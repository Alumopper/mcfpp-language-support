package com.simplelanguage.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.writeText
import kotlin.test.*

class MCFPPNestedScopeEditorTest {
    @TempDir lateinit var root: Path

    @Test fun `outer locals remain visible in loops and lambda hints show function types`() {
        root.resolve("mcfpp.toml").writeText("schema=1\nname=\"nested-scope\"\nnamespace=\"scope.tests\"\nversion=\"1.21.8\"\n")
        val uri = root.resolve("src/main/mcfpp/main.mcfpp").toUri().toString()
        val source = """
            func main() {
                var n: int = 0
                var f = { a: int -> 1 }
                for (i : 1..5) {
                    n += i
                    print(f(i))
                    if (i > 1) {
                        var n: string = "inner"
                        print(n)
                    }
                }
                print(n)
                print(i)
            }
        """.trimIndent()
        val received = CountDownLatch(1)
        val diagnostics = AtomicReference<List<Diagnostic>>(emptyList())
        val server = SimpleLanguageServer()
        server.connect(object : LanguageClient {
            override fun telemetryEvent(value: Any?) {}
            override fun publishDiagnostics(value: PublishDiagnosticsParams) {
                if (value.uri == uri) { diagnostics.set(value.diagnostics); received.countDown() }
            }
            override fun showMessage(value: MessageParams) {}
            override fun showMessageRequest(value: ShowMessageRequestParams): CompletableFuture<MessageActionItem> = CompletableFuture.completedFuture(null)
            override fun logMessage(value: MessageParams) {}
        })
        server.initialize(InitializeParams().apply { rootUri = root.toUri().toString() }).get()
        try {
            server.textDocumentService.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, source)))
            assertTrue(received.await(20, TimeUnit.SECONDS))
            assertEquals(listOf("Undefined symbol `i`"), diagnostics.get()
                .filter { it.severity == DiagnosticSeverity.Error }.map { it.message })

            for ((line, declarationLine) in listOf(4 to 1, 8 to 7, 11 to 1)) {
                val offset = source.lines()[line].lastIndexOf('n')
                val target = server.textDocumentService.definition(
                    DefinitionParams(TextDocumentIdentifier(uri), Position(line, offset))
                ).get().left.single()
                assertEquals(declarationLine, target.range.start.line, "Wrong n declaration at line $line")
            }
            val completions = server.textDocumentService.completion(
                CompletionParams(TextDocumentIdentifier(uri), Position(4, 0))
            ).get()
            val labels = if (completions.isLeft) completions.left.map { it.label } else completions.right.items.map { it.label }
            assertTrue("n" in labels && "f" in labels, labels.toString())
            assertFalse("a" in labels, "Lambda parameters must stay inside the lambda")
            val hints = server.textDocumentService.inlayHint(InlayHintParams(
                TextDocumentIdentifier(uri), Range(Position(0, 0), Position(14, 0))
            )).get()
            val lambdaHint = hints.single { it.position == Position(2, source.lines()[2].indexOf("f =") + 1) }
            assertEquals(": (int) -> int", lambdaHint.label.left)
        } finally { server.shutdown().get() }
    }
}
