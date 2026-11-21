package top.mcfpp.lsp

import org.eclipse.lsp4j.CallHierarchyIncomingCall
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyOutgoingCall
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DeclarationParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.ImplementationParams
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.LinkedEditingRangeParams
import org.eclipse.lsp4j.LinkedEditingRanges
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.PrepareRenameResult
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
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.TypeDefinitionParams
import org.eclipse.lsp4j.TypeHierarchyItem
import org.eclipse.lsp4j.TypeHierarchyPrepareParams
import org.eclipse.lsp4j.TypeHierarchySubtypesParams
import org.eclipse.lsp4j.TypeHierarchySupertypesParams
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import org.eclipse.lsp4j.services.TextDocumentService
import top.mcfpp.language.MniSymbolSnapshot
import java.util.concurrent.CompletableFuture

class MCFPPTextDocumentService(server: MCFPPLanguageServer) : TextDocumentService {
    private val lifecycle = MCFPPDocumentLifecycle(server)
    private val workspace = lifecycle.workspace
    private val completion = MCFPPCompletionHandler(workspace)
    private val hoverAndSignature = MCFPPHoverAndSignatureHandler(workspace)
    private val navigation = MCFPPNavigationHandler(workspace)
    private val hierarchyAndRefactor = MCFPPHierarchyAndRefactorHandler(workspace)

    override fun didOpen(params: DidOpenTextDocumentParams) = lifecycle.didOpen(params)

    override fun didChange(params: DidChangeTextDocumentParams) = lifecycle.didChange(params)

    override fun didClose(params: DidCloseTextDocumentParams) = lifecycle.didClose(params)

    override fun didSave(params: DidSaveTextDocumentParams) = lifecycle.didSave(params)

    override fun completion(params: CompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>> =
        lifecycle.withEditorAnalysis(params.textDocument.uri, Either.forLeft(emptyList())) { analysis ->
            completion.completion(params, analysis)
        }

    override fun hover(params: HoverParams): CompletableFuture<Hover> =
        lifecycle.withEditorAnalysis(params.textDocument.uri, null) { analysis ->
            hoverAndSignature.hover(params, analysis)
        }

    override fun signatureHelp(params: SignatureHelpParams): CompletableFuture<SignatureHelp> =
        lifecycle.withEditorAnalysis(params.textDocument.uri, null) { analysis ->
            hoverAndSignature.signatureHelp(params, analysis)
        }

    override fun prepareCallHierarchy(params: CallHierarchyPrepareParams): CompletableFuture<List<CallHierarchyItem>> =
        lifecycle.withEditorAnalysis(params.textDocument.uri, emptyList()) { analysis ->
            hierarchyAndRefactor.prepareCallHierarchy(params, analysis)
        }

    override fun callHierarchyIncomingCalls(
        params: CallHierarchyIncomingCallsParams
    ): CompletableFuture<List<CallHierarchyIncomingCall>> =
        lifecycle.onEditor {
            hierarchyAndRefactor.callHierarchyIncomingCalls(params)
        }

    override fun callHierarchyOutgoingCalls(
        params: CallHierarchyOutgoingCallsParams
    ): CompletableFuture<List<CallHierarchyOutgoingCall>> =
        lifecycle.onEditor {
            hierarchyAndRefactor.callHierarchyOutgoingCalls(params)
        }

    override fun prepareTypeHierarchy(params: TypeHierarchyPrepareParams): CompletableFuture<List<TypeHierarchyItem>> =
        lifecycle.withEditorAnalysis(params.textDocument.uri, emptyList()) { analysis ->
            hierarchyAndRefactor.prepareTypeHierarchy(params, analysis)
        }

    override fun typeHierarchySupertypes(
        params: TypeHierarchySupertypesParams
    ): CompletableFuture<List<TypeHierarchyItem>> =
        lifecycle.onEditor {
            hierarchyAndRefactor.typeHierarchySupertypes(params)
        }

    override fun typeHierarchySubtypes(
        params: TypeHierarchySubtypesParams
    ): CompletableFuture<List<TypeHierarchyItem>> =
        lifecycle.onEditor {
            hierarchyAndRefactor.typeHierarchySubtypes(params)
        }

    override fun definition(params: DefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> =
        lifecycle.withEditorAnalysis(params.textDocument.uri, Either.forLeft(emptyList())) { analysis ->
            navigation.definition(params, analysis)
        }

    override fun declaration(params: DeclarationParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> =
        lifecycle.withEditorAnalysis(params.textDocument.uri, Either.forLeft(emptyList())) { analysis ->
            navigation.declaration(params, analysis)
        }

    override fun references(params: ReferenceParams): CompletableFuture<List<Location>> =
        lifecycle.withEditorAnalysis(params.textDocument.uri, emptyList()) { analysis ->
            navigation.references(params, analysis)
        }

    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> =
        lifecycle.withEditorAnalysis(params.textDocument.uri, emptyList()) { analysis ->
            navigation.documentSymbol(analysis)
        }

    override fun typeDefinition(params: TypeDefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> =
        lifecycle.withEditorAnalysis(params.textDocument.uri, Either.forLeft(emptyList())) { analysis ->
            navigation.typeDefinition(params, analysis)
        }

    override fun implementation(params: ImplementationParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> =
        lifecycle.withEditorAnalysis(params.textDocument.uri, Either.forLeft(emptyList())) { analysis ->
            navigation.implementation(params, analysis)
        }

    override fun documentHighlight(params: DocumentHighlightParams): CompletableFuture<List<DocumentHighlight>> =
        lifecycle.withEditorAnalysis(params.textDocument.uri, emptyList()) { analysis ->
            navigation.documentHighlight(params, analysis)
        }

    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> =
        lifecycle.withEditorAnalysis(params.textDocument.uri, emptyList()) { analysis ->
            hierarchyAndRefactor.codeAction(params, analysis)
        }

    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit> =
        lifecycle.withEditorAnalysis(params.textDocument.uri, WorkspaceEdit()) { analysis ->
            hierarchyAndRefactor.rename(params, analysis)
        }

    override fun prepareRename(
        params: PrepareRenameParams
    ): CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> =
        lifecycle.withEditorAnalysis(params.textDocument.uri, null) { analysis ->
            hierarchyAndRefactor.prepareRename(params, analysis)
        }

    override fun foldingRange(params: FoldingRangeRequestParams): CompletableFuture<List<FoldingRange>> =
        lifecycle.withEditorAnalysis(params.textDocument.uri, emptyList()) { analysis ->
            hierarchyAndRefactor.foldingRange(analysis)
        }

    override fun selectionRange(params: SelectionRangeParams): CompletableFuture<List<SelectionRange>> =
        lifecycle.withEditorAnalysis(params.textDocument.uri, params.positions.map { SelectionRange(Range(it, it), null) }) { analysis ->
            hierarchyAndRefactor.selectionRange(params, analysis)
        }

    override fun linkedEditingRange(params: LinkedEditingRangeParams): CompletableFuture<LinkedEditingRanges> =
        lifecycle.withEditorAnalysis(params.textDocument.uri, LinkedEditingRanges(emptyList())) { analysis ->
            hierarchyAndRefactor.linkedEditingRange(params, analysis)
        }

    override fun formatting(params: DocumentFormattingParams): CompletableFuture<List<TextEdit>> =
        lifecycle.formatting(params)

    override fun rangeFormatting(params: DocumentRangeFormattingParams): CompletableFuture<List<TextEdit>> =
        lifecycle.rangeFormatting(params)

    override fun semanticTokensFull(params: SemanticTokensParams): CompletableFuture<SemanticTokens> =
        lifecycle.withEditorAnalysis(params.textDocument.uri, SemanticTokens(emptyList())) { analysis ->
            lifecycle.diagnosticsAndTokens.semanticTokensFull(analysis)
        }

    override fun semanticTokensRange(params: SemanticTokensRangeParams): CompletableFuture<SemanticTokens> =
        lifecycle.withEditorAnalysis(params.textDocument.uri, SemanticTokens(emptyList())) { analysis ->
            lifecycle.diagnosticsAndTokens.semanticTokensRange(params, analysis)
        }

    override fun inlayHint(params: InlayHintParams): CompletableFuture<List<InlayHint>> =
        lifecycle.withEditorAnalysis(params.textDocument.uri, emptyList()) { analysis ->
            hoverAndSignature.inlayHint(params, analysis)
        }

    fun workspaceSymbols(query: String = ""): List<MCFPPSymbol> =
        workspace.workspaceSymbols(query)

    fun workspaceSymbolsAsync(query: String): CompletableFuture<List<MCFPPSymbol>> =
        lifecycle.workspaceSymbolsAsync(query)

    fun setWorkspaceRoots(rootUris: List<String>) = lifecycle.setWorkspaceRoots(rootUris)

    fun updateWorkspaceRoots(addedUris: List<String>, removedUris: List<String>) =
        lifecycle.updateWorkspaceRoots(addedUris, removedUris)

    fun refreshProjectIndex() = lifecycle.refreshProjectIndex()

    fun refreshProjectIndex(changedUris: List<String>) = lifecycle.refreshProjectIndex(changedUris)

    fun updateMniSymbols(snapshot: MniSymbolSnapshot) = lifecycle.updateMniSymbols(snapshot)

    fun shutdown() = lifecycle.shutdown()
}
