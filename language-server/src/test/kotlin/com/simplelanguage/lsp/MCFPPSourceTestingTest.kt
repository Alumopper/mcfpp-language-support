package com.simplelanguage.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.*

class MCFPPSourceTestingTest {
    @TempDir lateinit var root: Path

    private fun project(): Path {
        root.resolve("mcfpp.toml").writeText("schema=1\nname=\"source-tests\"\nnamespace=\"example\"\nversion=\"1.21.8\"\n")
        root.resolve("src/main/mcfpp").createDirectories().resolve("math.mcfpp")
            .writeText("func add(left: int, right: int): int { return left + right; }\n")
        return root.resolve("src/test/mcfpp/numbers").createDirectories().resolve("math_test.mcfpp")
            .also { it.writeText("import mcfpp.test:*\n@Test\nfunc testNumbers() { assertNotEquals(1, 2); }\n") }
    }

    @Test fun `test sources and bundled assertions enter the editor index`() {
        val test = project()
        val index = MCFPPProjectIndex()
        index.setWorkspaceRoots(listOf(root.toUri().toString()))
        val snapshot = index.refresh(emptyMap())
        assertEquals("example.numbers", index.namespaceForUri(test.toUri().toString()))
        assertEquals("1.21.8", index.targetVersionForUri(test.toUri().toString()))
        assertEquals("example.numbers", snapshot.fileAnalyses.getValue(test.toUri().toString()).namespaceName)
        val assertion = snapshot.librarySymbols.single { it.namespaceName == "mcfpp.test" && it.name == "assertNotEquals" }
        assertEquals(listOf("expected", "actual", "message"), assertion.parameters.map { it.name })
        assertTrue(assertion.parameters.last().hasDefault)
        assertTrue(assertion.isExternal)
        assertFalse(assertion.isStdlib, "Test functions require a normal mcfpp.test import")
    }

    @Test fun `test assertions resolve without hiding unknown symbols`() {
        val test = project()
        val uri = test.toUri().toString()
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
        val source = """
            import mcfpp.test:*
            import example:add
            @Test
            func testNumbers() {
                assertNotEquals(0, add(1, 2))
                assertEquals(3, add(1, 2))
                assertTrue(true)
                assertFalse(false)
                assertNotEqulas(1, 2)
            }
        """.trimIndent()
        try {
            server.textDocumentService.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, source)))
            assertTrue(received.await(20, TimeUnit.SECONDS), "No diagnostics received for the test source")
            val undefined = diagnostics.get().filter { it.code?.left == "mcfpp.undefined-symbol" }.map { it.message }
            assertEquals(listOf("Undefined symbol `assertNotEqulas`"), undefined)
            val completions = server.textDocumentService.completion(
                CompletionParams(TextDocumentIdentifier(uri), Position(4, "    assertNot".length))
            ).get()
            val labels = if (completions.isLeft) completions.left.map { it.label } else completions.right.items.map { it.label }
            assertTrue("assertNotEquals" in labels, "Completions: $labels")
            val declaration = server.textDocumentService.definition(
                DefinitionParams(TextDocumentIdentifier(uri), Position(4, 8))
            ).get().left.single()
            assertTrue(declaration.uri.endsWith("/testing/assertions.mcfpp"), declaration.uri)
        } finally { server.shutdown().get() }
    }
}
