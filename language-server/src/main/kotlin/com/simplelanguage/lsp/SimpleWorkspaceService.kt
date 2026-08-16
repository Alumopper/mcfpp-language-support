package com.simplelanguage.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.concurrent.CompletableFuture

class SimpleWorkspaceService(private val server: SimpleLanguageServer) : WorkspaceService {
    
    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
        // 处理配置变更
    }
    
    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        // 处理文件变更
    }
    
    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> {
        return CompletableFuture.completedFuture(null)
    }
    
    override fun symbol(params: WorkspaceSymbolParams): CompletableFuture<Either<List<SymbolInformation>, List<WorkspaceSymbol>>> {
        return CompletableFuture.completedFuture(Either.forLeft(emptyList()))
    }
}