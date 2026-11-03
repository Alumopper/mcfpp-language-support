package com.simplelanguage.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class MCFPPFunctionValueEditorTest {
    @TempDir lateinit var root: Path

    @Test fun `lambda and function variables resolve calls and definitions`() {
        root.resolve("mcfpp.toml").writeText("schema=1\nname=\"function-values\"\nnamespace=\"function.values\"\nversion=\"1.21.8\"\n")
        val uri = root.resolve("src/main/mcfpp/function-values.mcfpp").toUri().toString()
        val source = """
            namespace function.values
            func foooo(value: int): int { return value; }
            func invoke(callback: (int) -> int): int { return callback(1); }
            func main() {
                var f = { a: int -> 1 }
                print(f(1))
                f = foooo
                print(f(2))
                var reference: (int) -> int = foooo
                print(reference(3))
                var typed: (int) -> int = foooo
                print(typed(4))
                var returned = { a: int -> return 1 }
                f = returned
                missingFunction(1)
                f = incompatible
            }
            func incompatible(value: string) {}
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
            assertEquals(listOf("Undefined symbol `missingFunction`"), diagnostics.get()
                .filter { it.code?.left == "mcfpp.undefined-symbol" }.map { it.message })
            val errors = diagnostics.get().filter { it.severity == DiagnosticSeverity.Error }.map { it.message }
            assertEquals(2, errors.size, errors.toString())
            assertTrue(errors.any { it == "Type mismatch for `f`: expected `(int)->int`, found `(string)->void`" }, errors.toString())
            assertFalse(diagnostics.get().any { it.source == "mcfpp-parser" }, diagnostics.get().toString())
            val calls = listOf(5 to "f", 7 to "f", 9 to "reference", 11 to "typed")
            for ((line, name) in calls) {
                val character = source.lines()[line].indexOf("$name(")
                val target = server.textDocumentService.definition(
                    DefinitionParams(TextDocumentIdentifier(uri), Position(line, character))
                ).get().left.single()
                assertEquals(uri, target.uri)
                assertEquals(if (name == "f") 4 else line - 1, target.range.start.line)
                val help = server.textDocumentService.signatureHelp(
                    SignatureHelpParams(TextDocumentIdentifier(uri), Position(line, character + name.length + 1))
                ).get()
                assertTrue(help.signatures.any { it.label.contains("int") }, "$name: ${help.signatures}")
            }
        } finally { server.shutdown().get() }
    }
}
