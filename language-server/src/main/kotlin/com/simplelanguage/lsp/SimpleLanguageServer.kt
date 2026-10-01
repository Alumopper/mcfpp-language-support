package com.simplelanguage.lsp

import org.eclipse.lsp4j.CompletionOptions
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionOptions
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.InlayHintRegistrationOptions
import org.eclipse.lsp4j.RenameOptions
import org.eclipse.lsp4j.SaveOptions
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.SemanticTokensLegend
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions
import org.eclipse.lsp4j.SignatureHelpOptions
import org.eclipse.lsp4j.SetTraceParams
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TextDocumentSyncOptions
import org.eclipse.lsp4j.WorkspaceFoldersOptions
import org.eclipse.lsp4j.WorkspaceServerCapabilities
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture

class SimpleLanguageServer : LanguageServer, LanguageClientAware {
    private lateinit var client: LanguageClient
    private val textDocumentService = MCFPPTextDocumentService(this)
    private val workspaceService = MCFPPWorkspaceService(this)
    @Volatile
    private var traceValue: String = "off"

    override fun getTextDocumentService(): TextDocumentService = textDocumentService

    override fun getWorkspaceService(): WorkspaceService = workspaceService

    override fun connect(client: LanguageClient) {
        this.client = client
    }

    @Suppress("DEPRECATION")
    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        val roots = params.workspaceFolders?.mapNotNull { it.uri }?.takeIf { it.isNotEmpty() }
            ?: params.rootUri?.let(::listOf)
            ?: emptyList()
        textDocumentService.setWorkspaceRoots(roots)
        val capabilities = ServerCapabilities()
        capabilities.textDocumentSync = Either.forRight(TextDocumentSyncOptions().apply {
            openClose = true
            change = TextDocumentSyncKind.Incremental
            setSave(SaveOptions(true))
        })
        capabilities.completionProvider = CompletionOptions(false, listOf(".", ":", "@", "/", "#"))
        capabilities.signatureHelpProvider = SignatureHelpOptions(listOf("(", "<", ","), listOf(","))
        capabilities.definitionProvider = Either.forLeft(true)
        capabilities.declarationProvider = Either.forLeft(true)
        capabilities.typeDefinitionProvider = Either.forLeft(true)
        capabilities.hoverProvider = Either.forLeft(true)
        capabilities.documentSymbolProvider = Either.forLeft(true)
        capabilities.documentHighlightProvider = Either.forLeft(true)
        capabilities.referencesProvider = Either.forLeft(true)
        capabilities.renameProvider = Either.forRight(RenameOptions(true))
        capabilities.implementationProvider = Either.forLeft(true)
        capabilities.callHierarchyProvider = Either.forLeft(true)
        capabilities.typeHierarchyProvider = Either.forLeft(true)
        capabilities.inlayHintProvider = Either.forRight(InlayHintRegistrationOptions().apply {
            resolveProvider = false
        })
        capabilities.semanticTokensProvider = SemanticTokensWithRegistrationOptions(
            SemanticTokensLegend(MCFPPSemanticTokenLegend.tokenTypes, MCFPPSemanticTokenLegend.tokenModifiers)
        ).apply {
            setFull(true)
            setRange(true)
        }
        capabilities.selectionRangeProvider = Either.forLeft(true)
        capabilities.linkedEditingRangeProvider = Either.forLeft(true)
        capabilities.foldingRangeProvider = Either.forLeft(true)
        capabilities.documentFormattingProvider = Either.forLeft(true)
        capabilities.documentRangeFormattingProvider = Either.forLeft(true)
        capabilities.codeActionProvider = Either.forRight(CodeActionOptions(listOf(CodeActionKind.QuickFix)))
        capabilities.workspaceSymbolProvider = Either.forLeft(true)
        capabilities.workspace = WorkspaceServerCapabilities(WorkspaceFoldersOptions().apply {
            supported = true
            setChangeNotifications(true)
        })
        return CompletableFuture.completedFuture(InitializeResult(capabilities))
    }

    override fun shutdown(): CompletableFuture<Any> {
        textDocumentService.shutdown()
        return CompletableFuture.completedFuture(Unit)
    }

    override fun setTrace(params: SetTraceParams) {
        traceValue = params.value ?: "off"
    }

    override fun exit() {
        textDocumentService.shutdown()
        System.exit(0)
    }

    fun getClient(): LanguageClient = client

    fun traceValue(): String = traceValue

    fun getMCFPPTextDocumentService(): MCFPPTextDocumentService = textDocumentService
}
