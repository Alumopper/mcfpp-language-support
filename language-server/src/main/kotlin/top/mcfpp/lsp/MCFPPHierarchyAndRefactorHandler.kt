package top.mcfpp.lsp

import org.eclipse.lsp4j.CallHierarchyIncomingCall
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyOutgoingCall
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.LinkedEditingRangeParams
import org.eclipse.lsp4j.LinkedEditingRanges
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.PrepareRenameResult
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.SelectionRange
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.TypeHierarchyItem
import org.eclipse.lsp4j.TypeHierarchyPrepareParams
import org.eclipse.lsp4j.TypeHierarchySubtypesParams
import org.eclipse.lsp4j.TypeHierarchySupertypesParams
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either3

internal class MCFPPHierarchyAndRefactorHandler(private val workspace: MCFPPWorkspaceView) {
    fun prepareCallHierarchy(params: CallHierarchyPrepareParams, analysis: MCFPPDocumentAnalysis): List<CallHierarchyItem> {
        val symbol = workspace.resolveSymbolAt(params, analysis)
            ?.takeIf { it.kind in CALLABLE_SYMBOL_KINDS && !it.isExternal }
            ?: return emptyList()
        return listOf(toCallHierarchyItem(symbol))
    }

    fun callHierarchyIncomingCalls(params: CallHierarchyIncomingCallsParams): List<CallHierarchyIncomingCall> {
        val target = findCallHierarchySymbol(params.item) ?: return emptyList()
        val grouped = linkedMapOf<String, Pair<MCFPPSymbol, MutableList<Range>>>()
        workspace.allAnalyses().values.forEach { document ->
            document.referenceOccurrencesByName[target.name].orEmpty()
                .asSequence()
                .filter { it.isCall }
                .filter { occurrence -> workspace.calls.resolveCallTarget(document, occurrence)?.let { sameSymbol(it, target) } == true }
                .forEach calls@{ occurrence ->
                    val caller = findEnclosingCallable(document, occurrence.range.start) ?: return@calls
                    val entry = grouped.getOrPut(symbolIdentityKey(caller)) { caller to mutableListOf() }
                    entry.second += occurrence.range
                }
        }
        return grouped.values.map { (caller, ranges) ->
            CallHierarchyIncomingCall(toCallHierarchyItem(caller), ranges.distinct())
        }
    }

    fun callHierarchyOutgoingCalls(params: CallHierarchyOutgoingCallsParams): List<CallHierarchyOutgoingCall> {
        val caller = findCallHierarchySymbol(params.item) ?: return emptyList()
        val document = workspace.allAnalyses()[caller.uri] ?: return emptyList()
        val grouped = linkedMapOf<String, Pair<MCFPPSymbol, MutableList<Range>>>()
        document.referenceOccurrencesByName.values
            .asSequence()
            .flatten()
            .filter { it.isCall && containsPosition(caller.fullRange, it.range.start) }
            .forEach { occurrence ->
                val target = workspace.calls.resolveCallTarget(document, occurrence)?.takeIf { !it.isExternal } ?: return@forEach
                val entry = grouped.getOrPut(symbolIdentityKey(target)) { target to mutableListOf() }
                entry.second += occurrence.range
            }
        return grouped.values.map { (target, ranges) ->
            CallHierarchyOutgoingCall(toCallHierarchyItem(target), ranges.distinct())
        }
    }

    fun prepareTypeHierarchy(params: TypeHierarchyPrepareParams, analysis: MCFPPDocumentAnalysis): List<TypeHierarchyItem> {
        val symbol = workspace.resolveSymbolAt(params, analysis)
            ?.takeIf { it.kind in TYPE_SYMBOL_KINDS && !it.isExternal }
            ?: return emptyList()
        return listOf(toTypeHierarchyItem(symbol))
    }

    fun typeHierarchySupertypes(params: TypeHierarchySupertypesParams): List<TypeHierarchyItem> {
        val symbol = findTypeHierarchySymbol(params.item) ?: return emptyList()
        return symbol.superTypes
            .mapNotNull { typeName -> workspace.resolveTypeSymbol(typeName, symbol) }
            .filterNot { it.isExternal }
            .distinctBy(::symbolIdentityKey)
            .map(::toTypeHierarchyItem)
    }

    fun typeHierarchySubtypes(params: TypeHierarchySubtypesParams): List<TypeHierarchyItem> {
        val symbol = findTypeHierarchySymbol(params.item) ?: return emptyList()
        return workspace.workspaceSymbols()
            .asSequence()
            .filter { it.topLevel && it.kind in TYPE_SYMBOL_KINDS && !it.isExternal }
            .filter { candidate ->
                candidate.superTypes.any { typeName ->
                    workspace.resolveTypeSymbol(typeName, candidate)?.let { sameSymbol(it, symbol) } == true
                }
            }
            .distinctBy(::symbolIdentityKey)
            .sortedBy { it.name }
            .map(::toTypeHierarchyItem)
            .toList()
    }

    fun codeAction(params: CodeActionParams, analysis: MCFPPDocumentAnalysis): List<Either<Command, CodeAction>> =
        buildCodeActions(analysis, params).map { Either.forRight<Command, CodeAction>(it) }

    fun rename(params: RenameParams, analysis: MCFPPDocumentAnalysis): WorkspaceEdit {
        importAliasAt(analysis, params.position)?.let { imported ->
            if (!isValidIdentifier(params.newName) || imported.alias == params.newName) {
                return WorkspaceEdit()
            }
            val edits = importAliasRanges(analysis, imported).map { TextEdit(it, params.newName) }
            return WorkspaceEdit(mapOf(analysis.uri to edits))
        }
        val target = workspace.resolveSymbolAt(params, analysis) ?: return WorkspaceEdit()
        if (!isRenameable(target) || !isValidIdentifier(params.newName)) {
            return WorkspaceEdit()
        }
        if (target.name == params.newName) {
            return WorkspaceEdit()
        }
        val changes = linkedMapOf<String, List<TextEdit>>()
        workspace.allAnalyses().values.forEach { document ->
            val edits = workspace.collectReferenceRanges(document, target, includeDeclaration = true).map { TextEdit(it, params.newName) }
            if (edits.isNotEmpty()) {
                changes[document.uri] = edits
            }
        }
        return WorkspaceEdit().apply { this.changes = changes }
    }

    fun prepareRename(params: PrepareRenameParams, analysis: MCFPPDocumentAnalysis): Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>? {
        importAliasAt(analysis, params.position)?.let { imported ->
            val range = analysis.wordRange(params.position) ?: imported.aliasRange ?: return null
            return Either3.forSecond(PrepareRenameResult(range, imported.alias.orEmpty()))
        }
        val target = workspace.resolveSymbolAt(params, analysis)?.takeIf(::isRenameable)
            ?: return null
        return Either3.forSecond(PrepareRenameResult(target.selectionRange, target.name))
    }

    fun foldingRange(analysis: MCFPPDocumentAnalysis): List<FoldingRange> =
        analysis.scopeRegions
            .asSequence()
            .map { it.range }
            .filter { it.end.line > it.start.line }
            .distinctBy { listOf(it.start.line, it.start.character, it.end.line, it.end.character).joinToString(":") }
            .map { range ->
                FoldingRange(range.start.line, range.end.line).apply {
                    startCharacter = range.start.character
                    endCharacter = range.end.character
                }
            }
            .toList()

    fun selectionRange(params: SelectionRangeParams, analysis: MCFPPDocumentAnalysis): List<SelectionRange> =
        params.positions.map { position -> buildSelectionRange(analysis, position) }

    fun linkedEditingRange(params: LinkedEditingRangeParams, analysis: MCFPPDocumentAnalysis): LinkedEditingRanges {
        val target = workspace.resolveSymbolAt(params, analysis)
        val ranges = target
            ?.let { workspace.collectReferenceRanges(analysis, it, includeDeclaration = true) }
            ?.distinctBy { listOf(it.start.line, it.start.character, it.end.line, it.end.character).joinToString(":") }
            .orEmpty()
            .filterNot { it.start == it.end }
        return LinkedEditingRanges(ranges).apply { wordPattern = "[A-Za-z_][A-Za-z0-9_]*" }
    }

    private fun toCallHierarchyItem(symbol: MCFPPSymbol): CallHierarchyItem {
        return CallHierarchyItem(symbol.name, symbol.kind, symbol.uri, symbol.fullRange, symbol.selectionRange).apply {
            detail = buildSignatureLabel(symbol)
            data = parameterSignatureKey(symbol)
        }
    }

    private fun toTypeHierarchyItem(symbol: MCFPPSymbol): TypeHierarchyItem {
        return TypeHierarchyItem(symbol.name, symbol.kind, symbol.uri, symbol.fullRange, symbol.selectionRange).apply {
            detail = buildString {
                symbol.namespaceName?.takeIf { it.isNotBlank() }?.let {
                    append(it)
                    append(':')
                }
                append(symbol.name)
                if (symbol.superTypes.isNotEmpty()) {
                    append(" : ")
                    append(symbol.superTypes.joinToString(", "))
                }
            }
            data = symbolIdentityKey(symbol)
        }
    }

    private fun findTypeHierarchySymbol(item: TypeHierarchyItem): MCFPPSymbol? {
        return workspace.allAnalyses()[item.uri]?.symbols?.firstOrNull { symbol ->
            symbol.name == item.name &&
                symbol.kind == item.kind &&
                symbol.selectionRange == item.selectionRange &&
                symbol.kind in TYPE_SYMBOL_KINDS
        }
    }

    private fun findCallHierarchySymbol(item: CallHierarchyItem): MCFPPSymbol? {
        return workspace.allAnalyses()[item.uri]?.symbols?.firstOrNull { symbol ->
            symbol.name == item.name &&
                symbol.kind == item.kind &&
                symbol.selectionRange == item.selectionRange &&
                symbol.kind in CALLABLE_SYMBOL_KINDS
        }
    }

    private fun findEnclosingCallable(analysis: MCFPPDocumentAnalysis, position: Position): MCFPPSymbol? {
        return analysis.symbols
            .asSequence()
            .filter { it.kind in CALLABLE_SYMBOL_KINDS && containsPosition(it.fullRange, position) }
            .minByOrNull { rangeSpan(it.fullRange) }
    }

    private fun rangeSpan(range: Range): Long {
        val lineSpan = (range.end.line - range.start.line).coerceAtLeast(0).toLong()
        val characterSpan = if (lineSpan == 0L) {
            (range.end.character - range.start.character).coerceAtLeast(0).toLong()
        } else {
            range.end.character.toLong()
        }
        return lineSpan * 1_000_000L + characterSpan
    }

    private fun buildCodeActions(
        analysis: MCFPPDocumentAnalysis,
        params: CodeActionParams
    ): List<CodeAction> {
        return params.context.diagnostics.flatMap { diagnostic ->
            buildList {
                duplicateDeclarationAction(analysis, params.textDocument.uri, diagnostic)?.let(::add)
                missingSemicolonAction(params.textDocument.uri, diagnostic)?.let(::add)
            }
        }
    }

    private fun duplicateDeclarationAction(
        analysis: MCFPPDocumentAnalysis,
        uri: String,
        diagnostic: Diagnostic
    ): CodeAction? {
        if (!diagnostic.message.startsWith("Duplicate declaration of `")) {
            return null
        }
        val symbol = analysis.symbols.firstOrNull { it.selectionRange == diagnostic.range } ?: return null
        val suggestedName = suggestUniqueName(symbol.name, analysis)
        val edit = WorkspaceEdit().apply {
            changes = mapOf(uri to listOf(TextEdit(symbol.selectionRange, suggestedName)))
        }
        return CodeAction().apply {
            title = "Rename to $suggestedName"
            kind = CodeActionKind.QuickFix
            diagnostics = listOf(diagnostic)
            isPreferred = true
            this.edit = edit
        }
    }

    private fun missingSemicolonAction(uri: String, diagnostic: Diagnostic): CodeAction? {
        if (!diagnostic.message.contains("missing ';'")) {
            return null
        }
        val edit = WorkspaceEdit().apply {
            changes = mapOf(uri to listOf(TextEdit(diagnostic.range.start.let { Range(it, it) }, ";")))
        }
        return CodeAction().apply {
            title = "Insert ';'"
            kind = CodeActionKind.QuickFix
            diagnostics = listOf(diagnostic)
            this.edit = edit
        }
    }

    private fun suggestUniqueName(baseName: String, analysis: MCFPPDocumentAnalysis): String {
        val usedNames = analysis.symbols.map { it.name }.toSet()
        var index = 2
        var candidate = "${baseName}_$index"
        while (candidate in usedNames) {
            index += 1
            candidate = "${baseName}_$index"
        }
        return candidate
    }

    private fun buildSelectionRange(analysis: MCFPPDocumentAnalysis, position: Position): SelectionRange {
        val candidateRanges = buildList {
            analysis.wordRange(position)?.let(::add)
            analysis.symbols
                .map { it.selectionRange }
                .filter { containsPosition(it, position) }
                .sortedWith(compareByDescending<Range> { it.start.line }
                    .thenByDescending { it.start.character }
                    .thenBy { it.end.line }
                    .thenBy { it.end.character })
                .forEach(::add)
            analysis.scopeRegions
                .map { it.range }
                .filter { containsPosition(it, position) }
                .sortedWith(compareByDescending<Range> { it.start.line }
                    .thenByDescending { it.start.character }
                    .thenBy { it.end.line }
                    .thenBy { it.end.character })
                .forEach(::add)
            add(Range(Position(position.line, 0), Position(position.line, analysis.line(position.line).length)))
            add(fullDocumentRange(analysis))
        }
            .distinctBy { listOf(it.start.line, it.start.character, it.end.line, it.end.character).joinToString(":") }
            .filterNot { it.start == it.end }

        return candidateRanges
            .asReversed()
            .fold<Range, SelectionRange?>(null) { parent, range -> SelectionRange(range, parent) }
            ?: SelectionRange(Range(position, position), null)
    }

    private fun fullDocumentRange(analysis: MCFPPDocumentAnalysis): Range {
        val lines = analysis.text.split("\n")
        val lastLineIndex = (lines.size - 1).coerceAtLeast(0)
        val lastLineLength = lines.getOrElse(lastLineIndex) { "" }.length
        return Range(Position(0, 0), Position(lastLineIndex, lastLineLength))
    }

    private fun importAliasAt(analysis: MCFPPDocumentAnalysis, position: Position): MCFPPImport? {
        val alias = analysis.wordAt(position) ?: return null
        val imported = analysis.imports.firstOrNull { it.alias == alias } ?: return null
        if (imported.aliasRange?.let { containsPosition(it, position) } == true) {
            return imported
        }
        val occurrence = analysis.referenceOccurrencesByName[alias]
            .orEmpty()
            .firstOrNull { containsPosition(it.range, position) }
            ?: return null
        val resolved = workspace.resolveReferenceOccurrence(analysis, occurrence) ?: return null
        return imported.takeIf {
            resolved.name == imported.importedName && resolved.namespaceName == imported.namespace
        }
    }

    private fun importAliasRanges(analysis: MCFPPDocumentAnalysis, imported: MCFPPImport): List<Range> {
        val alias = imported.alias ?: return emptyList()
        return buildList {
            imported.aliasRange?.let(::add)
            analysis.referenceOccurrencesByName[alias].orEmpty().forEach { occurrence ->
                val resolved = workspace.resolveReferenceOccurrence(analysis, occurrence)
                if (resolved?.name == imported.importedName && resolved.namespaceName == imported.namespace) {
                    add(occurrence.range)
                }
            }
        }.distinctBy { range ->
            listOf(range.start.line, range.start.character, range.end.line, range.end.character).joinToString(":")
        }
    }

    private fun isRenameable(symbol: MCFPPSymbol): Boolean {
        return !symbol.isExternal && !symbol.isSynthetic && symbol.kind in RENAMABLE_SYMBOL_KINDS
    }

    private fun isValidIdentifier(name: String): Boolean {
        return IDENTIFIER_REGEX.matches(name) && name !in MCFPPDocumentIndex.keywords
    }

    private companion object {
        val RENAMABLE_SYMBOL_KINDS = setOf(
            SymbolKind.Class,
            SymbolKind.Enum,
            SymbolKind.TypeParameter,
            SymbolKind.Function,
            SymbolKind.Method,
            SymbolKind.Field,
            SymbolKind.Property,
            SymbolKind.Variable,
            SymbolKind.EnumMember
        )
    }
}
