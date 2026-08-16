package com.simplelanguage.lsp

import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.jsonrpc.Launcher
import java.io.InputStream
import java.io.OutputStream

fun main() {
    // 创建LSP服务器实例
    val server = SimpleLanguageServer()
    
    // 创建Launcher.Builder
    val launcherBuilder = Launcher.Builder<LanguageClient>()
        .setLocalService(server)
        .setRemoteInterface(LanguageClient::class.java)
        .setInput(System.`in` as InputStream)
        .setOutput(System.`out` as OutputStream)
    
    // 构建Launcher
    val launcher = launcherBuilder.create()
    
    // 连接客户端
    server.connect(launcher.remoteProxy)
    
    // 启动服务器
    val future = launcher.startListening()
    
    // 等待服务器结束
    future.get()
}
