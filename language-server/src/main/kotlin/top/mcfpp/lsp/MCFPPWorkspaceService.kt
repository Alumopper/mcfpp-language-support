package top.mcfpp.lsp

import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture

class MCFPPWorkspaceService(private val server: MCFPPLanguageServer) : WorkspaceService {
    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
        server.getMCFPPTextDocumentService().refreshProjectIndex()
    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        server.getMCFPPTextDocumentService().refreshProjectIndex(params.changes.map { it.uri })
    }

    override fun didChangeWorkspaceFolders(params: DidChangeWorkspaceFoldersParams) {
        server.getMCFPPTextDocumentService().updateWorkspaceRoots(
            addedUris = params.event.added.map { it.uri },
            removedUris = params.event.removed.map { it.uri }
        )
    }

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> {
        return CompletableFuture.completedFuture(null)
    }

    override fun symbol(params: WorkspaceSymbolParams): CompletableFuture<Either<List<SymbolInformation>, List<WorkspaceSymbol>>> {
        return server.getMCFPPTextDocumentService().workspaceSymbolsAsync(params.query)
            .thenApply { symbols -> Either.forRight(symbols.map { it.toWorkspaceSymbol() }) }
    }
}
