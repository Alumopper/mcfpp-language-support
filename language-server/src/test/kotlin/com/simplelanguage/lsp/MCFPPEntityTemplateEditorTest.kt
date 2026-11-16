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

class MCFPPEntityTemplateEditorTest {
    @TempDir lateinit var root: Path

    private fun position(source: String, needle: String, after: String = needle): Position {
        val offset = source.indexOf(needle).also { assertTrue(it >= 0, needle) } + needle.indexOf(after) + after.length
        val prefix = source.substring(0, offset)
        return Position(prefix.count { it == '\n' }, prefix.substringAfterLast('\n').length)
    }

    private fun check(
        source: String,
        action: (org.eclipse.lsp4j.services.TextDocumentService, String, List<Diagnostic>) -> Unit
    ) {
        root.resolve("mcfpp.toml").writeText("schema=1\nname=\"templates\"\nnamespace=\"templates\"\nversion=\"1.21.8\"\n")
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
    fun `entity templates expose template and reference members separately`() {
        val source = """
            import mcfpp.minecraft.entity:*

            entity StoneDisplay: BlockDisplay {
                var initialCount: int = 10
                entity var count: int = 0

                constructor() {}

                entity func consume() {
                    this.count -= 1
                }
            }

            func main() {
                var template = StoneDisplay()
                template.initialCount = 20
                var ref = template.summon()
                var refWithData = template.summon({foo: 1})
                ref.consume()
                var remaining = ref.count
                var present = ref.exists()
                ref.checkType<BlockDisplay>()
            }
        """.trimIndent()
        check(source) { service, uri, diagnostics ->
            assertTrue(diagnostics.none { it.severity == DiagnosticSeverity.Error },
                diagnostics.joinToString("\n") { "${it.range}: ${it.message}" })
            fun completion(needle: String, after: String): Set<String> {
                val items = service.completion(CompletionParams(TextDocumentIdentifier(uri), position(source, needle, after))).get()
                return (items.left ?: items.right.items).map { it.label }.toSet()
            }
            val templateMembers = completion("template.initialCount", "template.")
            assertTrue(templateMembers.containsAll(listOf("initialCount", "summon")), templateMembers.toString())
            assertFalse("count" in templateMembers || "consume" in templateMembers, templateMembers.toString())

            val refMembers = completion("ref.consume", "ref.")
            assertTrue(refMembers.containsAll(listOf("count", "consume", "exists", "checkType", "blockState", "transform")),
                refMembers.toString())
            assertFalse("initialCount" in refMembers || "summon" in refMembers, refMembers.toString())

            val typeArguments = completion("ref.checkType<BlockDisplay>", "ref.checkType<")
            assertTrue("StoneDisplay" in typeArguments, typeArguments.toString())

            val definition = service.definition(
                DefinitionParams(TextDocumentIdentifier(uri), position(source, "ref.count", "ref.coun"))
            ).get()
            assertEquals("count", source.substring(
                offset(source, definition.left.single().range.start),
                offset(source, definition.left.single().range.end)
            ))
        }
    }

    private fun offset(source: String, position: Position): Int =
        source.split("\n").take(position.line).sumOf { it.length + 1 } + position.character
}
