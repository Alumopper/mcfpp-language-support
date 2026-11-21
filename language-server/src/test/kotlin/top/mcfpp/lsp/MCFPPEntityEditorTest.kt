package top.mcfpp.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.*

class MCFPPEntityEditorTest {
    @TempDir lateinit var root: Path

    @Test fun `editor entity kinds match the compiler registry`() {
        assertEquals(top.mcfpp.type.EntityTypes.all.map { it.typeName }.toSet(), top.mcfpp.language.EntityBuiltins.TYPES.toSet())
    }

    @Test fun `bounce example has no false errors and display chains complete`() {
        root.resolve("mcfpp.toml").writeText("schema=1\nname=\"display-editor\"\nnamespace=\"display.editor\"\nversion=\"1.21.8\"\n")
        val uri = root.resolve("src/main/mcfpp/main.mcfpp").toUri().toString()
        val source = "import mcfpp.minecraft.entity:*\n" + Files.readString(Path.of("../../MCFPP/examples/display-animation/src/main.mcfpp")) + """

            func edit(display: ItemDisplay, label: TextDisplay) {
                const base: entity = display
                display.transform.scale.x = 2.0
                display.billboard = DisplayBillboard.center
                var scale = DisplayVector3()
                scale.x = 2.0
                display.transform.scale = scale
                label.text = "hello"
                const height = 4.0
                const copy = height
                base.checkType<ItemDisplay>()
            }
        """.trimIndent()
        val messages = LinkedBlockingQueue<PublishDiagnosticsParams>()
        val server = MCFPPLanguageServer()
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
            val errors = assertNotNull(messages.poll(30, TimeUnit.SECONDS)).diagnostics.filter { it.severity == DiagnosticSeverity.Error }
            assertTrue(errors.isEmpty(), errors.joinToString("\n") { it.message })
            fun position(needle: String, after: String): Position {
                val index = source.indexOf(needle)
                assertTrue(index >= 0, needle)
                val offset = index + needle.indexOf(after) + after.length
                val before = source.substring(0, offset)
                return Position(before.count { it == '\n' }, before.substringAfterLast('\n').length)
            }
            fun completion(needle: String, after: String): Set<String> {
                val items = service.completion(CompletionParams(TextDocumentIdentifier(uri), position(needle, after))).get()
                return (items.left ?: items.right.items).map { it.label }.toSet()
            }
            assertTrue(completion("blockDisplay.transform.y", "blockDisplay.").containsAll(listOf("transform", "item", "exists", "checkType")))
            val transform = completion("blockDisplay.transform.y", "transform.")
            assertTrue(transform.containsAll(listOf("x", "y", "z", "scale", "leftRotation", "rightRotation")))
            assertFalse(transform.any { it in listOf("item", "ItemDisplay", "namespace") }, transform.toString())
            assertTrue(completion("display.transform.scale.x", "scale.").containsAll(listOf("x", "y", "z")))
            assertTrue(completion("label.text", "label.").containsAll(listOf("text", "alignment", "background", "textOpacity")))
            val values = completion("const copy = height", "const copy = ")
            assertTrue("height" in values, values.toString())
            assertFalse(values.any { it in listOf("ItemDisplay", "Zombie", "namespace", "func", "int") }, values.toString())
            val entityTypes = completion("base.checkType<ItemDisplay>", "base.checkType<")
            assertTrue(entityTypes.containsAll(listOf("ItemDisplay", "TextDisplay", "Zombie")), entityTypes.toString())
            assertFalse("height" in entityTypes, entityTypes.toString())
            val help = service.signatureHelp(SignatureHelpParams(TextDocumentIdentifier(uri), position("loop(10)", "loop("))).get()
            assertTrue(help.signatures.any { "interval" in it.label && "bool" in it.label }, help.toString())
            val bad = source + "\nfunc bad(display: ItemDisplay) { display.transform.y = \"wrong\" }\n"
            service.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(uri, 2), listOf(TextDocumentContentChangeEvent(bad))))
            val invalid = assertNotNull(messages.poll(30, TimeUnit.SECONDS)).diagnostics
            assertTrue(invalid.any { "float" in it.message && "string" in it.message }, invalid.toString())
        } finally { server.shutdown().get() }
    }
}
