package top.mcfpp.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.*

class MCFPPTemplateLiteralEditorTest {
    @TempDir lateinit var root: Path
    private val declarations = """
        data Base {
            /// Stable identifier.
            var id: int
            var tag: int? = 5
        }
        data Inner: Base { var label: string? = "ok" }
        data Box<T> { var value: T }
        data Outer {
            var inner: Inner
            var items: list<Inner>? = []
            var optional: int?
            var boxed: Box<int>? = { value: 9 }
            private var secret: int? = 2
        }
        func take(value: Outer) {}
        func pick(value: int) {}
        func pick(value: Inner) {}
        func generic<T>(value: Box<T>) {}
    """.trimIndent()

    private fun position(source: String, needle: String, delta: Int = needle.length): Position {
        val offset = source.indexOf(needle).also { assertTrue(it >= 0, needle) } + delta
        val prefix = source.substring(0, offset)
        return Position(prefix.count { it == '\n' }, prefix.substringAfterLast('\n').length)
    }

    private fun check(source: String, stdlib: Boolean = false, action: (TextDocumentService, String, List<Diagnostic>) -> Unit) {
        root.resolve("mcfpp.toml").writeText("schema=1\nnamespace=\"literals\"\nversion=\"26.3\"\n" + if (stdlib) "" else "compileArgs=[\"-ignoreStdLib\"]\n")
        val uri = root.resolve("src/main/mcfpp/main.mcfpp").toUri().toString()
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
            action(service, uri, assertNotNull(messages.poll(30, TimeUnit.SECONDS), "diagnostics not published").diagnostics)
        } finally { server.shutdown().get() }
    }

    private fun completions(service: TextDocumentService, uri: String, source: String, needle: String): List<CompletionItem> {
        val result = service.completion(CompletionParams(TextDocumentIdentifier(uri), position(source, needle))).get()
        return result.left ?: result.right.items
    }

    @Test fun `variables parameters nested templates lists and function values accept contextual literals`() {
        val source = declarations + "\n" + """

            func main() {
                var local: Outer = { inner: { id: 1 }, items: [{ id: 2 }] }
                take({ inner: { id: 3 }, boxed: { value: 8 } })
                take(value = { inner: { id: 4 } })
                pick({ id: 5 })
                var fn: (Outer) -> void = take
                fn({ inner: { id: 6 } })
                local = { inner: { id: 7 } }
                var id: int = 8
                take({ inner: { id: id + 1 } })
                generic<int>({ value: 1 })
            }
        """.trimIndent()
        check(source) { service, uri, diagnostics ->
            assertTrue(diagnostics.none { it.severity == DiagnosticSeverity.Error }, diagnostics.joinToString("\n") { "${it.range}: ${it.message}" })
            val fields = completions(service, uri, source, "var local: Outer = { ").map { it.label }.toSet()
            assertEquals(setOf("inner", "optional", "boxed"), fields)
            val nested = completions(service, uri, source, "inner: { ")
            assertTrue(nested.any { it.label == "tag" }, nested.toString())
            assertFalse(nested.any { it.label in setOf("secret", "func") })
            assertTrue(completions(service, uri, source, "take({ ").any { it.label == "items" })
            assertTrue(completions(service, uri, source, "items: [{ ").any { it.label == "label" })
            assertContains(completions(service, uri, source, "boxed: { ").single().detail, "int")
            assertContains(completions(service, uri, source, "generic<int>({ ").single().detail, "int")
            val hover = service.hover(HoverParams(TextDocumentIdentifier(uri), position(source, "id: 3", 1))).get()
            assertContains(assertNotNull(hover).contents.right.value, "Stable identifier")
            assertContains(hover.contents.right.value, "int")
        }
    }

    @Test fun `incomplete literals complete inherited fields with docs and a colon snippet`() {
        val source = declarations + "\nfunc main() { take({ inner: { i"
        check(source) { service, uri, _ ->
            val items = completions(service, uri, source, "take({ inner: { i")
            val id = items.single { it.label == "id" }
            assertEquals("id: \${1}", id.textEdit.left.newText)
            assertEquals(InsertTextFormat.Snippet, id.insertTextFormat)
            assertContains(id.documentation.right.value, "Stable identifier")
            assertFalse(items.any { it.label == "secret" || it.kind == CompletionItemKind.Keyword })
        }
    }

    @Test fun `chained template members use the final receiver type without same named stdlib members`() {
        val source = """
            data Identity { var id: int\nvar label: string = "unnamed" }
            data Profile: Identity { var score: int = 0 }
            data Team { var leader: Profile\nvar members: list<Profile> = [] }
            func main() {
                var team: Team = { leader: { id: 1 } }
                team.leader.
            }
        """.trimIndent().replace("\\n", "\n")
        check(source, stdlib = true) { service, uri, _ ->
            val items = completions(service, uri, source, "team.leader.").map { it.label }.toSet()
            assertTrue(items.containsAll(setOf("id", "label", "score")), items.toString())
            assertTrue(items.intersect(setOf("leader", "members", "register", "unregister", "displayName")).isEmpty(), items.toString())
            val direct = completions(service, uri, source, "team.").map { it.label }.toSet()
            assertTrue(direct.containsAll(setOf("leader", "members")), direct.toString())
            assertTrue(direct.intersect(setOf("id", "register", "unregister", "displayName")).isEmpty(), direct.toString())
        }
    }

    @Test fun `structure errors report missing unknown duplicate inaccessible and wrong field types`() {
        val source = declarations + "\n" + """

            abstract data AbstractBox { var x: int = 1 }
            data PrivateBox { var x: int; private new(x: int) { return { x } } }
            func main() {
                var a: Outer = { inner: { id: "bad", typo: 2 }, secret: 4 }
                take({ boxed: { value: "bad" }, unknown: 1 })
                var b: Inner = { id: 1, id: 2 }
                var c: Inner = { id: null }
                var d: AbstractBox = {}
                var e: PrivateBox = { x: 1 }
                var xs: list<Inner> = [{ typo: 1 }]
            }
        """.trimIndent()
        check(source) { _, _, diagnostics ->
            val messages = diagnostics.filter { it.code?.left == "mcfpp.template-literal" }.map { it.message }
            for (fragment in listOf("Missing required field `inner`",
                "Duplicate field `id`", "not accessible", "expected `int`, found `string`", "found `null`",
                "cannot be instantiated")) {
                assertTrue(messages.any { fragment in it }, "$fragment\n${diagnostics.joinToString("\n") { it.message }}")
            }
            val wrongKey = diagnostics.first { it.message.startsWith("Field `secret`") }
            assertEquals("secret", source.substring(MCFPPTextChanges.positionToOffset(source, wrongKey.range.start),
                MCFPPTextChanges.positionToOffset(source, wrongKey.range.end)))
        }
    }

    @Test fun `imported templates aliases defaults and raw NBT retain their behavior`() {
        val library = root.resolve("src/main/mcfpp/library.mcfpp")
        library.parent.createDirectories()
        library.writeText("namespace models\ndata Imported {\n/// Imported name.\nvar name: string\nvar count: int? = 1 }")
        val source = """
            import models:Imported as Model
            typealias Model as Alias
            func consume(value: Alias) {}
            func main() {
                var x: Model = { name: "ok" }
                consume({ name: "other" })
                var raw: nbt = { arbitrary: { anything: 1 } }
            }
        """.trimIndent()
        check(source) { service, uri, diagnostics ->
            assertTrue(diagnostics.none { it.severity == DiagnosticSeverity.Error }, diagnostics.joinToString("\n") { it.message })
            assertEquals(setOf("count", "name"), completions(service, uri, source, "var x: Model = { ").map { it.label }.toSet())
            assertEquals(setOf("count", "name"), completions(service, uri, source, "consume({ ").map { it.label }.toSet())
            val hover = service.hover(HoverParams(TextDocumentIdentifier(uri), position(source, "name: \"ok\"", 2))).get()
            assertContains(assertNotNull(hover).contents.right.value, "Imported name")
        }
    }

    @Test fun `stdlib command arguments and display setter literals use their declared data schemas`() {
        val source = """
            import mcfpp.minecraft.std:*
            import mcfpp.minecraft.entity:*
            func main() {
                fillOutline({ start: 0 64 0, end: 1 65 1 }, { name: "minecraft:stone", properties: {} })
                const display = entityByUUID<ItemDisplay>("0-0-0-0-1")
                display.brightness = { sky: 15, block: 0 }
            }
        """.trimIndent()
        check(source, stdlib = true) { service, uri, diagnostics ->
            assertTrue(diagnostics.none { it.severity == DiagnosticSeverity.Error }, diagnostics.joinToString("\n") { it.message })
            assertTrue(completions(service, uri, source, "fillOutline({ ").any { it.label == "start" })
            assertTrue(completions(service, uri, source, "}, { ").any { it.label == "name" })
            assertEquals(setOf("sky"), completions(service, uri, source, "display.brightness = { ").map { it.label }.toSet())
        }
    }
}
