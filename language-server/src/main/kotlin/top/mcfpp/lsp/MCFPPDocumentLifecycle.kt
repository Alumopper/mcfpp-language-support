package top.mcfpp.lsp

import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import top.mcfpp.language.MniSymbolSnapshot
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class MCFPPDocumentLifecycle(private val server: MCFPPLanguageServer) {
    private class OpenDocument(val text: String, val version: Int) {
        var targetVersion: String? = null
        var parsed: CompletableFuture<MCFPPDocumentAnalysis>? = null
    }

    private val documents = ConcurrentHashMap<String, OpenDocument>()

    private val analyses = ConcurrentHashMap<String, MCFPPDocumentAnalysis>()

    private val projectIndex = MCFPPProjectIndex()
    val workspace = MCFPPWorkspaceView(projectIndex) { analyses }
    val diagnosticsAndTokens = MCFPPDiagnosticsAndTokensHandler(workspace)


    private val projectRefreshLock = Any()

    private val dependentDiagnosticsLock = Any()

    private val analysisExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "mcfpp-analysis").apply { isDaemon = true }
    }

    private val projectIndexExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "mcfpp-project-index").apply { isDaemon = true }
    }

    private val editorExecutor = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "mcfpp-editor").apply { isDaemon = true }
    }

    private val pendingDocumentAnalyses = ConcurrentHashMap<String, ScheduledFuture<*>>()

    private var pendingProjectRefresh: ScheduledFuture<*>? = null

    private var pendingDependentDiagnosticsRefresh: ScheduledFuture<*>? = null

    @Volatile
    private var workspaceRootUris: List<String> = emptyList()

    @Volatile
    private var workspaceIndexReady = true

    fun didOpen(params: DidOpenTextDocumentParams) {
        val document = params.textDocument
        synchronized(documents) {
            val snapshot = OpenDocument(document.text, document.version)
            documents[document.uri] = snapshot
            scheduleDocumentAnalysis(document.uri, snapshot, 0)
        }
    }

    fun didChange(params: DidChangeTextDocumentParams) {
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

    fun didClose(params: DidCloseTextDocumentParams) {
        val uri = params.textDocument.uri
        synchronized(documents) {
            documents.remove(uri)
            analyses.remove(uri)
            pendingDocumentAnalyses.remove(uri)?.cancel(false)
            workspace.invalidateWorkspaceSymbolCache()
        }
        scheduleProjectIndexRefresh(0)
        server.getClientOrNull()?.publishDiagnostics(PublishDiagnosticsParams(uri, emptyList()))
    }

    fun didSave(params: DidSaveTextDocumentParams) {
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

    fun formatting(params: DocumentFormattingParams): CompletableFuture<List<TextEdit>> {
        val text = documents[params.textDocument.uri]?.text
            ?: return CompletableFuture.completedFuture(emptyList())
        return CompletableFuture.supplyAsync({ MCFPPFormatter.format(text, params.options) }, editorExecutor)
    }

    fun rangeFormatting(params: DocumentRangeFormattingParams): CompletableFuture<List<TextEdit>> {
        val text = documents[params.textDocument.uri]?.text
            ?: return CompletableFuture.completedFuture(emptyList())
        return CompletableFuture.supplyAsync({ MCFPPFormatter.format(text, params.options, params.range) }, editorExecutor)
    }

    fun workspaceSymbolsAsync(query: String): CompletableFuture<List<MCFPPSymbol>> =
        onEditor { workspace.workspaceSymbols(query) }

    fun <T> onEditor(action: () -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync(action, editorExecutor)

    fun setWorkspaceRoots(rootUris: List<String>) {
        workspaceRootUris = rootUris.distinct()
        projectIndex.setWorkspaceRoots(workspaceRootUris)
        workspaceIndexReady = false
        scheduleProjectIndexRefresh(0)
    }

    fun updateWorkspaceRoots(addedUris: List<String>, removedUris: List<String>) {
        val removed = removedUris.toSet()
        setWorkspaceRoots((workspaceRootUris.filterNot { it in removed } + addedUris).distinct())
    }

    fun refreshProjectIndex() {
        scheduleProjectIndexRefresh(PROJECT_CHANGE_DEBOUNCE_MS)
    }

    fun refreshProjectIndex(changedUris: List<String>) {
        if (changedUris.none(projectIndex::isRelevantWorkspaceChange)) {
            return
        }
        refreshProjectIndex()
    }

    fun updateMniSymbols(snapshot: MniSymbolSnapshot) {
        projectIndex.setMniSymbols(snapshot)
        workspace.invalidateWorkspaceSymbolCache()
        scheduleDependentDiagnosticsRefresh()
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
        projectIndexExecutor.shutdownNow()
        editorExecutor.shutdownNow()
    }

    fun <T> withEditorAnalysis(uri: String, missing: T?, render: (MCFPPDocumentAnalysis) -> T?): CompletableFuture<T> {
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
                    val surfaceChanged = previous == null || semanticSurface(previous) != semanticSurface(analysis)
                    if (surfaceChanged) workspace.invalidateWorkspaceSymbolCache()
                    else workspace.evictDocumentSymbolCache(uri)
                    surfaceChanged
                }
                if (surfaceChanged) scheduleDependentDiagnosticsRefresh(uri)
                analysis
            }, editorExecutor).also { document.parsed = it }
        }

    private fun scheduleDocumentAnalysis(uri: String, document: OpenDocument, delayMillis: Long) {
        val task = analysisExecutor.schedule({
            try {
                if (documents[uri] !== document) return@schedule
                if (!workspaceIndexReady) return@schedule
                val analysis = parseDocument(uri, document).join()
                publishDiagnostics(uri, document, analysis)
            } catch (error: Throwable) {
                val cause = (error as? CompletionException)?.cause ?: error
                if (cause !is ResponseErrorException || cause.responseError.code != ResponseErrorCode.ContentModified.value) {
                    reportBackgroundFailure("analyzing $uri", cause as? Exception ?: RuntimeException(cause))
                }
            }
        }, delayMillis, TimeUnit.MILLISECONDS)
        pendingDocumentAnalyses.put(uri, task)?.cancel(false)
    }

    private fun publishDiagnostics(uri: String, document: OpenDocument, analysis: MCFPPDocumentAnalysis) {
        if (documents[uri] !== document || analyses[uri] !== analysis) return
        val diagnostics = diagnosticsAndTokens.collectDiagnostics(analysis)
        if (documents[uri] === document && analyses[uri] === analysis) {
            server.getClientOrNull()?.publishDiagnostics(PublishDiagnosticsParams(uri, diagnostics, document.version))
        }
    }

    private fun scheduleDependentDiagnosticsRefresh(changedUri: String? = null) {
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
            pendingProjectRefresh = projectIndexExecutor.schedule({
                try {
                    projectIndex.refresh(documents.mapValues { it.value.text })
                    val openDocuments = synchronized(documents) {
                        workspace.invalidateWorkspaceSymbolCache()
                        documents.toMap()
                    }
                    // Open files are omitted from the disk index. Load their declarations
                    // before sending diagnostics that may depend on imports between them.
                    val pending = openDocuments.map { (uri, document) ->
                        parseDocument(uri, document).handle { _, _ -> Unit }
                    }
                    CompletableFuture.allOf(*pending.toTypedArray()).join()
                } catch (error: Exception) {
                    reportBackgroundFailure("refreshing the project index", error)
                } finally {
                    workspaceIndexReady = true
                    synchronized(documents) {
                        documents.forEach { (uri, document) -> scheduleDocumentAnalysis(uri, document, 0) }
                    }
                    invalidateSemanticTokens()
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

    private companion object {
        const val DOCUMENT_CHANGE_DEBOUNCE_MS = 120L
        const val PROJECT_CHANGE_DEBOUNCE_MS = 150L
        const val DEPENDENT_DIAGNOSTICS_DEBOUNCE_MS = 150L
    }
}
