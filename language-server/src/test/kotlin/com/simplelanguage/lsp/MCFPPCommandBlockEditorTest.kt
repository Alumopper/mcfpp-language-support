package com.simplelanguage.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.*

class MCFPPCommandBlockEditorTest {
    @TempDir lateinit var root: Path

    private fun check(source: String, action: (org.eclipse.lsp4j.services.TextDocumentService, String, List<Diagnostic>) -> Unit) {
        root.resolve("mcfpp.toml").writeText("schema=1\nname=\"commands\"\nnamespace=\"commands\"\nversion=\"1.21.8\"\n")
        val uri = root.resolve("src/main/mcfpp/main.mcfpp").toUri().toString()
        val messages = LinkedBlockingQueue<PublishDiagnosticsParams>()
        val server = SimpleLanguageServer()
        server.connect(object : LanguageClient {
            override fun telemetryEvent(value: Any?) {}
            override fun publishDiagnostics(value: PublishDiagnosticsParams) { if (value.uri == uri) messages.offer(value) }
            override fun showMessage(value: MessageParams) {}
            override fun showMessageRequest(value: ShowMessageRequestParams): CompletableFuture<MessageActionItem> = CompletableFuture.completedFuture(null)
            override fun logMessage(value: MessageParams) {}
        })
        server.initialize(InitializeParams().apply { rootUri = root.toUri().toString() }).get()
        try {
            val service = server.textDocumentService
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, source)))
            action(service, uri, assertNotNull(messages.poll(30, TimeUnit.SECONDS)).diagnostics)
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `command blocks parse interpolation and ordinary statements`() {
        val source = """
            func commands() {
                var count = 1
                /{
                  say hello
                  /count += 1
                  say count: ${'$'}{count}
                  /if count > 0 {
                    /say inside ordinary MCFPP block
                  }
                }
                /say done ${'$'}{count}
            }
        """.trimIndent()
        check(source) { service, uri, diagnostics ->
            assertTrue(diagnostics.none { it.severity == DiagnosticSeverity.Error },
                diagnostics.joinToString("\n") { "${it.range}: ${it.message}" })
            val lines = source.split("\n")
            val interpolationLine = lines.indexOfFirst { "${'$'}{count}" in it }
            assertTrue(interpolationLine >= 0, source)
            val definitions = service.definition(
                DefinitionParams(TextDocumentIdentifier(uri),
                    Position(interpolationLine, lines[interpolationLine].indexOf("count}") + 2))
            ).get()
            assertEquals(1, definitions.left.size, definitions.toString())
            assertEquals(1, definitions.left.single().range.start.line)
        }
    }

    @Test
    fun `command block lines are indented and the closing brace returns to the outer level`() {
        val source = "func commands() {\n/{\nsay hello\n/if true {\n/say nested\n}\n}\n}\n"
        val edits = MCFPPFormatter.format(source, FormattingOptions(4, true))
        val formatted = applyEdits(source, edits)
        assertEquals(
            "func commands() {\n    /{\n        say hello\n        /if true {\n            /say nested\n        }\n    }\n}\n",
            formatted
        )
    }

    private fun applyEdits(source: String, edits: List<TextEdit>): String {
        val lines = source.split("\n").toMutableList()
        edits.sortedByDescending { it.range.start.line }.forEach { edit ->
            val line = lines[edit.range.start.line]
            lines[edit.range.start.line] = line.substring(0, edit.range.start.character) +
                edit.newText + line.substring(edit.range.end.character)
        }
        return lines.joinToString("\n")
    }
}
