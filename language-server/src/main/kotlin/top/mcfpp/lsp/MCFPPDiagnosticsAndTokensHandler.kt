package top.mcfpp.lsp

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensRangeParams
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.jsonrpc.messages.Either

private data class SemanticRangeKey(val line: Int, val character: Int, val endLine: Int, val endCharacter: Int)
private data class UndefinedReferenceKey(val name: String, val line: Int, val character: Int)
private data class DiagnosticKey(val line: Int, val character: Int, val message: String)
private data class DuplicateDeclarationKey(
    val kind: String,
    val name: String,
    val containerName: String,
    val receiverType: String,
    val typeScope: String,
    val functionScope: String,
    val topLevel: Boolean,
    val parameterSignature: String
)

internal class MCFPPDiagnosticsAndTokensHandler(private val workspace: MCFPPWorkspaceView) {
    fun semanticTokensFull(analysis: MCFPPDocumentAnalysis): SemanticTokens =
        SemanticTokens(encodeSemanticTokens(semanticTokenEntries(analysis)))

    fun semanticTokensRange(params: SemanticTokensRangeParams, analysis: MCFPPDocumentAnalysis): SemanticTokens {
        val entries = semanticTokenEntries(analysis).filter { rangesOverlap(it.range, params.range) }
        return SemanticTokens(encodeSemanticTokens(entries))
    }

    fun collectDiagnostics(document: MCFPPDocumentAnalysis): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        diagnostics += document.parserDiagnostics
        document.symbols
            .groupBy(::duplicateDeclarationKey)
            .values
            .filter { duplicates -> duplicates.size > 1 }
            .forEach { duplicates ->
                duplicates.drop(1).forEach { duplicate ->
                    diagnostics += Diagnostic(
                        duplicate.selectionRange,
                        "Duplicate declaration of `${duplicate.name}`",
                        DiagnosticSeverity.Warning,
                        "mcfpp"
                    )
                }
            }

        diagnostics += collectUndefinedVariableDiagnostics(document)
        diagnostics += collectInaccessibleMemberDiagnostics(document)
        diagnostics += collectTypeDiagnostics(document)
        document.referenceOccurrencesByName.values.flatten().filter { it.isAssignmentTarget }.forEach {
            val symbol = workspace.resolveReferenceOccurrence(document, it)
            if (symbol?.isReadOnly == true && (symbol.kind == SymbolKind.EnumMember ||
                        symbol.kind == SymbolKind.Property && symbol.isSynthetic)) {
                diagnostics += Diagnostic(it.range, "Enum member '${symbol.name}' is read-only", DiagnosticSeverity.Error, "mcfpp")
            }
        }

        return diagnostics
    }

    private fun semanticTokenEntries(analysis: MCFPPDocumentAnalysis): List<SemanticTokenEntry> {
        val cache = workspace.symbolCaches.forDocument(analysis)
        cache.semanticTokens?.let { return it }
        val entriesByRange = linkedMapOf<SemanticRangeKey, SemanticTokenEntry>()
        analysis.versionDirectives.forEach { range ->
            entriesByRange[semanticRangeKey(range)] = SemanticTokenEntry(range, "macro", 0)
        }
        analysis.symbols
            .asSequence()
            .filterNot { it.isExternal }
            .forEach { symbol ->
                val type = semanticTokenType(symbol)
                val modifiers = buildList {
                    add("declaration")
                    if (symbol.isReadOnly) add("readonly")
                    if (symbol.isStatic) add("static")
                    if (symbol.isStdlib) add("defaultLibrary")
                }
                val entry = SemanticTokenEntry(
                    symbol.selectionRange,
                    type,
                    MCFPPSemanticTokenLegend.modifierBits(*modifiers.toTypedArray())
                )
                entriesByRange[semanticRangeKey(entry.range)] = entry
            }
        analysis.referenceOccurrencesByName.values
            .asSequence()
            .flatten()
            .forEach { occurrence ->
                val symbol = workspace.resolveReferenceOccurrence(analysis, occurrence) ?: return@forEach
                val modifiers = if (symbol.isStdlib) {
                    MCFPPSemanticTokenLegend.modifierBits("defaultLibrary")
                } else {
                    0
                }
                entriesByRange.putIfAbsent(
                    semanticRangeKey(occurrence.range),
                    SemanticTokenEntry(occurrence.range, semanticTokenType(symbol), modifiers)
                )
            }
        return entriesByRange.values
            .filter { entry ->
                entry.range.start.line == entry.range.end.line &&
                    entry.range.end.character > entry.range.start.character
            }
            .sortedWith(compareBy({ it.range.start.line }, { it.range.start.character }, { it.range.end.character }))
            .also { cache.semanticTokens = it }
    }

    private fun semanticTokenType(symbol: MCFPPSymbol): String {
        if (symbol.isMacro || symbol.isNative && workspace.isMacroSignature(symbol)) return "macro"
        if (symbol.isNative) return "mniFunction"
        return when (symbol.kind) {
        SymbolKind.Namespace, SymbolKind.Module, SymbolKind.Package -> "namespace"
        SymbolKind.Class, SymbolKind.Object -> "class"
        SymbolKind.Enum -> "enum"
        SymbolKind.Struct -> "struct"
        SymbolKind.TypeParameter -> "typeParameter"
        SymbolKind.Variable, SymbolKind.Constant -> if (symbol.isParameter) "parameter" else "variable"
        SymbolKind.Field, SymbolKind.Property, SymbolKind.Key -> "property"
        SymbolKind.EnumMember -> "enumMember"
        SymbolKind.Function -> "function"
        SymbolKind.Method, SymbolKind.Constructor -> "method"
        else -> "variable"
        }
    }

    private fun encodeSemanticTokens(entries: List<SemanticTokenEntry>): List<Int> {
        val data = ArrayList<Int>(entries.size * 5)
        var previousLine = 0
        var previousCharacter = 0
        entries.forEach { entry ->
            val line = entry.range.start.line
            val character = entry.range.start.character
            val deltaLine = line - previousLine
            val deltaCharacter = if (deltaLine == 0) character - previousCharacter else character
            data += deltaLine
            data += deltaCharacter
            data += entry.range.end.character - character
            data += MCFPPSemanticTokenLegend.typeIndex(entry.type)
            data += entry.modifierBits
            previousLine = line
            previousCharacter = character
        }
        return data
    }

    private fun semanticRangeKey(range: Range): SemanticRangeKey =
        SemanticRangeKey(range.start.line, range.start.character, range.end.line, range.end.character)

    private fun rangesOverlap(left: Range, right: Range): Boolean {
        return !isBeforeOrEqual(left.end, right.start) && !isBeforeOrEqual(right.end, left.start)
    }

    private fun duplicateDeclarationKey(symbol: MCFPPSymbol) = DuplicateDeclarationKey(
        symbol.kind.name, symbol.name, symbol.containerName.orEmpty(), symbol.receiverType.orEmpty(),
        symbol.enclosingTypeScopeId.orEmpty(), symbol.enclosingFunctionScopeId.orEmpty(),
        symbol.topLevel, parameterSignatureKey(symbol)
    )

    private fun collectUndefinedVariableDiagnostics(document: MCFPPDocumentAnalysis): List<Diagnostic> {
        return document.referenceOccurrencesByName.values
            .flatten()
            .filter { shouldCheckUndefinedReference(document, it) }
            .filterNot { isReferenceResolved(document, it) }
            .distinctBy { UndefinedReferenceKey(it.name, it.range.start.line, it.range.start.character) }
            .map {
                Diagnostic(
                    it.range,
                    "Undefined symbol `${it.name}`",
                    DiagnosticSeverity.Error,
                    "mcfpp"
                ).apply { setCode("mcfpp.undefined-symbol") }
            }
    }

    private fun collectInaccessibleMemberDiagnostics(document: MCFPPDocumentAnalysis): List<Diagnostic> {
        return document.referenceOccurrencesByName.values
            .asSequence()
            .flatten()
            .mapNotNull { reference ->
                val receiver = document.receiverBefore(reference.range.end)
                val matchingMembers = if (receiver != null) {
                    val receiverType = workspace.calls.resolveReceiverTypeAt(document, receiver, reference.range.start)
                        ?: return@mapNotNull null
                    workspace.memberSymbolsForType(document, receiverType).filter { it.name == reference.name }
                } else if (reference.isCall && workspace.calls.resolveCallTarget(document, reference) == null) {
                    workspace.memberSymbolsForType(document, reference.name)
                        .filter { it.kind == SymbolKind.Constructor && it.name == reference.name }
                } else {
                    return@mapNotNull null
                }
                if (matchingMembers.isEmpty() || matchingMembers.any { workspace.isMemberAccessible(document, it, reference.range.start) }) {
                    return@mapNotNull null
                }
                val access = matchingMembers.first().accessModifier.name.lowercase()
                val subject = if (matchingMembers.first().kind == SymbolKind.Constructor) "constructor" else "member"
                Diagnostic(
                    reference.range,
                    "${access.replaceFirstChar(Char::uppercaseChar)} $subject `${reference.name}` is not accessible here",
                    DiagnosticSeverity.Error,
                    "mcfpp"
                )
            }
            .distinctBy { DiagnosticKey(it.range.start.line, it.range.start.character, it.message) }
            .toList()
    }

    private fun isReferenceResolved(document: MCFPPDocumentAnalysis, reference: MCFPPReferenceOccurrence): Boolean {
        if (workspace.resolveReferenceOccurrence(document, reference) != null) {
            return true
        }
        val receiver = document.receiverBefore(reference.range.end)
        if (receiver != null) {
            val receiverType = workspace.calls.resolveReceiverTypeAt(document, receiver, reference.range.start) ?: return false
            if (MCFPPDocumentIndex.isReferenceType(receiverType) && reference.name in setOf("tryRead", "set")) return true
            return workspace.receiverMembers(document, receiver, receiverType, reference.range.start).any { it.name == reference.name } ||
                workspace.memberSymbolsForType(document, receiverType).any { it.name == reference.name }
        }
        if (reference.isCall) {
            return workspace.memberSymbolsForType(document, reference.name)
                .any { it.kind == SymbolKind.Constructor && it.name == reference.name }
        }
        return false
    }

    private fun shouldCheckUndefinedReference(document: MCFPPDocumentAnalysis, reference: MCFPPReferenceOccurrence): Boolean {
        if (reference.isNamedArgument) return false
        if (reference.name in MCFPPDocumentIndex.keywords || reference.name in MCFPPDocumentIndex.builtinValues || reference.name in MCFPPDocumentIndex.builtinTypes || reference.name in MCFPPDocumentIndex.referenceFactories) {
            return false
        }
        val line = document.line(reference.range.start.line)
        val start = reference.range.start.character.coerceIn(0, line.length)
        val end = reference.range.end.character.coerceIn(0, line.length)
        val previousChar = line.take(start).trimEnd().lastOrNull()
        val nextChar = line.drop(end).trimStart().firstOrNull()
        if (previousChar == '@') {
            return false
        }
        if (nextChar == ':') {
            return false
        }
        val prefix = line.take(start).trimEnd()
        if (prefix.endsWith(" from") || prefix.endsWith("namespace") || prefix.endsWith("import")) {
            return false
        }
        if (line.trimStart().startsWith("import ")) {
            return false
        }
        return true
    }

    private fun collectTypeDiagnostics(document: MCFPPDocumentAnalysis): List<Diagnostic> {
        val (literals, _) = workspace.templateContext(document)
        val checker = workspace.typeChecker(document) { start, end ->
            literals.startingAt(start)?.takeIf { it.close() + 1 == end }?.let { literal ->
                if (literal.kind() == '{') literals.bestTypes(literal).firstOrNull() else literals.types(literal).firstOrNull()
            }
        }
        return checker.collectDiagnostics() + literals.diagnostics().map {
            Diagnostic(Range(offsetToPosition(document.text, it.start()), offsetToPosition(document.text, it.end())),
                it.message(), DiagnosticSeverity.Error, "mcfpp").apply { code = Either.forLeft("mcfpp.template-literal") }
        }
    }
}

internal data class SemanticTokenEntry(
    val range: Range,
    val type: String,
    val modifierBits: Int
)
