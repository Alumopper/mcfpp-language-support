package top.mcfpp.lsp

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.CallHierarchyIncomingCall
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyOutgoingCall
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.InsertTextFormat
import top.mcfpp.language.VersionPreprocessor
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DeclarationParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightKind
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.ImplementationParams
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintKind
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.LinkedEditingRangeParams
import org.eclipse.lsp4j.LinkedEditingRanges
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.PrepareRenameResult
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.SelectionRange
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.SemanticTokensRangeParams
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.SignatureInformation
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.TypeDefinitionParams
import org.eclipse.lsp4j.TypeHierarchyItem
import org.eclipse.lsp4j.TypeHierarchyPrepareParams
import org.eclipse.lsp4j.TypeHierarchySubtypesParams
import org.eclipse.lsp4j.TypeHierarchySupertypesParams
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.services.TextDocumentService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class MCFPPTextDocumentService(private val server: MCFPPLanguageServer) : TextDocumentService {
    private class OpenDocument(val text: String, val version: Int) {
        var targetVersion: String? = null
        var parsed: CompletableFuture<MCFPPDocumentAnalysis>? = null
    }

    private class SymbolCaches {
        @Volatile var workspace: List<MCFPPSymbol>? = null
        private val documents = ConcurrentHashMap<String, DocumentCaches>()

        fun forDocument(analysis: MCFPPDocumentAnalysis): DocumentCaches =
            documents.compute(analysis.uri) { _, cached ->
                cached?.takeIf { it.analysis === analysis } ?: DocumentCaches(analysis)
            }!!
    }

    private class DocumentCaches(val analysis: MCFPPDocumentAnalysis) {
        @Volatile var visible: List<MCFPPSymbol>? = null
        @Volatile var semanticTokens: List<SemanticTokenEntry>? = null
        val members = ConcurrentHashMap<String, List<MCFPPSymbol>>()
    }

    private val documents = ConcurrentHashMap<String, OpenDocument>()
    private val analyses = ConcurrentHashMap<String, MCFPPDocumentAnalysis>()
    private val projectIndex = MCFPPProjectIndex()
    @Volatile private var symbolCaches = SymbolCaches()
    private val projectRefreshLock = Any()
    private val dependentDiagnosticsLock = Any()
    private val analysisExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "mcfpp-analysis").apply { isDaemon = true }
    }
    private val editorExecutor = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "mcfpp-editor").apply { isDaemon = true }
    }
    private val pendingDocumentAnalyses = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private var pendingProjectRefresh: ScheduledFuture<*>? = null
    private var pendingDependentDiagnosticsRefresh: ScheduledFuture<*>? = null
    @Volatile
    private var workspaceRootUris: List<String> = emptyList()

    private enum class CompletionContext {
        DEFAULT,
        VALUE,
        TYPE
    }

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val document = params.textDocument
        synchronized(documents) {
            val snapshot = OpenDocument(document.text, document.version)
            documents[document.uri] = snapshot
            scheduleDocumentAnalysis(document.uri, snapshot, 0)
        }
    }

    private data class SemanticTokenEntry(
        val range: Range,
        val type: String,
        val modifierBits: Int
    )

    override fun didChange(params: DidChangeTextDocumentParams) {
        val uri = params.textDocument.uri
        val version = params.textDocument.version
        synchronized(documents) {
            val previous = documents[uri] ?: return
            if (version <= previous.version) return
            val snapshot = OpenDocument(MCFPPTextChanges.apply(previous.text, params.contentChanges), version)
            documents[uri] = snapshot
            scheduleDocumentAnalysis(uri, snapshot, DOCUMENT_CHANGE_DEBOUNCE_MS)
        }
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = params.textDocument.uri
        synchronized(documents) {
            documents.remove(uri)
            analyses.remove(uri)
            pendingDocumentAnalyses.remove(uri)?.cancel(false)
            invalidateWorkspaceSymbolCache()
        }
        scheduleProjectIndexRefresh(0)
        server.getClientOrNull()?.publishDiagnostics(PublishDiagnosticsParams(uri, emptyList()))
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        val uri = params.textDocument.uri
        val savedText = params.text ?: return
        synchronized(documents) {
            val previous = documents[uri] ?: return
            if (previous.text != savedText) {
                val snapshot = OpenDocument(savedText, previous.version)
                documents[uri] = snapshot
                scheduleDocumentAnalysis(uri, snapshot, 0)
            }
        }
    }

    override fun completion(params: CompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>> =
        withEditorAnalysis(params.textDocument.uri, Either.forLeft(emptyList())) { analysis ->
            buildCompletions(params, analysis)
        }

    private fun buildCompletions(
        params: CompletionParams,
        analysis: MCFPPDocumentAnalysis
    ): Either<List<CompletionItem>, CompletionList> {
        val directiveStart = VersionPreprocessor.directivePrefixStart(
            analysis.text, MCFPPTextChanges.positionToOffset(analysis.text, params.position)
        )
        if (directiveStart >= 0) {
            val start = offsetToPosition(analysis.text, directiveStart)
            val items = listOf(
                "#if" to "#if MC >= \${1:26.3}",
                "#elif" to "#elif MC >= \${1:26.1}",
                "#else" to "#else",
                "#endif" to "#endif"
            ).map { (label, snippet) ->
                CompletionItem(label).apply {
                    kind = CompletionItemKind.Keyword
                    detail = "Minecraft version conditional compilation"
                    insertTextFormat = InsertTextFormat.Snippet
                    textEdit = Either.forLeft(TextEdit(
                        Range(start, params.position), snippet
                    ))
                }
            }
            return Either.forLeft(items)
        }
        templateCompletions(analysis, params.position)?.let {
            return Either.forLeft(it)
        }
        val receiver = analysis.receiverBefore(params.position)
        val namespaceQualifier = namespaceQualifierBefore(analysis, params.position)
        val context = determineCompletionContext(analysis, params.position)
        val items = if (receiver != null) {
            resolveMemberCompletions(analysis, receiver, params.position)
        } else if (namespaceQualifier != null) {
            val namespace = resolveNamespaceQualifier(analysis, namespaceQualifier)
            workspaceSymbols()
                .filter { it.topLevel && it.namespaceName == namespace }
                .map(::symbolCompletion)
        } else if (context == CompletionContext.TYPE) {
            buildList {
                addAll(MCFPPDocumentIndex.builtinTypes.map { typeCompletion(it) })
                addAll(
                    globalCompletionItems(analysis) { it.kind in TYPE_DEFINITION_SYMBOL_KINDS }
                )
                addAll(
                    visibleSymbolsForCompletion(analysis, params.position)
                        .filter { it.kind == SymbolKind.TypeParameter }
                        .map(::symbolCompletion)
                )
            }
        } else {
            buildList {
                addAll(MCFPPDocumentIndex.keywords
                    .filter { context != CompletionContext.VALUE || it in top.mcfpp.language.CompletionScope.VALUE_KEYWORDS }
                    .map { keywordCompletion(it) })
                addAll(MCFPPDocumentIndex.builtinValues.map { valueCompletion(it) })
                addAll(contextualEnumMembers(analysis, params.position).map(::symbolCompletion))
                addAll(MCFPPDocumentIndex.referenceFactories.map { name -> CompletionItem(name).apply {
                    kind = CompletionItemKind.Function
                    detail = "Create a reference from an explicit Minecraft address"
                } })
                addAll(globalCompletionItems(analysis) {
                    it.kind !in TYPE_DEFINITION_SYMBOL_KINDS || it.name !in top.mcfpp.language.EntityBuiltins.TYPES + top.mcfpp.language.ContextBuiltins.TYPES
                })
                analysis.innermostTypeScope(params.position)?.name?.let { currentType ->
                    addAll(
                        accessibleMemberSymbolsForType(analysis, currentType, params.position)
                            .filter { it.kind != SymbolKind.Constructor }
                            .map(::symbolCompletion)
                    )
                }
                addAll(visibleSymbolsForCompletion(analysis, params.position).map { symbolCompletion(it) })
            }
        }
        return Either.forLeft(items.distinctBy { it.label })
    }

    override fun hover(params: HoverParams): CompletableFuture<Hover> =
        withEditorAnalysis(params.textDocument.uri, null) { analysis ->
            buildHover(params, analysis)
        }

    private fun buildHover(params: HoverParams, analysis: MCFPPDocumentAnalysis): Hover? {
        val symbol = resolveSymbolAt(params, analysis) ?: return null
        val markdown = buildString {
            if (symbol.kind in setOf(SymbolKind.Function, SymbolKind.Method, SymbolKind.Constructor)) {
                append("```mcfpp\n")
                append(buildSignatureLabel(symbol))
                append("\n```")
            } else {
                append(symbol.kind.name.lowercase())
                append(" `")
                append(symbol.name)
                append('`')
                val owner = if (symbol.uri == analysis.uri) analysis else allAnalyses()[symbol.uri]
                (symbol.typeName ?: owner?.let { declarationType(it, symbol) })?.takeIf { it.isNotBlank() }?.let {
                    append(" : `")
                    append(it)
                    append('`')
                }
            }
            symbol.receiverType?.let {
                append("\n\nReceiver: `")
                append(it)
                append('`')
            }
            symbol.containerName?.takeIf { it.isNotBlank() }?.let {
                append("\n\nContainer: `")
                append(it)
                append('`')
            }
            symbol.documentation?.takeIf { it.isNotBlank() }?.let {
                append("\n\n---\n\n")
                append(it)
            }
            if (symbol.kind == SymbolKind.EnumMember && symbol.detail != null) append("\n\n`${symbol.detail}`")
        }
        val hover = Hover()
        hover.contents = Either.forRight(MarkupContent(MarkupKind.MARKDOWN, markdown))
        hover.range = analysis.wordRange(params.position) ?: symbol.selectionRange
        return hover
    }

    // LSP 3.17 signature help: https://github.com/microsoft/language-server-protocol/blob/gh-pages/_specifications/lsp/3.17/language/signatureHelp.md
    override fun signatureHelp(params: SignatureHelpParams): CompletableFuture<SignatureHelp> =
        withEditorAnalysis(params.textDocument.uri, null) { analysis ->
            val resolved = MCFPPCallContextFinder.findCandidates(analysis.text, params.position)
                .firstNotNullOfOrNull { context ->
                    val symbols = resolveCallableSymbols(analysis, context, params.position)
                    symbols.takeIf { it.isNotEmpty() }?.let { context to it }
                }
                ?: return@withEditorAnalysis null
            val (context, symbols) = resolved
            val scored = symbols.map { symbol ->
                symbol to signatureMatchScore(symbol, context, analysis, params.position)
            }
            val activeSignature = scored.indices.maxByOrNull { scored[it].second } ?: 0
            val signatureInformation = scored.map { (symbol, _) ->
                buildSignatureInformation(symbol, context)
            }
            val activeParameter = signatureInformation.getOrNull(activeSignature)?.activeParameter
            SignatureHelp(signatureInformation, activeSignature, activeParameter)
        }

    // LSP 3.17 call hierarchy: https://github.com/microsoft/language-server-protocol/blob/gh-pages/_specifications/lsp/3.17/language/callHierarchy.md
    override fun prepareCallHierarchy(params: CallHierarchyPrepareParams): CompletableFuture<List<CallHierarchyItem>> =
        withEditorAnalysis(params.textDocument.uri, emptyList()) { analysis ->
            val symbol = resolveSymbolAt(params, analysis)
                ?.takeIf { it.kind in CALLABLE_SYMBOL_KINDS && !it.isExternal }
                ?: return@withEditorAnalysis emptyList()
            listOf(toCallHierarchyItem(symbol))
        }

    override fun callHierarchyIncomingCalls(
        params: CallHierarchyIncomingCallsParams
    ): CompletableFuture<List<CallHierarchyIncomingCall>> =
        CompletableFuture.supplyAsync({
            val target = findCallHierarchySymbol(params.item) ?: return@supplyAsync emptyList()
            val grouped = linkedMapOf<String, Pair<MCFPPSymbol, MutableList<Range>>>()
            allAnalyses().values.forEach { document ->
                document.referenceOccurrencesByName[target.name].orEmpty()
                    .asSequence()
                    .filter { it.isCall }
                    .filter { occurrence -> resolveCallTarget(document, occurrence)?.let { sameSymbol(it, target) } == true }
                    .forEach calls@{ occurrence ->
                        val caller = findEnclosingCallable(document, occurrence.range.start) ?: return@calls
                        val entry = grouped.getOrPut(symbolIdentityKey(caller)) { caller to mutableListOf() }
                        entry.second += occurrence.range
                    }
            }
            grouped.values.map { (caller, ranges) ->
                CallHierarchyIncomingCall(toCallHierarchyItem(caller), ranges.distinct())
            }
        }, editorExecutor)

    override fun callHierarchyOutgoingCalls(
        params: CallHierarchyOutgoingCallsParams
    ): CompletableFuture<List<CallHierarchyOutgoingCall>> =
        CompletableFuture.supplyAsync({
            val caller = findCallHierarchySymbol(params.item) ?: return@supplyAsync emptyList()
            val document = allAnalyses()[caller.uri] ?: return@supplyAsync emptyList()
            val grouped = linkedMapOf<String, Pair<MCFPPSymbol, MutableList<Range>>>()
            document.referenceOccurrencesByName.values
                .asSequence()
                .flatten()
                .filter { it.isCall && containsPosition(caller.fullRange, it.range.start) }
                .forEach { occurrence ->
                    val target = resolveCallTarget(document, occurrence)?.takeIf { !it.isExternal } ?: return@forEach
                    val entry = grouped.getOrPut(symbolIdentityKey(target)) { target to mutableListOf() }
                    entry.second += occurrence.range
                }
            grouped.values.map { (target, ranges) ->
                CallHierarchyOutgoingCall(toCallHierarchyItem(target), ranges.distinct())
            }
        }, editorExecutor)

    override fun prepareTypeHierarchy(params: TypeHierarchyPrepareParams): CompletableFuture<List<TypeHierarchyItem>> =
        withEditorAnalysis(params.textDocument.uri, emptyList()) { analysis ->
            val symbol = resolveSymbolAt(params, analysis)
                ?.takeIf { it.kind in TYPE_SYMBOL_KINDS && !it.isExternal }
                ?: return@withEditorAnalysis emptyList()
            listOf(toTypeHierarchyItem(symbol))
        }

    override fun typeHierarchySupertypes(
        params: TypeHierarchySupertypesParams
    ): CompletableFuture<List<TypeHierarchyItem>> =
        CompletableFuture.supplyAsync({
            val symbol = findTypeHierarchySymbol(params.item) ?: return@supplyAsync emptyList()
            symbol.superTypes
                .mapNotNull { typeName -> resolveTypeSymbol(typeName, symbol) }
                .filterNot { it.isExternal }
                .distinctBy(::symbolIdentityKey)
                .map(::toTypeHierarchyItem)
        }, editorExecutor)

    override fun typeHierarchySubtypes(
        params: TypeHierarchySubtypesParams
    ): CompletableFuture<List<TypeHierarchyItem>> =
        CompletableFuture.supplyAsync({
            val symbol = findTypeHierarchySymbol(params.item) ?: return@supplyAsync emptyList()
            workspaceSymbols()
                .asSequence()
                .filter { it.topLevel && it.kind in TYPE_SYMBOL_KINDS && !it.isExternal }
                .filter { candidate ->
                    candidate.superTypes.any { typeName ->
                        resolveTypeSymbol(typeName, candidate)?.let { sameSymbol(it, symbol) } == true
                    }
                }
                .distinctBy(::symbolIdentityKey)
                .sortedBy { it.name }
                .map(::toTypeHierarchyItem)
                .toList()
        }, editorExecutor)

    override fun definition(params: DefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> =
        withEditorAnalysis(params.textDocument.uri, Either.forLeft(emptyList())) { analysis ->
            val locations = resolveSymbolAt(params, analysis)?.takeIf { !it.isExternal || it.uri.endsWith(".mcfpp") }?.location()?.let(::listOf).orEmpty()
            Either.forLeft(locations)
        }

    override fun declaration(params: DeclarationParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> =
        withEditorAnalysis(params.textDocument.uri, Either.forLeft(emptyList())) { analysis ->
            val locations = resolveSymbolAt(params, analysis)?.takeIf { !it.isExternal || it.uri.endsWith(".mcfpp") }?.location()?.let(::listOf).orEmpty()
            Either.forLeft(locations)
        }

    override fun references(params: ReferenceParams): CompletableFuture<List<Location>> =
        withEditorAnalysis(params.textDocument.uri, emptyList()) { analysis ->
            val target = resolveSymbolAt(params, analysis) ?: return@withEditorAnalysis emptyList()
            findReferences(target, params.context.isIncludeDeclaration)
        }

    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> =
        withEditorAnalysis(params.textDocument.uri, emptyList()) { analysis ->
            buildDocumentSymbols(analysis).map { Either.forRight<SymbolInformation, DocumentSymbol>(it) }
        }

    override fun typeDefinition(params: TypeDefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> =
        withEditorAnalysis(params.textDocument.uri, Either.forLeft(emptyList())) { analysis ->
            val target = resolveSymbolAt(params, analysis) ?: return@withEditorAnalysis Either.forLeft(emptyList())
            val symbol = if (target.kind in TYPE_DEFINITION_SYMBOL_KINDS) {
                target
            } else {
                target.typeName?.let { resolveTypeSymbol(it, target) }
            }
            val locations = symbol
                ?.takeIf { !it.isExternal }
                ?.location()
                ?.let(::listOf)
                .orEmpty()
            Either.forLeft(locations)
        }

    override fun implementation(params: ImplementationParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> =
        withEditorAnalysis(params.textDocument.uri, Either.forLeft(emptyList())) { analysis ->
            val target = resolveSymbolAt(params, analysis) ?: return@withEditorAnalysis Either.forLeft(emptyList())
            if (target.isExternal) Either.forLeft(emptyList()) else Either.forLeft(findImplementations(target))
        }

    override fun documentHighlight(params: DocumentHighlightParams): CompletableFuture<List<DocumentHighlight>> =
        withEditorAnalysis(params.textDocument.uri, emptyList()) { analysis ->
            val target = resolveSymbolAt(params, analysis) ?: return@withEditorAnalysis emptyList()
            collectReferenceRanges(analysis, target, includeDeclaration = true)
                .map { DocumentHighlight(it, DocumentHighlightKind.Text) }
        }

    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> =
        withEditorAnalysis(params.textDocument.uri, emptyList()) { analysis ->
            buildCodeActions(analysis, params).map { Either.forRight<Command, CodeAction>(it) }
        }

    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit> =
        withEditorAnalysis(params.textDocument.uri, WorkspaceEdit()) { analysis ->
            importAliasAt(analysis, params.position)?.let { imported ->
                if (!isValidIdentifier(params.newName) || imported.alias == params.newName) {
                    return@withEditorAnalysis WorkspaceEdit()
                }
                val edits = importAliasRanges(analysis, imported).map { TextEdit(it, params.newName) }
                return@withEditorAnalysis WorkspaceEdit(mapOf(analysis.uri to edits))
            }
            val target = resolveSymbolAt(params, analysis) ?: return@withEditorAnalysis WorkspaceEdit()
            if (!isRenameable(target) || !isValidIdentifier(params.newName)) {
                return@withEditorAnalysis WorkspaceEdit()
            }
            if (target.name == params.newName) {
                return@withEditorAnalysis WorkspaceEdit()
            }
            val changes = linkedMapOf<String, List<TextEdit>>()
            allAnalyses().values.forEach { document ->
                val edits = collectReferenceRanges(document, target, includeDeclaration = true).map { TextEdit(it, params.newName) }
                if (edits.isNotEmpty()) {
                    changes[document.uri] = edits
                }
            }
            WorkspaceEdit().apply { this.changes = changes }
        }

    override fun prepareRename(
        params: PrepareRenameParams
    ): CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> =
        withEditorAnalysis(params.textDocument.uri, null) { analysis ->
            importAliasAt(analysis, params.position)?.let { imported ->
                val range = analysis.wordRange(params.position) ?: imported.aliasRange ?: return@withEditorAnalysis null
                return@withEditorAnalysis Either3.forSecond(PrepareRenameResult(range, imported.alias.orEmpty()))
            }
            val target = resolveSymbolAt(params, analysis)?.takeIf(::isRenameable)
                ?: return@withEditorAnalysis null
            Either3.forSecond(PrepareRenameResult(target.selectionRange, target.name))
        }

    override fun foldingRange(params: FoldingRangeRequestParams): CompletableFuture<List<FoldingRange>> =
        withEditorAnalysis(params.textDocument.uri, emptyList()) { analysis ->
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
        }

    override fun selectionRange(params: SelectionRangeParams): CompletableFuture<List<SelectionRange>> =
        withEditorAnalysis(params.textDocument.uri, params.positions.map { SelectionRange(Range(it, it), null) }) { analysis ->
            params.positions.map { position -> buildSelectionRange(analysis, position) }
        }

    override fun linkedEditingRange(params: LinkedEditingRangeParams): CompletableFuture<LinkedEditingRanges> =
        withEditorAnalysis(params.textDocument.uri, LinkedEditingRanges(emptyList())) { analysis ->
            val target = resolveSymbolAt(params, analysis)
            val ranges = target
                ?.let { collectReferenceRanges(analysis, it, includeDeclaration = true) }
                ?.distinctBy { listOf(it.start.line, it.start.character, it.end.line, it.end.character).joinToString(":") }
                .orEmpty()
                .filterNot { it.start == it.end }
            LinkedEditingRanges(ranges).apply { wordPattern = "[A-Za-z_][A-Za-z0-9_]*" }
        }

    override fun formatting(params: DocumentFormattingParams): CompletableFuture<List<TextEdit>> {
        val text = documents[params.textDocument.uri]?.text
            ?: return CompletableFuture.completedFuture(emptyList())
        return CompletableFuture.supplyAsync({ MCFPPFormatter.format(text, params.options) }, editorExecutor)
    }

    override fun rangeFormatting(params: DocumentRangeFormattingParams): CompletableFuture<List<TextEdit>> {
        val text = documents[params.textDocument.uri]?.text
            ?: return CompletableFuture.completedFuture(emptyList())
        return CompletableFuture.supplyAsync({ MCFPPFormatter.format(text, params.options, params.range) }, editorExecutor)
    }

    fun workspaceSymbolsAsync(query: String): CompletableFuture<List<MCFPPSymbol>> =
        CompletableFuture.supplyAsync({ workspaceSymbols(query) }, editorExecutor)

    override fun semanticTokensFull(params: SemanticTokensParams): CompletableFuture<SemanticTokens> =
        withEditorAnalysis(params.textDocument.uri, SemanticTokens(emptyList())) { analysis ->
            SemanticTokens(encodeSemanticTokens(semanticTokenEntries(analysis)))
        }

    override fun semanticTokensRange(params: SemanticTokensRangeParams): CompletableFuture<SemanticTokens> =
        withEditorAnalysis(params.textDocument.uri, SemanticTokens(emptyList())) { analysis ->
            val entries = semanticTokenEntries(analysis).filter { rangesOverlap(it.range, params.range) }
            SemanticTokens(encodeSemanticTokens(entries))
        }

    override fun inlayHint(params: InlayHintParams): CompletableFuture<List<InlayHint>> =
        withEditorAnalysis(params.textDocument.uri, emptyList()) { analysis -> inlayHints(params, analysis) }

    private fun inlayHints(params: InlayHintParams, analysis: MCFPPDocumentAnalysis): List<InlayHint> {
        val checker by lazy { typeChecker(analysis) }
        val inferredTypeHints = analysis.symbols
            .asSequence()
            .filter { symbol ->
                !symbol.isExternal &&
                    !symbol.isParameter &&
                    symbol.typeName == null &&
                    symbol.kind in setOf(SymbolKind.Variable, SymbolKind.Field) &&
                    containsPosition(params.range, symbol.selectionRange.end)
            }
            .mapNotNull { symbol -> inferredTypeHint(symbol, checker) }
        val parameterHints = analysis.referenceOccurrencesByName.values
            .asSequence()
            .flatten()
            .filter { occurrence ->
                occurrence.isCall && (occurrence.normalArgumentRanges.asSequence() + occurrence.readOnlyArgumentRanges)
                    .any { containsPosition(params.range, it.start) }
            }
            .flatMap { occurrence -> parameterInlayHints(analysis, occurrence).asSequence() }
            .filter { hint -> containsPosition(params.range, hint.position) }
        val hints = (inferredTypeHints + parameterHints)
            .distinctBy { hint -> listOf(hint.position.line, hint.position.character, hint.label.toString()).joinToString("|") }
            .sortedWith(compareBy({ it.position.line }, { it.position.character }))
            .toList()
        return hints
    }

    fun workspaceSymbols(query: String = ""): List<MCFPPSymbol> {
        val lowered = query.trim().lowercase()
        val symbols = cachedWorkspaceSymbols()
        if (lowered.isBlank()) {
            return symbols
        }
        return symbols
            .filter {
                it.name.lowercase().contains(lowered) ||
                    (it.containerName?.lowercase()?.contains(lowered) == true) ||
                    (it.namespaceName?.lowercase()?.contains(lowered) == true)
            }
            .sortedBy { it.name }
    }

    fun setWorkspaceRoots(rootUris: List<String>) {
        workspaceRootUris = rootUris.distinct()
        projectIndex.setWorkspaceRoots(workspaceRootUris)
        scheduleProjectIndexRefresh(0)
    }

    fun updateWorkspaceRoots(addedUris: List<String>, removedUris: List<String>) {
        val removed = removedUris.toSet()
        setWorkspaceRoots((workspaceRootUris.filterNot { it in removed } + addedUris).distinct())
    }

    fun refreshProjectIndex() {
        scheduleProjectIndexRefresh(PROJECT_CHANGE_DEBOUNCE_MS)
    }

    fun updateMniSymbols(snapshot: top.mcfpp.language.MniSymbolSnapshot) {
        projectIndex.setMniSymbols(snapshot)
        refreshProjectIndex()
    }

    fun refreshProjectIndex(changedUris: List<String>) {
        if (changedUris.none(projectIndex::isRelevantWorkspaceChange)) {
            return
        }
        refreshProjectIndex()
    }

    fun shutdown() {
        pendingDocumentAnalyses.values.forEach { it.cancel(false) }
        synchronized(projectRefreshLock) {
            pendingProjectRefresh?.cancel(false)
            pendingProjectRefresh = null
        }
        synchronized(dependentDiagnosticsLock) {
            pendingDependentDiagnosticsRefresh?.cancel(false)
            pendingDependentDiagnosticsRefresh = null
        }
        analysisExecutor.shutdownNow()
        editorExecutor.shutdownNow()
    }

    private fun <T> withEditorAnalysis(uri: String, missing: T?, render: (MCFPPDocumentAnalysis) -> T?): CompletableFuture<T> {
        val document = documents[uri] ?: return CompletableFuture.completedFuture(missing)
        val parsed = parseDocument(uri, document)
        return parsed.thenApplyAsync({ analysis ->
            requireCurrent(uri, document, analysis)
            val result = render(analysis)
            requireCurrent(uri, document, analysis)
            result
        }, editorExecutor)
    }

    private fun requireCurrent(uri: String, document: OpenDocument, analysis: MCFPPDocumentAnalysis? = null) {
        if (documents[uri] !== document || analysis != null && analyses[uri] !== analysis) {
            throw ResponseErrorException(ResponseError(ResponseErrorCode.ContentModified, "Document changed during analysis", null))
        }
    }

    private fun parseDocument(uri: String, document: OpenDocument): CompletableFuture<MCFPPDocumentAnalysis> =
        synchronized(documents) {
            val version = projectIndex.targetVersionForUri(uri)
            document.parsed?.takeIf { document.targetVersion == version }?.let { return@synchronized it }
            document.targetVersion = version
            CompletableFuture.supplyAsync({
                requireCurrent(uri, document)
                val analysis = MCFPPDocumentIndex.analyze(uri, document.text, version)
                val surfaceChanged = synchronized(documents) {
                    requireCurrent(uri, document)
                    if (document.targetVersion != version) {
                        throw ResponseErrorException(ResponseError(ResponseErrorCode.ContentModified, "Target version changed during analysis", null))
                    }
                    val previous = analyses.put(uri, analysis)
                    invalidateWorkspaceSymbolCache()
                    previous == null || semanticSurface(previous) != semanticSurface(analysis)
                }
                if (surfaceChanged) scheduleDependentDiagnosticsRefresh(uri)
                analysis
            }, editorExecutor).also { document.parsed = it }
        }

    private fun scheduleDocumentAnalysis(uri: String, document: OpenDocument, delayMillis: Long) {
        val task = analysisExecutor.schedule({
            try {
                if (documents[uri] !== document) return@schedule
                val analysis = parseDocument(uri, document).join()
                publishDiagnostics(uri, document, analysis)
            } catch (error: Throwable) {
                val cause = (error as? java.util.concurrent.CompletionException)?.cause ?: error
                if (cause !is ResponseErrorException || cause.responseError.code != ResponseErrorCode.ContentModified.value) {
                    reportBackgroundFailure("analyzing $uri", cause as? Exception ?: RuntimeException(cause))
                }
            }
        }, delayMillis, TimeUnit.MILLISECONDS)
        pendingDocumentAnalyses.put(uri, task)?.cancel(false)
    }

    private fun publishDiagnostics(uri: String, document: OpenDocument, analysis: MCFPPDocumentAnalysis) {
        if (documents[uri] !== document || analyses[uri] !== analysis) return
        val diagnostics = collectDiagnostics(analysis)
        if (documents[uri] === document && analyses[uri] === analysis) {
            server.getClientOrNull()?.publishDiagnostics(PublishDiagnosticsParams(uri, diagnostics, document.version))
        }
    }

    private fun scheduleDependentDiagnosticsRefresh(changedUri: String) {
        synchronized(dependentDiagnosticsLock) {
            pendingDependentDiagnosticsRefresh?.cancel(false)
            pendingDependentDiagnosticsRefresh = analysisExecutor.schedule({
                try {
                    invalidateSemanticTokens()
                    documents.forEach { (uri, document) ->
                        val analysis = analyses[uri]
                        if (uri != changedUri && analysis != null && analysis.text == document.text) {
                            publishDiagnostics(uri, document, analysis)
                        }
                    }
                } catch (error: Exception) {
                    reportBackgroundFailure("refreshing dependent diagnostics", error)
                }
            }, DEPENDENT_DIAGNOSTICS_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun semanticSurface(analysis: MCFPPDocumentAnalysis): String {
        return buildList {
            add("namespace=${analysis.namespaceName.orEmpty()}")
            analysis.imports
                .map { imported -> "import=${imported.namespace}:${imported.importedName}|${imported.alias.orEmpty()}|${imported.source.orEmpty()}" }
                .sorted()
                .forEach(::add)
            (analysis.topLevelSymbols + analysis.typeMembers.values.flatten())
                .map { symbol ->
                    listOf(
                        symbol.name,
                        symbol.kind.name,
                        symbol.namespaceName.orEmpty(),
                        symbol.containerName.orEmpty(),
                        symbol.receiverType.orEmpty(),
                        symbol.typeName.orEmpty(),
                        symbol.accessModifier.name,
                        symbol.superTypes.joinToString(","),
                        parameterSignatureKey(symbol)
                    ).joinToString("|")
                }
                .sorted()
                .forEach(::add)
        }.joinToString("\n")
    }

    private fun scheduleProjectIndexRefresh(delayMillis: Long) {
        synchronized(projectRefreshLock) {
            pendingProjectRefresh?.cancel(false)
            pendingProjectRefresh = analysisExecutor.schedule({
                try {
                    projectIndex.refresh(documents.mapValues { it.value.text })
                    val openDocuments = synchronized(documents) {
                        invalidateWorkspaceSymbolCache()
                        documents.toMap()
                    }
                    // Open files are omitted from the disk index. Load their declarations
                    // before sending diagnostics that may depend on imports between them.
                    val pending = openDocuments.map { (uri, document) ->
                        parseDocument(uri, document).handle { _, _ -> Unit }
                    }
                    CompletableFuture.allOf(*pending.toTypedArray()).join()
                    synchronized(documents) {
                        openDocuments.forEach { (uri, document) ->
                            if (documents[uri] === document) scheduleDocumentAnalysis(uri, document, 0)
                        }
                    }
                    invalidateSemanticTokens()
                } catch (error: Exception) {
                    reportBackgroundFailure("refreshing the project index", error)
                }
            }, delayMillis, TimeUnit.MILLISECONDS)
        }
    }

    private fun reportBackgroundFailure(operation: String, error: Exception) {
        val detail = error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
        runCatching {
            server.getClientOrNull()?.logMessage(
                MessageParams(MessageType.Error, "MCFPP language server failed while $operation: $detail")
            )
        }
    }

    private fun collectDiagnostics(document: MCFPPDocumentAnalysis): List<Diagnostic> {
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
            val symbol = resolveReferenceOccurrence(document, it)
            if (symbol?.isReadOnly == true && (symbol.kind == SymbolKind.EnumMember ||
                        symbol.kind == SymbolKind.Property && symbol.isSynthetic)) {
                diagnostics += Diagnostic(it.range, "Enum member '${symbol.name}' is read-only", DiagnosticSeverity.Error, "mcfpp")
            }
        }

        return diagnostics
    }

    private fun resolveSymbolAt(params: TextDocumentPositionParams, analysis: MCFPPDocumentAnalysis): MCFPPSymbol? {
        templateFieldAt(analysis, params.position)?.let { return it }
        analysis.referenceOccurrencesByName[analysis.wordAt(params.position)].orEmpty()
            .firstOrNull { it.isNamedArgument && containsPosition(it.range, params.position) }
            ?.let { return resolveNamedArgument(analysis, it) }
        val receiver = analysis.receiverBefore(params.position)
        val word = analysis.wordAt(params.position) ?: return null
        resolveQualifiedGlobalSymbol(analysis, word, params.position)?.let { return it }
        if (receiver != null) {
            val typeName = resolveReceiverTypeAt(analysis, receiver, params.position)
            if (typeName != null) {
                return receiverMembers(analysis, receiver, typeName, params.position).firstOrNull { it.name == word }
            }
        }
        resolveLocalSymbol(analysis, word, params.position)?.let {
            return it
        }
        return resolveGlobalSymbol(analysis, word, params.position)
    }

    private fun buildDocumentSymbols(analysis: MCFPPDocumentAnalysis): List<DocumentSymbol> {
        return analysis.topLevelSymbols
            .sortedWith(compareBy({ it.range.start.line }, { it.range.start.character }))
            .map { symbol ->
                val children = if (symbol.kind in setOf(SymbolKind.Class, SymbolKind.Enum)) {
                    analysis.typeMembers[symbol.name]
                        .orEmpty()
                        .sortedWith(compareBy({ it.range.start.line }, { it.range.start.character }))
                        .map(::toDocumentSymbol)
                } else {
                    emptyList()
                }
                toDocumentSymbol(symbol, children)
            }
    }

    private fun toDocumentSymbol(symbol: MCFPPSymbol, children: List<DocumentSymbol> = emptyList()): DocumentSymbol {
        val documentSymbol = DocumentSymbol()
        documentSymbol.name = symbol.name
        documentSymbol.kind = symbol.kind
        documentSymbol.range = symbol.fullRange
        documentSymbol.selectionRange = symbol.selectionRange
        documentSymbol.detail = symbol.detail ?: symbol.typeName ?: symbol.superTypes.takeIf { it.isNotEmpty() }?.joinToString(", ")
        if (children.isNotEmpty()) {
            documentSymbol.children = children
        }
        return documentSymbol
    }

    private fun resolveReceiverType(analysis: MCFPPDocumentAnalysis, receiver: String): String? {
        analysis.variables[receiver]?.let {
            return resolveImportedTypeName(analysis, it)
        }
        resolveGlobalSymbol(analysis, receiver, Position(0, 0))
            ?.takeIf { it.kind in TYPE_SYMBOL_KINDS }
            ?.let { return it.name }
        return resolveImportedTypeName(analysis, receiver)
    }

    private fun resolveMemberCompletions(
        analysis: MCFPPDocumentAnalysis,
        receiver: String,
        position: Position
    ): List<CompletionItem> {
        val receiverType = resolveReceiverTypeAt(analysis, receiver, position) ?: return emptyList()
        val builtins = if (MCFPPDocumentIndex.isReferenceType(receiverType)) listOf(
            CompletionItem("tryRead").apply {
                kind = CompletionItemKind.Method
                detail = "tryRead { value -> ... }: bool; synchronous value snapshot"
            },
            CompletionItem("set").apply {
                kind = CompletionItemKind.Method
                detail = "set(value): void; writes the referenced target"
            }
        ) else emptyList()
        return builtins + receiverMembers(analysis, receiver, receiverType, position)
            .filter { it.kind != SymbolKind.Constructor }
            .map { symbolCompletion(it) }
    }

    private fun enumDeclaration(analysis: MCFPPDocumentAnalysis, type: String): MCFPPSymbol? {
        val simple = type.substringAfterLast(':')
        val namespace = if (':' in type) resolveNamespaceQualifier(analysis, type.substringBeforeLast(':')) else null
        val imported = analysis.imports.firstOrNull { namespace == null && it.importedName != "*" && (it.alias ?: it.importedName) == simple }
        val targetNamespace = namespace ?: imported?.namespace
        val currentNamespace = analysis.namespaceName ?: projectIndex.namespaceForUri(analysis.uri)
        return (if (namespace != null) workspaceSymbols() else visibleGlobalSymbolsForDocument(analysis)).asSequence()
            .filter { it.kind == SymbolKind.Enum && it.name == (imported?.importedName ?: simple) }
            .filter { targetNamespace == null || it.namespaceName == targetNamespace }
            .maxWithOrNull(compareBy({ it.uri == analysis.uri }, { it.namespaceName == currentNamespace }, { !it.isStdlib }))
    }

    private fun enumMembers(owner: MCFPPSymbol) = workspaceSymbols().filter {
        it.kind == SymbolKind.EnumMember && it.containerName == owner.name && it.uri == owner.uri && it.namespaceName == owner.namespaceName
    }

    private fun contextualEnumMembers(analysis: MCFPPDocumentAnalysis, position: Position): List<MCFPPSymbol> {
        val offset = MCFPPTextChanges.positionToOffset(analysis.text, position)
        var start = offset
        while (start > 0 && Character.isUnicodeIdentifierPart(analysis.text[start - 1])) start--
        val expected = templateContext(analysis).first.expectedValueTypes(start).distinct().singleOrNull() ?: return emptyList()
        return enumDeclaration(analysis, expected)?.let(::enumMembers).orEmpty()
    }

    private fun receiverMembers(analysis: MCFPPDocumentAnalysis, receiver: String, type: String, position: Position): List<MCFPPSymbol> {
        val owner = enumDeclaration(analysis, type) ?: return accessibleMemberSymbolsForType(analysis, type, position)
        val static = receiver.substringAfterLast(':') == owner.name || analysis.imports.any {
            it.alias == receiver && it.importedName == owner.name && it.namespace == owner.namespaceName
        }
        if (static && resolveLocalSymbol(analysis, receiver, position)?.kind in setOf(null, SymbolKind.Enum))
            return enumMembers(owner)
        return top.mcfpp.language.EnumProperties.TYPES.map { (name, result) ->
            owner.copy(name = name, kind = SymbolKind.Property, typeName = result, receiverType = type,
                containerName = owner.name, topLevel = false, isReadOnly = true, isSynthetic = true,
                documentation = "Read-only enum property.")
        }
    }

    private fun memberSymbolsForType(
        analysis: MCFPPDocumentAnalysis,
        receiverType: String
    ): List<MCFPPSymbol> {
        MCFPPEntityTemplates.templateOf(receiverType)?.let { template ->
            return entityReferenceMembers(analysis, template)
        }
        val normalizedReceiverType = normalizeTypeName(receiverType) ?: return emptyList()
        val cacheKey = receiverType
        val memberSymbolsCache = symbolCaches.forDocument(analysis).members
        memberSymbolsCache[cacheKey]?.let { return it }
        val intrinsic = MCFPPValueMembers.members(receiverType)
        templateSchema(analysis).members(receiverType)?.let { declared ->
            val members = declared + intrinsic
            memberSymbolsCache[cacheKey] = members
            return members
        }
        val visibleSymbols = visibleGlobalSymbolsForDocument(analysis)
        val membersByOwner = visibleSymbols
            .asSequence()
            .filter { it.containerName != null || it.receiverType != null }
            .groupBy { normalizeTypeName(it.containerName ?: it.receiverType).orEmpty() }
        val typesByName = visibleSymbols
            .asSequence()
            .filter { it.topLevel && it.kind in TYPE_SYMBOL_KINDS }
            .groupBy { normalizeTypeName(it.name).orEmpty() }
        val pending = ArrayDeque<String>()
        val visitedTypes = mutableSetOf<String>()
        val members = linkedMapOf<String, MCFPPSymbol>()
        pending.addLast(normalizedReceiverType)
        while (pending.isNotEmpty()) {
            val currentType = pending.removeFirst()
            if (!visitedTypes.add(currentType)) {
                continue
            }
            membersByOwner[currentType].orEmpty()
                .filterNot { it.isEntityMember }
                .forEach { symbol ->
                    val key = listOf(symbol.name, symbol.kind.name, parameterSignatureKey(symbol)).joinToString("|")
                    members.putIfAbsent(key, symbol)
                }
            // Intrinsic summon declarations are reconstructed by the compiler when a
            // library is imported, so they do not appear in its exported members.
            if (membersByOwner[currentType].orEmpty().none { it.name == "summon" }) {
                typesByName[currentType].orEmpty().firstOrNull { it.isEntityTemplate }?.let { template ->
                    for (parameters in listOf(emptyList(), listOf(MCFPPFunctionParameter("d", "nbt")))) {
                        val summon = template.copy(name = "summon", kind = SymbolKind.Method,
                            containerName = currentType, receiverType = currentType, topLevel = false,
                            detail = "EntityRef<$currentType>", typeName = "EntityRef<$currentType>",
                            parameters = parameters, isEntityTemplate = false, isEntityMember = false, isSynthetic = true)
                        val key = listOf(summon.name, summon.kind.name, parameterSignatureKey(summon)).joinToString("|")
                        members.putIfAbsent(key, summon)
                    }
                }
            }
            typesByName[currentType].orEmpty().forEach { typeSymbol ->
                typeSymbol.superTypes.forEach { superType ->
                    normalizeTypeName(superType)
                        ?.takeIf { it !in visitedTypes }
                        ?.let(pending::addLast)
                }
            }
            top.mcfpp.language.EntityBuiltins.parent(currentType)?.let(pending::addLast)
        }
        return (members.values + intrinsic).distinctBy { it.name to parameterSignatureKey(it) }.also { memberSymbolsCache[cacheKey] = it }
    }

    /**
     * Members of `EntityRef<T>`: the entity members declared on the template ancestry
     * plus the compiler-owned members of the built-in entity kind that ancestry reaches.
     */
    private fun entityReferenceMembers(analysis: MCFPPDocumentAnalysis, template: String): List<MCFPPSymbol> {
        val cacheKey = "EntityRef<$template>"
        val memberSymbolsCache = symbolCaches.forDocument(analysis).members
        memberSymbolsCache[cacheKey]?.let { return it }
        val symbols = visibleGlobalSymbolsForDocument(analysis)
        val owners = MCFPPEntityTemplates.owners(symbols, template)
        val members = linkedMapOf<String, MCFPPSymbol>()
        owners.forEach { owner ->
            symbols.asSequence()
                .filter { it.isEntityMember && normalizeTypeName(it.containerName) == owner }
                .forEach { symbol ->
                    val key = listOf(symbol.name, symbol.kind.name, parameterSignatureKey(symbol)).joinToString("|")
                    members.putIfAbsent(key, symbol)
                }
            if (owner == "entity" || top.mcfpp.language.EntityBuiltins.TYPES.contains(owner)) symbols.asSequence()
                .filter { it.containerName != null && normalizeTypeName(it.containerName) == owner && !it.isEntityMember }
                .forEach { symbol ->
                    val key = listOf(symbol.name, symbol.kind.name, parameterSignatureKey(symbol)).joinToString("|")
                    members.putIfAbsent(key, symbol)
                }
        }
        return members.values.toList().also { memberSymbolsCache[cacheKey] = it }
    }

    private fun accessibleMemberSymbolsForType(
        analysis: MCFPPDocumentAnalysis,
        receiverType: String,
        position: Position
    ): List<MCFPPSymbol> {
        val currentType = analysis.innermostTypeScope(position)?.name
        return memberSymbolsForType(analysis, receiverType).filter { symbol ->
            isMemberAccessible(analysis, symbol, position, currentType)
        }
    }

    private fun isMemberAccessible(
        analysis: MCFPPDocumentAnalysis,
        symbol: MCFPPSymbol,
        position: Position,
        currentType: String? = analysis.innermostTypeScope(position)?.name
    ): Boolean {
        val owner = symbol.containerName ?: symbol.receiverType ?: return true
        return when (symbol.accessModifier) {
            MCFPPAccessModifier.PUBLIC -> true
            MCFPPAccessModifier.PRIVATE -> currentType != null && normalizeTypeName(currentType) == normalizeTypeName(owner)
            MCFPPAccessModifier.PROTECTED -> currentType != null && isTypeOrSubtype(analysis, currentType, owner)
        }
    }

    private fun isTypeOrSubtype(
        analysis: MCFPPDocumentAnalysis,
        actualType: String,
        expectedType: String
    ): Boolean {
        val expected = normalizeTypeName(expectedType) ?: return false
        val actual = normalizeTypeName(actualType) ?: return false
        if (actual == expected) {
            return true
        }
        return inheritanceDistance(analysis, actual.lowercase(), expected.lowercase()) != null
    }

    private fun resolveCallableSymbols(
        analysis: MCFPPDocumentAnalysis,
        context: MCFPPCallContext,
        position: Position
    ): List<MCFPPSymbol> {
        val callableKinds = setOf(SymbolKind.Function, SymbolKind.Method, SymbolKind.Constructor)
        if (context.qualifierSeparator == null) {
            val value = resolveLocalSymbol(analysis, context.name, position)
                ?: visibleGlobalSymbolsForDocument(analysis).firstOrNull {
                    it.name == context.name && it.kind in setOf(SymbolKind.Variable, SymbolKind.Field)
                }
            if (value != null && value.kind in setOf(SymbolKind.Variable, SymbolKind.Field)) {
                return callableValues(analysis, value, emptySet())
            }
        }
        val symbols = when (context.qualifierSeparator) {
            '.' -> {
                val receiverType = context.qualifier?.let { resolveReceiverTypeAt(analysis, it, position) }
                    ?: return emptyList()
                accessibleMemberSymbolsForType(analysis, receiverType, position).filter { symbol ->
                    symbol.kind in callableKinds &&
                        symbol.name == context.name
                }
            }
            ':' -> {
                val namespace = context.qualifier?.let { resolveNamespaceQualifier(analysis, it) } ?: return emptyList()
                workspaceSymbols().filter { symbol ->
                    symbol.kind in callableKinds && symbol.name == context.name && symbol.namespaceName == namespace
                }
            }
            else -> {
                val aliasedImport = analysis.imports.firstOrNull { imported -> imported.alias == context.name }
                if (aliasedImport != null) {
                    workspaceSymbols().filter { symbol ->
                        symbol.kind in callableKinds &&
                            symbol.name == aliasedImport.importedName &&
                            symbol.namespaceName == aliasedImport.namespace
                    }
                } else {
                    val currentType = analysis.innermostTypeScope(position)?.name
                    visibleGlobalSymbolsForDocument(analysis).filter { symbol ->
                        symbol.kind in callableKinds &&
                            symbol.name == context.name &&
                            (
                                symbol.topLevel ||
                                    symbol.containerName == currentType ||
                                    symbol.receiverType == currentType ||
                                    (symbol.kind == SymbolKind.Constructor && symbol.containerName == context.name)
                                )
                    }
                }
            }
        }
        return symbols
            .filter { isMemberAccessible(analysis, it, position) }
            .filter { symbol ->
                context.argumentGroup != MCFPPArgumentGroup.READ_ONLY || symbol.parameters.any { it.isReadOnly }
            }
            .distinctBy { symbolIdentityKey(it) }
    }

    private fun callableValues(analysis: MCFPPDocumentAnalysis, value: MCFPPSymbol, visited: Set<String>): List<MCFPPSymbol> {
        val key = symbolIdentityKey(value)
        if (key in visited) return emptyList()
        value.functionValue?.let { signature ->
            val declared = value.initializer?.let { reference -> visibleGlobalSymbolsForDocument(analysis).firstOrNull { it.name == reference } }
            val contexts = declared?.contextParameters ?: signature.contextTypes.mapIndexed { index, type -> MCFPPFunctionParameter("context${index + 1}", type) }
            return listOf(value.copy(parameters = signature.parameters, contextParameters = contexts, typeName = signature.returnType, detail = signature.returnType))
        }
        val reference = value.initializer?.takeIf { it.matches(Regex("(?:[A-Za-z_][A-Za-z0-9_.]*:)?[A-Za-z_][A-Za-z0-9_]*")) }
            ?: return emptyList()
        val name = reference.substringAfterLast(':')
        if (':' !in reference) {
            resolveLocalSymbol(analysis, name, value.range.start)?.takeIf {
                it.kind in setOf(SymbolKind.Variable, SymbolKind.Field)
            }?.let { alias ->
                return callableValues(analysis, alias, visited + key).map {
                    value.copy(parameters = it.parameters, typeName = it.typeName, detail = it.detail)
                }
            }
        }
        val namespace = reference.substringBefore(':', "").takeIf { it.isNotEmpty() }
            ?.let { resolveNamespaceQualifier(analysis, it) }
        val imported = analysis.imports.firstOrNull { it.alias == name }
        return visibleGlobalSymbolsForDocument(analysis).filter {
            it.kind in CALLABLE_SYMBOL_KINDS &&
                it.name == (imported?.importedName ?: name) &&
                (namespace == null || it.namespaceName == namespace) &&
                (imported == null || it.namespaceName == imported.namespace)
        }.map { value.copy(parameters = it.parameters, typeName = it.typeName, detail = it.detail) }
    }

    private fun resolveReceiverTypeAt(analysis: MCFPPDocumentAnalysis, receiver: String, position: Position): String? {
        val expression = receiver.trim().removeSuffix("!!").removeSuffix("?").removeSurrounding("(", ")").trim()
        MCFPPDocumentIndex.referenceFactoryType(expression)?.let { return it }
        if (expression.startsWith('{') && expression.endsWith('}')) return "nbt"
        stringLiteralType(expression)?.let { return it }
        if (expression.startsWith('@') && lastTopLevelMemberSeparator(expression) < 0) return "entity"
        if (IDENTIFIER_REGEX.matches(expression)) {
            val local = resolveLocalSymbol(analysis, expression, position)
            local?.typeName?.let { return it }
            local?.initializer?.takeIf { it.trim() != expression }?.let {
                inferArgumentType(analysis, it, local.range.start)?.let { type -> return type }
            }
        }
        if (expression == "this") {
            val owner = analysis.innermostTypeScope(position)?.name ?: return null
            return if (isEntityMemberContext(analysis, owner, position)) MCFPPEntityTemplates.referenceType(owner) else owner
        }
        if (expression == "super") {
            val owner = analysis.innermostTypeScope(position)?.name ?: return null
            val parent = visibleGlobalSymbolsForDocument(analysis).firstOrNull {
                it.name == owner && it.kind in setOf(SymbolKind.Class)
            }?.superTypes?.firstOrNull()?.let(::normalizeTypeName)
            return if (parent != null && isEntityMemberContext(analysis, owner, position))
                MCFPPEntityTemplates.referenceType(parent) else parent
        }
        if (expression.endsWith(')')) {
            inferExpressionCallReturnType(analysis, expression, position)
                ?.let { return resolveImportedTypeName(analysis, it) }
        }
        val memberSeparator = lastTopLevelMemberSeparator(expression)
        if (memberSeparator > 0) {
            val ownerExpression = expression.substring(0, memberSeparator).removeSuffix("?")
            val memberName = expression.substring(memberSeparator + 1).trim()
            if (IDENTIFIER_REGEX.matches(memberName)) {
                val ownerType = resolveReceiverTypeAt(analysis, ownerExpression, position)
                if (ownerType != null) {
                    return receiverMembers(analysis, ownerExpression, ownerType, position)
                        .firstOrNull { it.name == memberName }
                        ?.typeName
                        ?.let { resolveImportedTypeName(analysis, it) }
                }
            }
        }
        if (!IDENTIFIER_REGEX.matches(expression)) inferArgumentType(analysis, expression, position)?.let { return it }
        return resolveReceiverType(analysis, expression)
    }

    /** `this` inside `entity func name()` is the template's `EntityRef`, not the template value. */
    private fun isEntityMemberContext(analysis: MCFPPDocumentAnalysis, type: String, position: Position): Boolean =
        analysis.symbols.any { symbol ->
            symbol.isEntityMember && symbol.kind == SymbolKind.Method &&
                normalizeTypeName(symbol.containerName) == normalizeTypeName(type) &&
                containsPosition(symbol.fullRange, position)
        }

    private fun inferExpressionCallReturnType(
        analysis: MCFPPDocumentAnalysis,
        expression: String,
        position: Position
    ): String? {
        if (CALL_EXPRESSION.matches(expression)) {
            inferNestedCallReturnType(analysis, expression, position)?.let { return it }
        }
        val close = expression.indexOfLast { !it.isWhitespace() }
        if (close < 0 || expression[close] != ')') return null
        var depth = 1
        var open = close - 1
        while (open >= 0 && depth > 0) {
            when (expression[open]) {
                ')' -> depth++
                '(' -> depth--
            }
            open--
        }
        open++
        if (depth != 0) return null
        var nameEnd = open
        var readOnlyArguments = ""
        var genericClose = open - 1
        while (genericClose >= 0 && expression[genericClose].isWhitespace()) genericClose--
        if (genericClose >= 0 && expression[genericClose] == '>') {
            var angleDepth = 1
            var genericOpen = genericClose - 1
            while (genericOpen >= 0 && angleDepth > 0) {
                when (expression[genericOpen]) {
                    '>' -> angleDepth++
                    '<' -> angleDepth--
                }
                genericOpen--
            }
            genericOpen++
            if (angleDepth != 0) return null
            readOnlyArguments = expression.substring(genericOpen, genericClose + 1)
            nameEnd = genericOpen
        }
        var nameStart = nameEnd - 1
        while (nameStart >= 0 && (expression[nameStart].isLetterOrDigit() || expression[nameStart] == '_')) nameStart--
        val name = expression.substring(nameStart + 1, nameEnd).takeIf { IDENTIFIER_REGEX.matches(it) } ?: return null
        var separator = nameStart
        while (separator >= 0 && expression[separator].isWhitespace()) separator--
        if (separator < 0 || expression[separator] != '.') return null
        val ownerExpression = expression.substring(0, separator).trim()
        val ownerType = resolveReceiverTypeAt(analysis, ownerExpression, position) ?: return null
        val syntheticCall = name + readOnlyArguments + expression.substring(open, close + 1)
        val callPosition = Position(0, syntheticCall.lastIndexOf(')'))
        val context = MCFPPCallContextFinder.findCandidates(syntheticCall, callPosition).firstOrNull() ?: return null
        val symbol = accessibleMemberSymbolsForType(analysis, ownerType, position)
            .asSequence()
            .filter { it.kind in CALLABLE_SYMBOL_KINDS && it.name == name }
            .maxByOrNull { signatureMatchScore(it, context, analysis, position) }
            ?: return null
        val returnType = symbol.typeName ?: symbol.detail ?: return null
        return substituteTypeParameters(returnType, typeParameterSubstitutions(symbol, context.readOnlyArguments))
    }

    private fun lastTopLevelMemberSeparator(expression: String): Int {
        var parentheses = 0
        var brackets = 0
        var angles = 0
        for (index in expression.indices.reversed()) {
            when (expression[index]) {
                ')' -> parentheses++
                '(' -> if (parentheses > 0) parentheses--
                ']' -> brackets++
                '[' -> if (brackets > 0) brackets--
                '>' -> angles++
                '<' -> if (angles > 0) angles--
                '.' -> if (parentheses == 0 && brackets == 0 && angles == 0) return index
            }
        }
        return -1
    }

    private fun signatureMatchScore(
        symbol: MCFPPSymbol,
        context: MCFPPCallContext,
        analysis: MCFPPDocumentAnalysis,
        position: Position
    ): Int {
        val parameters = symbol.parameters.filter {
            it.isReadOnly == (context.argumentGroup == MCFPPArgumentGroup.READ_ONLY)
        }
        val nonBlankArguments = context.arguments.indexOfLast { it.isNotBlank() }.let { last ->
            if (last < 0) 0 else last + 1
        }
        var score = 0
        score += when {
            parameters.isEmpty() && nonBlankArguments == 0 -> 24
            context.activeParameter < parameters.size -> 30
            else -> -40 * (context.activeParameter - parameters.size + 1)
        }
        val required = parameters.count { !it.hasDefault }
        score += when {
            nonBlankArguments > parameters.size -> -30 * (nonBlankArguments - parameters.size)
            nonBlankArguments >= required -> 12
            else -> -4 * (required - nonBlankArguments)
        }
        context.arguments.forEachIndexed { index, argument ->
            val parameter = parameters.getOrNull(index) ?: return@forEachIndexed
            val argumentType = inferArgumentType(analysis, argument, position) ?: return@forEachIndexed
            val parameterType = substituteTypeParameters(
                parameter.typeName,
                typeParameterSubstitutions(symbol, context.readOnlyArguments)
            )
            if (argument.trimStart().startsWith('{') && templateSchema(analysis).fields(parameterType) != null) {
                score += 20
                return@forEachIndexed
            }
            score += typeCompatibilityScore(argumentType, parameterType, analysis)
        }
        return score
    }

    private val argumentTypeInferences = ThreadLocal.withInitial { mutableSetOf<String>() }

    private fun inferArgumentType(analysis: MCFPPDocumentAnalysis, rawArgument: String, position: Position): String? {
        val key = "${analysis.uri}|${position.line}:${position.character}|$rawArgument"
        val pending = argumentTypeInferences.get()
        if (!pending.add(key)) return null
        return try {
            inferArgumentTypeUnchecked(analysis, rawArgument, position)
        } finally {
            pending.remove(key)
            if (pending.isEmpty()) argumentTypeInferences.remove()
        }
    }

    private fun inferArgumentTypeUnchecked(analysis: MCFPPDocumentAnalysis, rawArgument: String, position: Position): String? {
        val argument = rawArgument.trim()
        if (argument.isEmpty()) return null
        MCFPPDocumentIndex.referenceFactoryType(argument)?.let { return it }
        stringLiteralType(argument)?.let { return it }
        if (argument == "true" || argument == "false") return "bool"
        if (argument.startsWith('@') && lastTopLevelMemberSeparator(argument) < 0) return "entity"
        if (argument.startsWith('{') && argument.endsWith('}')) return "nbt"
        if (SIGNED_INTEGER.matches(argument)) {
            return when (argument.last().lowercaseChar()) {
                'b' -> "byte"
                's' -> "short"
                'l' -> "long"
                else -> "int"
            }
        }
        if (SIGNED_DECIMAL.matches(argument)) {
            return if (argument.last().lowercaseChar() == 'd') "double" else "float"
        }
        if (CALL_EXPRESSION.matches(argument)) {
            inferNestedCallReturnType(analysis, argument, position)?.let { return it }
        }
        if (IDENTIFIER_REGEX.matches(argument)) {
            val symbol = resolveLocalSymbol(analysis, argument, position)
                ?: resolveGlobalSymbol(analysis, argument, position)
            symbol?.typeName?.let { return it }
            symbol?.initializer?.let { initializer ->
                val owner = allAnalyses()[symbol.uri] ?: analysis
                return inferArgumentType(owner, initializer, symbol.selectionRange.start)
            }
        }
        return typeChecker(analysis).expressionType(argument, MCFPPTextChanges.positionToOffset(analysis.analysisText, position))
    }

    private fun inferNestedCallReturnType(
        analysis: MCFPPDocumentAnalysis,
        callText: String,
        position: Position
    ): String? {
        val closingOffset = callText.lastIndexOf(')').takeIf { it >= 0 } ?: callText.length
        val callPosition = offsetToPosition(callText, closingOffset)
        val context = MCFPPCallContextFinder.findCandidates(callText, callPosition).firstOrNull() ?: return null
        val symbol = resolveCallableSymbols(analysis, context, position)
            .maxByOrNull { signatureMatchScore(it, context, analysis, position) }
            ?: return null
        val returnType = symbol.typeName ?: symbol.detail ?: return null
        return substituteTypeParameters(returnType, typeParameterSubstitutions(symbol, context.readOnlyArguments))
    }

    private fun offsetToPosition(text: String, offset: Int): Position {
        val boundedOffset = offset.coerceIn(0, text.length)
        var line = 0
        var character = 0
        for (index in 0 until boundedOffset) {
            if (text[index] == '\n') {
                line += 1
                character = 0
            } else if (text[index] != '\r') {
                character += 1
            }
        }
        return Position(line, character)
    }

    private fun declarationType(analysis: MCFPPDocumentAnalysis, symbol: MCFPPSymbol): String? =
        typeChecker(analysis).declarationType(symbol)?.replace(")->", ") -> ")

    private fun inferredTypeHint(symbol: MCFPPSymbol, checker: MCFPPTypeChecker): InlayHint? {
        if (symbol.initializer == null) return null
        val inferredType = checker.declarationType(symbol)?.replace(")->", ") -> ") ?: return null
        return InlayHint(symbol.selectionRange.end, Either.forLeft(": $inferredType")).apply {
            kind = InlayHintKind.Type
            paddingLeft = false
            paddingRight = true
            setTooltip("Inferred MCFPP type")
        }
    }

    private fun parameterInlayHints(
        analysis: MCFPPDocumentAnalysis,
        occurrence: MCFPPReferenceOccurrence
    ): List<InlayHint> {
        val target = resolveCallTarget(analysis, occurrence) ?: return emptyList()
        val readOnlyParameters = target.parameters.filter { it.isReadOnly }
        val normalParameters = target.parameters.filterNot { it.isReadOnly }
        return buildList {
            addParameterInlayHints(
                this,
                readOnlyParameters,
                occurrence.readOnlyArguments,
                occurrence.readOnlyArgumentRanges
            )
            addParameterInlayHints(
                this,
                normalParameters,
                occurrence.normalArguments,
                occurrence.normalArgumentRanges
            )
        }
    }

    private fun addParameterInlayHints(
        destination: MutableList<InlayHint>,
        parameters: List<MCFPPFunctionParameter>,
        arguments: List<String>,
        ranges: List<Range>
    ) {
        ranges.forEachIndexed { index, range ->
            val parameter = parameters.getOrNull(index) ?: return@forEachIndexed
            val parameterName = parameter.name?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            if (arguments.getOrNull(index)?.trim() == parameterName) {
                return@forEachIndexed
            }
            destination += InlayHint(range.start, Either.forLeft("$parameterName:")).apply {
                kind = InlayHintKind.Parameter
                paddingLeft = false
                paddingRight = true
                setTooltip(parameter.typeName)
            }
        }
    }

    private fun typeCompatibilityScore(
        argumentType: String,
        parameterType: String,
        analysis: MCFPPDocumentAnalysis
    ): Int {
        val argument = normalizeTypeName(expandTypeAlias(analysis, argumentType))?.lowercase() ?: return 0
        val parameter = normalizeTypeName(expandTypeAlias(analysis, parameterType))?.lowercase() ?: return 0
        return when {
            argument == parameter -> 16
            parameter == "any" -> 4
            argument in NUMERIC_TYPES && parameter in NUMERIC_TYPES ->
                if (typeChecker(analysis).isAssignable(parameterType, argumentType)) 7 else -8
            else -> inheritanceDistance(analysis, argument, parameter)
                ?.let { distance -> 14 - distance.coerceAtMost(8) }
                ?: -8
        }
    }

    private fun inheritanceDistance(
        analysis: MCFPPDocumentAnalysis,
        actualType: String,
        expectedType: String
    ): Int? {
        if (actualType == expectedType) {
            return 0
        }
        val typesByName = visibleGlobalSymbolsForDocument(analysis)
            .asSequence()
            .filter { it.topLevel && it.kind in TYPE_SYMBOL_KINDS }
            .groupBy { it.name.lowercase() }
        val pending = ArrayDeque<Pair<String, Int>>()
        val visited = mutableSetOf<String>()
        pending.addLast(actualType to 0)
        while (pending.isNotEmpty()) {
            val (current, distance) = pending.removeFirst()
            if (!visited.add(current)) {
                continue
            }
            for (symbol in typesByName[current].orEmpty()) {
                for (superType in symbol.superTypes) {
                    val normalizedSuperType = normalizeTypeName(superType)?.lowercase() ?: continue
                    if (normalizedSuperType == expectedType) {
                        return distance + 1
                    }
                    if (normalizedSuperType !in visited) {
                        pending.addLast(normalizedSuperType to distance + 1)
                    }
                }
            }
        }
        return null
    }

    private fun expandTypeAlias(
        analysis: MCFPPDocumentAnalysis,
        typeName: String,
        visited: Set<String> = emptySet()
    ): String {
        val normalized = normalizeTypeName(typeName) ?: return typeName
        if (normalized in visited) {
            return typeName
        }
        val aliasTarget = visibleGlobalSymbolsForDocument(analysis)
            .asSequence()
            .filter { it.topLevel && it.kind == SymbolKind.TypeParameter }
            .firstOrNull { normalizeTypeName(it.name) == normalized }
            ?.typeName
            ?.takeIf { normalizeTypeName(it) != normalized }
            ?: return typeName
        return expandTypeAlias(analysis, aliasTarget, visited + normalized)
    }

    private fun buildSignatureInformation(symbol: MCFPPSymbol, context: MCFPPCallContext): SignatureInformation {
        val parameters = symbol.parameters + symbol.contextParameters
        val groupParameters = parameters.filter {
            it.isReadOnly == (context.argumentGroup == MCFPPArgumentGroup.READ_ONLY)
        }
        val groupOffset = if (context.argumentGroup == MCFPPArgumentGroup.NORMAL) {
            parameters.count { it.isReadOnly }
        } else {
            0
        }
        val named = context.arguments.getOrNull(context.activeParameter)?.takeIf { '=' in it }?.substringBefore('=')?.trim()
        val activeParameter = parameters.indexOfFirst { named != null && it.name == named }.takeIf { it >= 0 } ?: groupParameters
            .takeIf { it.isNotEmpty() }
            ?.let { groupOffset + context.activeParameter.coerceAtMost(it.lastIndex) }
        return SignatureInformation(buildSignatureLabel(symbol, context.name)).apply {
            symbol.documentation?.takeIf { it.isNotBlank() }?.let {
                documentation = Either.forRight(MarkupContent(MarkupKind.MARKDOWN, it))
            }
            this.parameters = parameters.map { parameter ->
                val description = parameter.name?.let { parameterDocumentation(symbol.documentation, it) }
                if (description == null) ParameterInformation(parameter.label())
                else ParameterInformation(parameter.label(), MarkupContent(MarkupKind.MARKDOWN, description))
            }
            this.activeParameter = activeParameter
        }
    }

    private fun buildSignatureLabel(symbol: MCFPPSymbol, displayName: String = symbol.name): String = buildString {
        if (symbol.contextParameters.isNotEmpty()) append(symbol.contextParameters.joinToString(", ", "context(", ") ") { it.label() })
        append(displayName)
        val readOnly = symbol.parameters.filter { it.isReadOnly }
        if (readOnly.isNotEmpty()) {
            append('<')
            append(readOnly.joinToString(", ") { it.label() })
            append('>')
        }
        append('(')
        append(symbol.parameters.filterNot { it.isReadOnly }.joinToString(", ") { it.label() })
        append(')')
        symbol.typeName
            ?.takeIf { it.isNotBlank() && it != "void" && symbol.kind != SymbolKind.Constructor }
            ?.let { append(": ").append(it) }
    }

    private fun parameterDocumentation(documentation: String?, parameterName: String): String? {
        if (documentation.isNullOrBlank()) return null
        val marker = "@param $parameterName"
        return documentation.lineSequence()
            .map(String::trim)
            .firstOrNull { it == marker || it.startsWith("$marker ") }
            ?.removePrefix(marker)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun parameterSignatureKey(symbol: MCFPPSymbol): String = symbol.parameters.joinToString("|") { parameter ->
        listOf(
            if (parameter.isReadOnly) "readonly" else "normal",
            parameter.typeName
        ).joinToString(":")
    }

    private fun symbolIdentityKey(symbol: MCFPPSymbol): String = listOf(
        symbol.uri,
        symbol.name,
        symbol.kind.name,
        symbol.range.start.line,
        symbol.range.start.character,
        symbol.namespaceName.orEmpty(),
        symbol.containerName.orEmpty(),
        symbol.receiverType.orEmpty(),
        parameterSignatureKey(symbol)
    ).joinToString("|")

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
        return allAnalyses()[item.uri]?.symbols?.firstOrNull { symbol ->
            symbol.name == item.name &&
                symbol.kind == item.kind &&
                symbol.selectionRange == item.selectionRange &&
                symbol.kind in TYPE_SYMBOL_KINDS
        }
    }

    private fun resolveTypeSymbol(typeName: String, owner: MCFPPSymbol): MCFPPSymbol? {
        val simpleName = normalizeTypeName(typeName) ?: return null
        val explicitNamespace = typeName
            .substringBefore('<')
            .substringBefore('?')
            .substringBeforeLast(':', missingDelimiterValue = "")
            .trim()
            .takeIf { it.isNotEmpty() }
        val candidates = workspaceSymbols().filter { candidate ->
            candidate.topLevel && candidate.kind in TYPE_SYMBOL_KINDS && candidate.name == simpleName
        }
        if (explicitNamespace != null) {
            return candidates.firstOrNull { it.namespaceName == explicitNamespace }
        }
        val ownerAnalysis = allAnalyses()[owner.uri]
        if (ownerAnalysis != null) {
            visibleGlobalSymbolsForDocument(ownerAnalysis)
                .firstOrNull { candidate ->
                    candidate.topLevel && candidate.kind in TYPE_SYMBOL_KINDS && candidate.name == simpleName
                }
                ?.let { return it }
        }
        return candidates.firstOrNull { it.namespaceName == owner.namespaceName }
            ?: candidates.singleOrNull()
    }

    private fun findCallHierarchySymbol(item: CallHierarchyItem): MCFPPSymbol? {
        return allAnalyses()[item.uri]?.symbols?.firstOrNull { symbol ->
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

    private fun resolveCallTarget(
        analysis: MCFPPDocumentAnalysis,
        occurrence: MCFPPReferenceOccurrence
    ): MCFPPSymbol? {
        val receiver = if (occurrence.namespaceQualifier == null) {
            analysis.receiverBefore(occurrence.range.end)
        } else {
            null
        }
        val normalContext = MCFPPCallContext(
            name = occurrence.name,
            qualifier = occurrence.namespaceQualifier ?: receiver,
            qualifierSeparator = when {
                occurrence.namespaceQualifier != null -> ':'
                receiver != null -> '.'
                else -> null
            },
            argumentGroup = MCFPPArgumentGroup.NORMAL,
            activeParameter = (occurrence.normalArguments.size - 1).coerceAtLeast(0),
            arguments = occurrence.normalArguments,
            readOnlyArguments = occurrence.readOnlyArguments
        )
        val candidates = resolveCallableSymbols(analysis, normalContext, occurrence.range.start)
        return candidates.maxByOrNull { candidate ->
            var score = signatureMatchScore(candidate, normalContext, analysis, occurrence.range.start)
            if (occurrence.readOnlyArguments.isNotEmpty()) {
                score += signatureMatchScore(
                    candidate,
                    normalContext.copy(
                        argumentGroup = MCFPPArgumentGroup.READ_ONLY,
                        activeParameter = occurrence.readOnlyArguments.lastIndex,
                        arguments = occurrence.readOnlyArguments,
                        readOnlyArguments = occurrence.readOnlyArguments
                    ),
                    analysis,
                    occurrence.range.start
                )
            }
            score
        }
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

    private fun semanticTokenEntries(analysis: MCFPPDocumentAnalysis): List<SemanticTokenEntry> {
        val cache = symbolCaches.forDocument(analysis)
        cache.semanticTokens?.let { return it }
        val entriesByRange = linkedMapOf<String, SemanticTokenEntry>()
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
                val symbol = resolveReferenceOccurrence(analysis, occurrence) ?: return@forEach
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

    private fun resolveReferenceOccurrence(
        analysis: MCFPPDocumentAnalysis,
        occurrence: MCFPPReferenceOccurrence
    ): MCFPPSymbol? {
        if (occurrence.isCall) {
            return resolveCallTarget(analysis, occurrence)
        }
        resolveQualifiedGlobalSymbol(analysis, occurrence.name, occurrence.range.start)?.let { return it }
        val receiver = analysis.receiverBefore(occurrence.range.end)
        if (receiver != null) {
            val receiverType = resolveReceiverTypeAt(analysis, receiver, occurrence.range.start)
            if (receiverType != null) {
                return receiverMembers(analysis, receiver, receiverType, occurrence.range.start)
                    .firstOrNull { it.name == occurrence.name }
            }
        }
        return resolveLocalSymbol(analysis, occurrence.name, occurrence.range.start)
            ?: resolveGlobalSymbol(analysis, occurrence.name, occurrence.range.start)
    }

    private fun semanticTokenType(symbol: MCFPPSymbol): String {
        if (symbol.isMacro || symbol.isNative && workspaceSymbols().any { it.isMacro &&
                it.name == symbol.name && it.namespaceName == symbol.namespaceName &&
                it.parameters.map { parameter -> parameter.typeName } == symbol.parameters.map { parameter -> parameter.typeName } }) return "macro"
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

    private fun semanticRangeKey(range: Range): String = listOf(
        range.start.line,
        range.start.character,
        range.end.line,
        range.end.character
    ).joinToString(":")

    private fun rangesOverlap(left: Range, right: Range): Boolean {
        return !isBeforeOrEqual(left.end, right.start) && !isBeforeOrEqual(right.end, left.start)
    }

    private fun visibleSymbolsForCompletion(analysis: MCFPPDocumentAnalysis, position: Position): List<MCFPPSymbol> {
        return analysis.symbols
            .filter { !it.topLevel }
            .filter { it.kind in setOf(SymbolKind.Variable, SymbolKind.Field, SymbolKind.Method, SymbolKind.Function, SymbolKind.Constructor, SymbolKind.EnumMember, SymbolKind.TypeParameter) }
            .filter { analysis.isScopeVisible(it.enclosingFunctionScopeId ?: it.enclosingTypeScopeId, position) }
            .filter { isBeforeOrEqual(it.range.start, position) }
            .sortedBy { it.name }
    }

    private fun resolveLocalSymbol(analysis: MCFPPDocumentAnalysis, word: String, position: Position): MCFPPSymbol? {
        val declarations = analysis.declarationsByName[word].orEmpty().filter { it.uri == analysis.uri }
        declarations.firstOrNull { containsPosition(it.range, position) }?.let {
            return it
        }
        val currentTypeScopeId = analysis.innermostTypeScope(position)?.id
        return declarations
            .filter {
                it.kind in setOf(
                    SymbolKind.Variable,
                    SymbolKind.Field,
                    SymbolKind.Function,
                    SymbolKind.Method,
                    SymbolKind.Constructor,
                    SymbolKind.EnumMember,
                    SymbolKind.Class,
                    SymbolKind.Enum,
                    SymbolKind.TypeParameter,
                    SymbolKind.Namespace
                )
            }
            .filter { isBeforeOrEqual(it.range.start, position) }
            .filter {
                when {
                    it.enclosingFunctionScopeId != null -> analysis.isScopeVisible(it.enclosingFunctionScopeId, position)
                    it.enclosingTypeScopeId != null -> it.enclosingTypeScopeId == currentTypeScopeId || it.kind in setOf(SymbolKind.Field, SymbolKind.Method, SymbolKind.Constructor)
                    else -> true
                }
            }
            .maxWithOrNull(
                compareBy<MCFPPSymbol>(
                    { scopePriority(it, analysis) },
                    { it.range.start.line },
                    { it.range.start.character }
                )
            ) ?: if (workspaceSymbols().any { it.kind == SymbolKind.EnumMember && it.name == word })
                contextualEnumMembers(analysis, position).firstOrNull { it.name == word } else null
    }

    private fun findImplementations(target: MCFPPSymbol): List<Location> {
        val workspace = workspaceSymbols()
        val typeKinds = setOf(SymbolKind.Class, SymbolKind.Enum)
        val implementerTypes = when (target.kind) {
            SymbolKind.Class -> workspace
                .filter { it.topLevel && it.kind in typeKinds }
                .filter { candidate -> isSubtype(candidate, target) }
            else -> emptyList()
        }
        val memberImplementations = when (target.kind) {
            SymbolKind.Method,
            SymbolKind.Function,
            SymbolKind.Field,
            SymbolKind.Property,
            SymbolKind.Constructor,
            SymbolKind.EnumMember -> {
                val ownerType = target.containerName ?: target.receiverType
                if (ownerType == null) {
                    emptyList()
                } else {
                    val ownerTypeSymbol = workspace.firstOrNull { candidate ->
                        candidate.topLevel &&
                            candidate.kind in typeKinds &&
                            candidate.name == ownerType &&
                            (target.namespaceName == null || candidate.namespaceName == target.namespaceName)
                    }
                    val implementerTypeKeys = workspace
                        .filter { it.topLevel && it.kind in typeKinds }
                        .filter { candidate -> ownerTypeSymbol != null && isSubtype(candidate, ownerTypeSymbol) }
                        .map { it.namespaceName to it.name }
                        .toSet()
                    workspace.filter { candidate ->
                        candidate.name == target.name &&
                            candidate.kind == target.kind &&
                            parameterSignatureKey(candidate) == parameterSignatureKey(target) &&
                            candidate.location() != target.location() &&
                            ((candidate.containerName != null && (candidate.namespaceName to candidate.containerName) in implementerTypeKeys) ||
                                (candidate.receiverType != null && (candidate.namespaceName to candidate.receiverType) in implementerTypeKeys))
                    }
                }
            }
            else -> emptyList()
        }
        return (implementerTypes + memberImplementations)
            .distinctBy { listOf(it.uri, it.range.start.line, it.range.start.character, it.kind, it.name).joinToString("|") }
            .map { it.location() }
    }

    private fun isSubtype(candidate: MCFPPSymbol, target: MCFPPSymbol): Boolean {
        val pending = ArrayDeque<MCFPPSymbol>()
        val visited = mutableSetOf<String>()
        pending.addLast(candidate)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(symbolIdentityKey(current))) {
                continue
            }
            current.superTypes.forEach { typeName ->
                val parent = resolveTypeSymbol(typeName, current) ?: return@forEach
                if (sameSymbol(parent, target)) {
                    return true
                }
                pending.addLast(parent)
            }
        }
        return false
    }

    private fun findReferences(target: MCFPPSymbol, includeDeclaration: Boolean): List<Location> {
        return allAnalyses().values.flatMap { document ->
            buildList {
                addAll(collectReferenceRanges(document, target, includeDeclaration).map { Location(document.uri, it) })
            }
        }
            .distinctBy { listOf(it.uri, it.range.start.line, it.range.start.character, it.range.end.line, it.range.end.character).joinToString("|") }
    }

    private fun collectReferenceRanges(document: MCFPPDocumentAnalysis, target: MCFPPSymbol, includeDeclaration: Boolean): List<Range> {
        return buildList {
            addAll(
                document.referenceOccurrencesByName[target.name].orEmpty()
                    .filter { matchesScopedReference(document, it, target) }
                    .map { it.range }
            )
            if (includeDeclaration) {
                addAll(
                    document.declarationsByName[target.name].orEmpty()
                        .filter { matchesScopedDeclaration(it, target) }
                        .map { it.range }
                )
            }
        }.distinctBy { listOf(it.start.line, it.start.character, it.end.line, it.end.character).joinToString(":") }
    }

    private fun matchesScopedReference(
        document: MCFPPDocumentAnalysis,
        reference: MCFPPReferenceOccurrence,
        target: MCFPPSymbol
    ): Boolean {
        if (reference.isNamedArgument) return resolveNamedArgument(document, reference)?.let { sameSymbol(it, target) } == true
        val targetOwner = target.containerName ?: target.receiverType
        if (targetOwner != null) {
            val receiver = document.receiverBefore(reference.range.end)
            val receiverType = receiver?.let { resolveReceiverTypeAt(document, it, reference.range.start) }
            if (receiverType != null) {
                return reference.name == target.name && isTypeOrSubtype(document, receiverType, targetOwner)
            }
        }
        val resolved = resolveLocalSymbol(document, reference.name, reference.range.start)
            ?: resolveGlobalSymbol(document, reference.name, reference.range.start)
        return resolved != null && sameSymbol(resolved, target)
    }

    private fun sameSymbol(left: MCFPPSymbol, right: MCFPPSymbol): Boolean {
        if (left.isExternal || right.isExternal) {
            return left.isExternal == right.isExternal &&
                left.name == right.name &&
                left.kind == right.kind &&
                left.namespaceName == right.namespaceName &&
                left.containerName == right.containerName &&
                left.receiverType == right.receiverType &&
                parameterSignatureKey(left) == parameterSignatureKey(right)
        }
        return left.uri == right.uri &&
            left.kind == right.kind &&
            left.range == right.range &&
            left.enclosingTypeScopeId == right.enclosingTypeScopeId &&
            left.enclosingFunctionScopeId == right.enclosingFunctionScopeId
    }

    private fun matchesScopedDeclaration(candidate: MCFPPSymbol, target: MCFPPSymbol): Boolean {
        return candidate.uri == target.uri &&
            candidate.kind == target.kind &&
            candidate.range == target.range &&
            candidate.enclosingTypeScopeId == target.enclosingTypeScopeId &&
            candidate.enclosingFunctionScopeId == target.enclosingFunctionScopeId
    }

    private fun scopePriority(symbol: MCFPPSymbol, analysis: MCFPPDocumentAnalysis): Int {
        val scope = symbol.enclosingFunctionScopeId ?: symbol.enclosingTypeScopeId ?: return 0
        return analysis.scopeDepth(scope)
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

    private fun allAnalyses(): Map<String, MCFPPDocumentAnalysis> {
        return linkedMapOf<String, MCFPPDocumentAnalysis>().apply {
            putAll(projectIndex.snapshot().fileAnalyses)
            putAll(analyses)
        }
    }

    private fun cachedWorkspaceSymbols(): List<MCFPPSymbol> {
        val caches = symbolCaches
        caches.workspace?.let { return it }
        return synchronized(caches) {
            caches.workspace ?: allAnalyses().values
                .flatMap { analysis -> analysis.topLevelSymbols + analysis.typeMembers.values.flatten() }
                .plus(projectIndex.snapshot().librarySymbols)
                .distinctBy(::symbolIdentityKey)
                .sortedBy { it.name }
                .also { caches.workspace = it }
        }
    }

    private fun invalidateWorkspaceSymbolCache() {
        symbolCaches = SymbolCaches()
    }

    private fun invalidateSemanticTokens() {
        runCatching { server.getClientOrNull()?.refreshSemanticTokens() }
            .onSuccess { refresh ->
                refresh?.exceptionally { error ->
                    reportBackgroundFailure("requesting a semantic token refresh", error as? Exception ?: Exception(error))
                    null
                }
            }
            .onFailure { error ->
                reportBackgroundFailure("requesting a semantic token refresh", error as? Exception ?: Exception(error))
            }
    }

    private fun visibleGlobalSymbolsForDocument(analysis: MCFPPDocumentAnalysis): List<MCFPPSymbol> {
        val cache = symbolCaches.forDocument(analysis)
        cache.visible?.let { return it }
        val exactImports = analysis.imports.filter { it.importedName != "*" }
        val wildcardNamespaces = analysis.imports.filter { it.importedName == "*" }.map { it.namespace }.toSet()
        val currentNamespace = analysis.namespaceName ?: projectIndex.namespaceForUri(analysis.uri)
        return workspaceSymbols().filter { symbol ->
            if (symbol.isStdlib || symbol.uri == analysis.uri) {
                return@filter true
            }
            val namespace = symbol.namespaceName ?: projectIndex.namespaceForUri(symbol.uri) ?: return@filter false
            if (namespace == currentNamespace) {
                return@filter true
            }
            if (namespace in wildcardNamespaces) {
                return@filter true
            }
            exactImports.any { imported ->
                if (imported.namespace != namespace) {
                    false
                } else {
                    val owner = normalizeTypeName(symbol.containerName ?: symbol.receiverType)
                    (symbol.topLevel && imported.importedName == symbol.name) || owner == imported.importedName
                }
            }
        }.also { cache.visible = it }
    }

    private fun duplicateDeclarationKey(symbol: MCFPPSymbol): String {
        return listOf(
            symbol.kind.name,
            symbol.name,
            symbol.containerName ?: "",
            symbol.receiverType ?: "",
            symbol.enclosingTypeScopeId ?: "",
            symbol.enclosingFunctionScopeId ?: "",
            symbol.topLevel.toString(),
            parameterSignatureKey(symbol)
        ).joinToString("|")
    }

    private fun resolveGlobalSymbol(analysis: MCFPPDocumentAnalysis, name: String, position: Position): MCFPPSymbol? {
        resolveQualifiedGlobalSymbol(analysis, name, position)?.let { return it }
        val currentTypeName = analysis.innermostTypeScope(position)?.name
        val aliasedImport = analysis.imports.firstOrNull { it.alias == name }
        if (aliasedImport != null) {
            return workspaceSymbols()
                .asSequence()
                .filter { it.topLevel }
                .filter { it.name == aliasedImport.importedName && it.namespaceName == aliasedImport.namespace }
                .maxWithOrNull(compareBy<MCFPPSymbol>({ it.range.start.line }, { it.range.start.character }))
        }
        return visibleGlobalSymbolsForDocument(analysis)
            .asSequence()
            .filter { it.name == name }
            .filter { isAccessibleByOriginalName(analysis, it) }
            .filter { isGlobalSymbolVisibleAt(it, currentTypeName) }
            .maxWithOrNull(
                compareBy<MCFPPSymbol>(
                    { globalSymbolPriority(it, currentTypeName) },
                    { it.range.start.line },
                    { it.range.start.character }
                )
            )
    }

    private fun resolveQualifiedGlobalSymbol(
        analysis: MCFPPDocumentAnalysis,
        name: String,
        position: Position
    ): MCFPPSymbol? {
        val wordStart = analysis.wordRange(position)?.start ?: position
        val namespace = namespaceQualifierBefore(analysis, wordStart)
            ?.let { resolveNamespaceQualifier(analysis, it) }
            ?: return null
        return workspaceSymbols()
            .asSequence()
            .filter { it.topLevel && it.namespaceName == namespace && it.name == name }
            .maxWithOrNull(compareBy<MCFPPSymbol>({ it.range.start.line }, { it.range.start.character }))
    }

    private fun isAccessibleByOriginalName(analysis: MCFPPDocumentAnalysis, symbol: MCFPPSymbol): Boolean {
        if (symbol.isStdlib || symbol.uri == analysis.uri) {
            return true
        }
        val namespace = symbol.namespaceName ?: projectIndex.namespaceForUri(symbol.uri) ?: return false
        val currentNamespace = analysis.namespaceName ?: projectIndex.namespaceForUri(analysis.uri)
        if (namespace == currentNamespace) {
            return true
        }
        if (analysis.imports.any { it.namespace == namespace && it.importedName == "*" && it.alias == null }) {
            return true
        }
        return analysis.imports.any {
            it.namespace == namespace && it.importedName == symbol.name && it.alias == null
        }
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
        val resolved = resolveReferenceOccurrence(analysis, occurrence) ?: return null
        return imported.takeIf {
            resolved.name == imported.importedName && resolved.namespaceName == imported.namespace
        }
    }

    private fun importAliasRanges(analysis: MCFPPDocumentAnalysis, imported: MCFPPImport): List<Range> {
        val alias = imported.alias ?: return emptyList()
        return buildList {
            imported.aliasRange?.let(::add)
            analysis.referenceOccurrencesByName[alias].orEmpty().forEach { occurrence ->
                val resolved = resolveReferenceOccurrence(analysis, occurrence)
                if (resolved?.name == imported.importedName && resolved.namespaceName == imported.namespace) {
                    add(occurrence.range)
                }
            }
        }.distinctBy { range ->
            listOf(range.start.line, range.start.character, range.end.line, range.end.character).joinToString(":")
        }
    }

    private fun isGlobalSymbolVisibleAt(symbol: MCFPPSymbol, currentTypeName: String?): Boolean {
        if (symbol.topLevel) {
            return true
        }
        if (currentTypeName == null) {
            return false
        }
        return symbol.containerName == currentTypeName || symbol.receiverType == currentTypeName
    }

    private fun globalSymbolPriority(symbol: MCFPPSymbol, currentTypeName: String?): Int {
        return when {
            currentTypeName != null && (symbol.containerName == currentTypeName || symbol.receiverType == currentTypeName) -> 2
            symbol.topLevel -> 1
            else -> 0
        }
    }

    private fun collectUndefinedVariableDiagnostics(document: MCFPPDocumentAnalysis): List<Diagnostic> {
        return document.referenceOccurrencesByName.values
            .flatten()
            .filter { shouldCheckUndefinedReference(document, it) }
            .filterNot { isReferenceResolved(document, it) }
            .distinctBy { listOf(it.name, it.range.start.line, it.range.start.character).joinToString("|") }
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
                    val receiverType = resolveReceiverTypeAt(document, receiver, reference.range.start)
                        ?: return@mapNotNull null
                    memberSymbolsForType(document, receiverType).filter { it.name == reference.name }
                } else if (reference.isCall && resolveCallTarget(document, reference) == null) {
                    memberSymbolsForType(document, reference.name)
                        .filter { it.kind == SymbolKind.Constructor && it.name == reference.name }
                } else {
                    return@mapNotNull null
                }
                if (matchingMembers.isEmpty() || matchingMembers.any { isMemberAccessible(document, it, reference.range.start) }) {
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
            .distinctBy { listOf(it.range.start.line, it.range.start.character, it.message).joinToString("|") }
            .toList()
    }

    private fun isReferenceResolved(document: MCFPPDocumentAnalysis, reference: MCFPPReferenceOccurrence): Boolean {
        if (resolveReferenceOccurrence(document, reference) != null) {
            return true
        }
        val receiver = document.receiverBefore(reference.range.end)
        if (receiver != null) {
            val receiverType = resolveReceiverTypeAt(document, receiver, reference.range.start) ?: return false
            if (MCFPPDocumentIndex.isReferenceType(receiverType) && reference.name in setOf("tryRead", "set")) return true
            return receiverMembers(document, receiver, receiverType, reference.range.start).any { it.name == reference.name } ||
                memberSymbolsForType(document, receiverType).any { it.name == reference.name }
        }
        if (reference.isCall) {
            return memberSymbolsForType(document, reference.name)
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

    private fun resolveNamedArgument(document: MCFPPDocumentAnalysis, reference: MCFPPReferenceOccurrence): MCFPPSymbol? {
        val call = MCFPPCallContextFinder.findCandidates(document.text, reference.range.start).firstOrNull() ?: return null
        val callable = resolveCallableSymbols(document, call, reference.range.start)
            .maxByOrNull { signatureMatchScore(it, call, document, reference.range.start) } ?: return null
        val owner = allAnalyses()[callable.uri] ?: return null
        return owner.symbols.firstOrNull { it.isParameter && it.name == reference.name && containsPosition(callable.fullRange, it.range.start) }
    }

    private fun collectTypeDiagnostics(document: MCFPPDocumentAnalysis): List<Diagnostic> {
        val (literals, _) = templateContext(document)
        val checker = typeChecker(document) { start, end ->
            literals.startingAt(start)?.takeIf { it.close() + 1 == end }?.let { literal ->
                if (literal.kind() == '{') literals.bestTypes(literal).firstOrNull() else literals.types(literal).firstOrNull()
            }
        }
        return checker.collectDiagnostics() + literals.diagnostics().map {
            Diagnostic(Range(offsetToPosition(document.text, it.start()), offsetToPosition(document.text, it.end())),
                it.message(), DiagnosticSeverity.Error, "mcfpp").apply { code = Either.forLeft("mcfpp.template-literal") }
        }
    }

    private fun typeChecker(document: MCFPPDocumentAnalysis, contextualLiteralType: (Int, Int) -> String? = { _, _ -> null }): MCFPPTypeChecker =
        MCFPPTypeChecker(
            document = document,
            visibleGlobals = visibleGlobalSymbolsForDocument(document),
            resolveLocalSymbol = { name, position -> resolveLocalSymbol(document, name, position) },
            resolveGlobalSymbol = { name, position -> resolveGlobalSymbol(document, name, position) },
            resolveCallSymbol = { occurrence -> resolveCallTarget(document, occurrence) },
            resolveMemberSymbol = { ownerType, name, position ->
                val receiver = document.receiverBefore(position)
                (if (receiver != null) receiverMembers(document, receiver, ownerType, position)
                    else accessibleMemberSymbolsForType(document, ownerType, position)).firstOrNull { it.name == name }
            },
            resolveImportedType = { typeName -> resolveImportedTypeName(document, typeName) ?: typeName },
            contextualLiteralType = contextualLiteralType
        )

    private fun templateSchema(analysis: MCFPPDocumentAnalysis): MCFPPTemplateLiterals = MCFPPTemplateLiterals(
        workspaceSymbols(),
        { type ->
            if (':' in type) resolveNamespaceQualifier(analysis, type.substringBeforeLast(':')) + ":" + type.substringAfterLast(':')
            else {
                val namespace = analysis.namespaceName ?: projectIndex.namespaceForUri(analysis.uri)
                val local = workspaceSymbols().firstOrNull { it.topLevel && it.name == type && it.kind in TYPE_SYMBOL_KINDS &&
                    (it.uri == analysis.uri || it.namespaceName == namespace && !it.isStdlib) }
                local?.let { symbol -> symbol.namespaceName?.takeIf { it.isNotEmpty() }?.let { "$it:${symbol.name}" } ?: symbol.name }
                    ?: analysis.imports.firstOrNull { !it.importedName.equals("*") && (it.alias ?: it.importedName) == type }
                        ?.let { "${it.namespace}:${it.importedName}" }
                    ?: type
            }
        },
        { field, offset -> isMemberAccessible(analysis, field, offsetToPosition(analysis.text, offset)) }
    )

    private fun templateContext(analysis: MCFPPDocumentAnalysis): Pair<top.mcfpp.language.TemplateLiterals, MCFPPTemplateLiterals> {
        val schema = templateSchema(analysis)
        val checker by lazy { typeChecker(analysis) }
        val resolver = object : top.mcfpp.language.TemplateLiterals.Resolver {
            override fun fields(type: String, offset: Int) = schema.schema(type, offset)
            override fun constructionError(type: String, offset: Int) = schema.constructionError(type, offset)
            override fun returnTypes(expressionStart: Int): List<String> {
                val position = offsetToPosition(analysis.text, expressionStart)
                return analysis.symbols.filter { it.kind in CALLABLE_SYMBOL_KINDS && containsPosition(it.fullRange, position) }
                    .minByOrNull { MCFPPTextChanges.positionToOffset(analysis.text, it.fullRange.end) - MCFPPTextChanges.positionToOffset(analysis.text, it.fullRange.start) }
                    ?.typeName?.let(::listOf).orEmpty()
            }
            override fun argumentTypes(expressionStart: Int): List<String> {
                val position = offsetToPosition(analysis.text, expressionStart)
                for (call in MCFPPCallContextFinder.findCandidates(analysis.analysisText, position)) {
                    val targets = resolveCallableSymbols(analysis, call, position)
                    if (targets.isEmpty()) continue
                    val named = Regex("^\\s*([\\p{L}_][\\p{L}\\p{N}_]*)\\s*=\\s*$")
                        .matchEntire(call.arguments.lastOrNull().orEmpty())?.groupValues?.get(1)
                    return targets.mapNotNull { target ->
                        val parameters = (target.parameters + target.contextParameters).filter {
                            it.isReadOnly == (call.argumentGroup == MCFPPArgumentGroup.READ_ONLY)
                        }
                        val parameter = if (named != null) parameters.firstOrNull { it.name == named }
                            else parameters.getOrNull(call.activeParameter)
                        parameter?.typeName?.let { substituteTypeParameters(it, typeParameterSubstitutions(target, call.readOnlyArguments)) }
                    }
                }
                return emptyList()
            }
            override fun assignmentType(expression: String, offset: Int): String? {
                val position = offsetToPosition(analysis.text, offset)
                val separator = lastTopLevelMemberSeparator(expression)
                if (separator > 0) {
                    val owner = resolveReceiverTypeAt(analysis, expression.substring(0, separator), position)
                    top.mcfpp.language.EntityBuiltins.member(owner, expression.substring(separator + 1).trim())?.writeType()?.let { return it }
                }
                return resolveReceiverTypeAt(analysis, expression, position)
            }
            override fun valueType(expression: String, offset: Int): String? = checker.expressionType(expression, offset)
                ?: inferArgumentType(analysis, expression, offsetToPosition(analysis.text, offset))
            override fun isAssignable(expected: String, actual: String): Boolean =
                if (actual == "null") false else checker.isAssignable(expected, actual)
        }
        return top.mcfpp.language.TemplateLiterals(analysis.analysisText, resolver) to schema
    }

    private fun templateCompletions(analysis: MCFPPDocumentAnalysis, position: Position): List<CompletionItem>? {
        val (literals, schema) = templateContext(analysis)
        val offset = MCFPPTextChanges.positionToOffset(analysis.text, position)
        val literal = literals.at(offset) ?: return null
        val site = literals.keySite(literal, offset) ?: return null
        val types = literals.bestTypes(literal).takeIf { it.isNotEmpty() } ?: return null
        return literals.completions(literal, site).map { field ->
            val symbol = types.firstNotNullOfOrNull { schema.fields(it)?.firstOrNull { it.name == field.name() } }
            CompletionItem(field.name()).apply {
                kind = CompletionItemKind.Field
                detail = "${field.name()}: ${field.type()}" + if (field.hasDefault()) " (default)" else ""
                symbol?.documentation?.let { documentation = Either.forRight(MarkupContent(MarkupKind.MARKDOWN, it)) }
                insertTextFormat = if (site.hasColon()) InsertTextFormat.PlainText else InsertTextFormat.Snippet
                textEdit = Either.forLeft(TextEdit(Range(offsetToPosition(analysis.text, site.start()), offsetToPosition(analysis.text, site.end())),
                    field.name() + if (site.hasColon()) "" else ": \${1}"))
                sortText = "0${field.name()}"
            }
        }
    }

    private fun templateFieldAt(analysis: MCFPPDocumentAnalysis, position: Position): MCFPPSymbol? {
        val (literals, schema) = templateContext(analysis)
        val offset = MCFPPTextChanges.positionToOffset(analysis.text, position)
        val literal = literals.at(offset) ?: return null
        val entry = literals.entries(literal).firstOrNull { it.colon() >= 0 && offset >= it.keyStart() && offset <= it.keyEnd() } ?: return null
        return literals.bestTypes(literal).firstNotNullOfOrNull { type -> schema.fields(type)?.firstOrNull { it.name == entry.name() } }
    }

    private fun determineCompletionContext(analysis: MCFPPDocumentAnalysis, position: Position): CompletionContext {
        val line = analysis.line(position.line)
        val prefix = line.substring(0, position.character.coerceIn(0, line.length))
        val trimmed = prefix.trimEnd()
        val beforeIdentifier = prefix.replace(Regex("[A-Za-z_][A-Za-z0-9_]*$"), "")
        if (top.mcfpp.language.CompletionScope.isTypeArgumentOrCast(beforeIdentifier)) {
            return CompletionContext.TYPE
        }
        if (Regex("(?:\\bas\\b|\\)\\s*:)\\s*[A-Za-z0-9_:.<>!?|&\\[\\]]*$").containsMatchIn(trimmed)) {
            return CompletionContext.TYPE
        }
        if (Regex("(?:\\b(?:var|const|external)\\s+[A-Za-z_][A-Za-z0-9_]*\\s*:|\\bfunc\\s+[A-Za-z_][A-Za-z0-9_]*\\s*(?:<[^>]*>\\s*)?\\([^)]*\\)\\s*:|[<(,]\\s*[A-Za-z_][A-Za-z0-9_]*\\s*:)\\s*[A-Za-z0-9_:.<>!?|&\\[\\]]*$").containsMatchIn(trimmed)) {
            return CompletionContext.TYPE
        }
        return if (top.mcfpp.language.CompletionScope.isValuePosition(beforeIdentifier)) CompletionContext.VALUE
            else CompletionContext.DEFAULT
    }

    private fun namespaceQualifierBefore(analysis: MCFPPDocumentAnalysis, position: Position): String? {
        val line = analysis.line(position.line)
        val prefix = line.substring(0, position.character.coerceIn(0, line.length))
        val match = NAMESPACE_QUALIFIER_AT_CURSOR.find(prefix) ?: return null
        val beforeQualifier = prefix.substring(0, match.range.first).trimEnd()
        if (Regex("\\b(?:var|const|external)\\s*$").containsMatchIn(beforeQualifier) ||
            beforeQualifier.lastOrNull() in setOf('(', '<', ',')) {
            return null
        }
        return match.groupValues[1]
    }

    private fun resolveNamespaceQualifier(analysis: MCFPPDocumentAnalysis, qualifier: String): String {
        return analysis.imports.firstOrNull { imported ->
            imported.importedName == "*" && imported.alias == qualifier
        }?.namespace ?: qualifier
    }

    private fun resolveImportedTypeName(analysis: MCFPPDocumentAnalysis, typeName: String): String? {
        if (typeName.any { it in "({<" }) return typeName
        val normalized = normalizeTypeName(typeName) ?: return null
        val imported = analysis.imports.firstOrNull { candidate ->
            candidate.importedName != "*" && candidate.alias == normalized
        } ?: return normalized
        val isType = workspaceSymbols().any { symbol ->
            symbol.topLevel && symbol.kind in TYPE_SYMBOL_KINDS &&
                symbol.namespaceName == imported.namespace && symbol.name == imported.importedName
        }
        return if (isType) imported.importedName else normalized
    }

    private fun containsPosition(range: Range, position: Position): Boolean {
        return !isBefore(position, range.start) && !isBefore(range.end, position)
    }

    private fun isBeforeOrEqual(left: Position, right: Position): Boolean {
        return left.line < right.line || (left.line == right.line && left.character <= right.character)
    }

    private fun isBefore(left: Position, right: Position): Boolean {
        return left.line < right.line || (left.line == right.line && left.character < right.character)
    }

    private fun normalizeTypeName(typeName: String?): String? {
        if (typeName.isNullOrBlank()) {
            return null
        }
        return typeName.substringBefore('<').substringBefore('?').substringAfterLast(':').trim().trimEnd(';', ',')
    }

    private fun keywordCompletion(keyword: String): CompletionItem = CompletionItem(keyword).apply {
        kind = CompletionItemKind.Keyword
        detail = "keyword"
    }

    private fun typeCompletion(typeName: String): CompletionItem = CompletionItem(typeName).apply {
        kind = CompletionItemKind.Class
        detail = "builtin type"
    }

    private fun valueCompletion(value: String): CompletionItem = CompletionItem(value).apply {
        kind = CompletionItemKind.Value
        detail = "builtin value"
    }

    private fun typeParameterSubstitutions(
        symbol: MCFPPSymbol,
        readOnlyArguments: List<String>
    ): Map<String, String> = symbol.parameters
        .filter { it.isReadOnly && normalizeTypeName(it.typeName).equals("type", ignoreCase = true) }
        .zip(readOnlyArguments)
        .mapNotNull { (parameter, argument) ->
            val name = parameter.name ?: return@mapNotNull null
            argument.trim().takeIf { it.isNotEmpty() }?.let { name to it }
        }
        .toMap()

    private fun substituteTypeParameters(typeName: String, substitutions: Map<String, String>): String {
        return substitutions.entries.fold(typeName) { current, (parameter, argument) ->
            current.replace(
                Regex("(?<![A-Za-z0-9_])${Regex.escape(parameter)}(?![A-Za-z0-9_])"),
                Regex.escapeReplacement(argument)
            )
        }
    }

    private fun globalCompletionItems(
        analysis: MCFPPDocumentAnalysis,
        include: (MCFPPSymbol) -> Boolean = { true }
    ): List<CompletionItem> {
        val currentNamespace = analysis.namespaceName ?: projectIndex.namespaceForUri(analysis.uri)
        val wildcardNamespaces = analysis.imports
            .filter { it.importedName == "*" && it.alias == null }
            .mapTo(mutableSetOf()) { it.namespace }
        return visibleGlobalSymbolsForDocument(analysis)
            .asSequence()
            .filter { it.topLevel && include(it) }
            .flatMap { symbol ->
                val namespace = symbol.namespaceName ?: projectIndex.namespaceForUri(symbol.uri)
                val matchingImports = analysis.imports.filter {
                    it.importedName == symbol.name && it.namespace == namespace
                }
                buildList {
                    matchingImports.mapNotNullTo(this) { imported ->
                        imported.alias?.let { alias -> symbolCompletion(symbol, alias) }
                    }
                    if (
                        (symbol.isStdlib && namespace in setOf("mcfpp.lang", "mcfpp.sys", "mcfpp")) ||
                        symbol.uri == analysis.uri ||
                        namespace == currentNamespace ||
                        (namespace != null && namespace in wildcardNamespaces) ||
                        matchingImports.any { it.alias == null }
                    ) {
                        add(symbolCompletion(symbol))
                    }
                }.asSequence()
            }
            .toList()
    }

    private fun symbolCompletion(
        symbol: MCFPPSymbol,
        displayName: String = symbol.name
    ): CompletionItem = CompletionItem(displayName).apply {
        kind = symbol.toCompletionKind()
        detail = if (symbol.kind in setOf(SymbolKind.Function, SymbolKind.Method, SymbolKind.Constructor)) {
            buildSignatureLabel(symbol, displayName)
        } else {
            symbol.typeName ?: symbol.detail ?: symbol.containerName
        }
        symbol.documentation?.takeIf { it.isNotBlank() }?.let {
            documentation = Either.forRight(MarkupContent(MarkupKind.MARKDOWN, it))
        }
    }

    private fun isRenameable(symbol: MCFPPSymbol): Boolean {
        return !symbol.isExternal && !symbol.isSynthetic && symbol.kind in RENAMABLE_SYMBOL_KINDS
    }

    private fun isValidIdentifier(name: String): Boolean {
        return IDENTIFIER_REGEX.matches(name) && name !in MCFPPDocumentIndex.keywords
    }

    private companion object {
        const val DOCUMENT_CHANGE_DEBOUNCE_MS = 120L
        const val PROJECT_CHANGE_DEBOUNCE_MS = 150L
        const val DEPENDENT_DIAGNOSTICS_DEBOUNCE_MS = 150L
        val IDENTIFIER_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val SIGNED_INTEGER = Regex("[+-]?\\d+[bBsSlL]?")
        val SIGNED_DECIMAL = Regex("[+-]?(?:\\d+\\.\\d*|\\d*\\.\\d+)(?:[eE][+-]?\\d+)?[fFdD]?")
        val CALL_EXPRESSION = Regex("([A-Za-z_][A-Za-z0-9_.:]*)\\s*(?:<.*>)?\\s*\\(.*\\)")
        val NAMESPACE_QUALIFIER_AT_CURSOR = Regex("([A-Za-z_][A-Za-z0-9_.]*)\\s*:\\s*[A-Za-z_0-9]*$")
        val NUMERIC_TYPES = setOf("byte", "short", "int", "long", "float", "double")
        val CALLABLE_SYMBOL_KINDS = setOf(SymbolKind.Function, SymbolKind.Method, SymbolKind.Constructor)
        val TYPE_SYMBOL_KINDS = setOf(SymbolKind.Class, SymbolKind.Enum)
        val TYPE_DEFINITION_SYMBOL_KINDS = TYPE_SYMBOL_KINDS + SymbolKind.TypeParameter
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
