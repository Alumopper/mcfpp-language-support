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
import kotlin.io.path.writeText
import kotlin.test.*

class MCFPPCompositionEditorTest {
    @TempDir lateinit var root: Path
    private fun position(source: String, needle: String): Position {
        val prefix = source.substring(0, source.indexOf(needle).also { assertTrue(it >= 0, needle) } + needle.length)
        return Position(prefix.count { it == '\n' }, prefix.substringAfterLast('\n').length)
    }
    private fun check(source: String, snapshot: top.mcfpp.language.MniSymbolSnapshot? = null, action: (TextDocumentService, String, List<Diagnostic>) -> Unit) {
        root.resolve("mcfpp.toml").writeText("schema=1\nnamespace=\"composition\"\nversion=\"26.3\"\ncompileArgs=[\"-ignoreStdLib\"]\n")
        val uri = root.resolve("main.mcfpp").toUri().toString()
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
        snapshot?.let(server::updateMniSymbols)
        try {
            val service = server.textDocumentService
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, source)))
            action(service, uri, assertNotNull(messages.poll(30, TimeUnit.SECONDS)).diagnostics)
        } finally { server.shutdown().get() }
    }
    private fun complete(service: TextDocumentService, uri: String, source: String, needle: String): Set<String> {
        val result = service.completion(CompletionParams(TextDocumentIdentifier(uri), position(source, needle))).get()
        return (result.left ?: result.right.items).map { it.label }.toSet()
    }
    @Test fun `new factories return complete objects and offer signatures`() {
        val source = """
            data Point {
                var x: int
                var y: int
                new(x: int, y: int) { return { x, y } }
            }
            func main() {
                var a: Point = Point(1, 2)
                var b: Point = Point.new(3, 4)
            }
        """.trimIndent()
        check(source) { service, uri, diagnostics ->
            assertTrue(diagnostics.none { it.severity == DiagnosticSeverity.Error }, diagnostics.joinToString("\n") { it.message })
            val help = service.signatureHelp(SignatureHelpParams(TextDocumentIdentifier(uri), position(source, "Point.new(3, "))).get()
            assertEquals(2, assertNotNull(help).signatures.first().parameters.size)
            assertContains(complete(service, uri, source, "Point."), "new")
        }
    }
    @Test fun `structural members merge intersections and restrict unions`() {
        val source = """
            data A { var common: int; var left: int }
            data B { var common: int; var right: string }
            func main(both: (A & B), either: (A | B), shape: { x: int, y: string }) {
                both.common
                either.common
                shape.x
            }
        """.trimIndent()
        check(source) { service, uri, diagnostics ->
            assertTrue(diagnostics.none { it.severity == DiagnosticSeverity.Error }, diagnostics.joinToString("\n") { it.message })
            assertEquals(setOf("common", "left", "right", "let", "also"), complete(service, uri, source, "both."))
            assertEquals(setOf("common", "let", "also"), complete(service, uri, source, "either."))
            assertEquals(setOf("x", "y", "let", "also"), complete(service, uri, source, "shape."))
        }
    }
    @Test fun `optional operations retain member types through assertions and fallbacks`() {
        val source = """
            data Child { var value: int }
            data Holder { var child: Child?; var title: string? }
            func main(h: Holder) {
                var a: int = h.child?.value ?: 0
                var b: string = h.title!!
            }
        """.trimIndent()
        check(source) { service, uri, diagnostics ->
            assertTrue(diagnostics.none { it.severity == DiagnosticSeverity.Error }, diagnostics.joinToString("\n") { it.message })
            assertEquals(setOf("value", "let", "also"), complete(service, uri, source, "h.child?."))
        }
    }
    @Test fun `literal contracts allow extra fields require initialized fields and accept shorthand`() {
        val source = """
            data Point { var x: int = 1; var y: int? }
            func main(x: int) {
                var a: Point = { x, extra: true }
                var b: Point = {}
            }
        """.trimIndent()
        check(source) { _, _, diagnostics ->
            val errors = diagnostics.filter { it.severity == DiagnosticSeverity.Error }
            assertEquals(1, errors.size, errors.joinToString("\n") { it.message })
            assertContains(errors.single().message, "Missing required field `x`")
        }
    }

    @Test fun `macro and MNI declarations and calls have distinct semantic tokens`() {
        val source = """
            namespace shading
            func expand(): int = example.Provider.expand
            func nativeValue(): int = example.Provider.nativeValue
            func ordinary(): int { return 0 }
            func main() { expand(); nativeValue(); ordinary() }
        """.trimIndent()
        val snapshot = top.mcfpp.language.MniSymbolSnapshot().apply {
            projectUri = root.toUri().toString(); generation = 1
            symbols = listOf(top.mcfpp.language.MniSymbolSnapshot.Symbol().apply {
                namespace = "shading"; name = "expand"; typeName = "int"; macro = true
                uri = root.resolve("Provider.kt").toUri().toString(); endCharacter = 6
                declarationUri = root.resolve("main.mcfpp").toUri().toString()
                declarationLine = 1
                declarationCharacter = 5
            })
        }
        check(source, snapshot) { service, uri, _ ->
            val tokens = service.semanticTokensFull(SemanticTokensParams(TextDocumentIdentifier(uri))).get().data
            var line = 0; var character = 0
            val classifications = mutableListOf<Pair<String, String>>()
            for (token in tokens.chunked(5)) {
                line += token[0]; character = if (token[0] == 0) character + token[1] else token[1]
                val name = source.lines()[line].substring(character, character + token[2])
                classifications += name to MCFPPSemanticTokenLegend.tokenTypes[token[3]]
            }
            assertEquals(listOf("macro", "macro"), classifications.filter { it.first == "expand" }.map { it.second })
            assertEquals(listOf("mniFunction", "mniFunction"), classifications.filter { it.first == "nativeValue" }.map { it.second })
            assertEquals(listOf("function", "function"), classifications.filter { it.first == "ordinary" }.map { it.second })
            val document = TextDocumentIdentifier(uri)
            val usage = position(source, "func main() { exp")
            val definition = service.definition(DefinitionParams(document, usage)).get().left.single()
            assertEquals(uri, definition.uri)
            assertEquals(Position(1, 5), definition.range.start)
            val implementation = service.implementation(ImplementationParams(document, usage)).get().left.single()
            assertEquals(root.resolve("Provider.kt").toUri().toString(), implementation.uri)
        }
    }

    @Test fun `runnable composition example infers factory results without false errors`() {
        val source = java.nio.file.Files.readString(Path.of("../examples/type-composition/src/main/mcfpp/main.mcfpp"))
        check(source) { service, uri, diagnostics ->
            assertTrue(diagnostics.none { it.severity == DiagnosticSeverity.Error }, diagnostics.joinToString("\n") { "${it.range}: ${it.message}" })
            assertTrue(complete(service, uri, source, "point.").containsAll(setOf("x", "y")))
            assertTrue(complete(service, uri, source, "marker.").containsAll(setOf("x", "y", "label")))
        }
    }

    @Test fun `macro modes are checked on source calls and callback formals`() {
        val source = """
            namespace modes
            func expand(number: int, output: int, callback: (int, int) -> void) = example.Provider.expand
            func ordinary(a: int, b: int) {}
            func main(runtime: int) {
                expand(runtime, runtime, ordinary)
                expand(1, runtime, { constant, writable -> constant = 2; writable = 3 })
            }
        """.trimIndent()
        fun parameter(name: String, type: String, declaredMode: String) =
            top.mcfpp.language.MniSymbolSnapshot.Parameter().apply {
                this.name = name; typeName = type; mode = declaredMode
            }
        val snapshot = top.mcfpp.language.MniSymbolSnapshot().apply {
            projectUri = root.toUri().toString()
            symbols = listOf(top.mcfpp.language.MniSymbolSnapshot.Symbol().apply {
                namespace = "modes"; name = "expand"; typeName = "void"; macro = true
                declarationUri = root.resolve("main.mcfpp").toUri().toString()
                declarationLine = 1; declarationCharacter = 5
                parameters = listOf(parameter("number", "int", "CONST"), parameter("output", "int", "REF"),
                    parameter("callback", "(int, int) -> void", "INLINE").apply {
                        macroCallbackParameters = listOf(parameter("a", "int", "CONST"), parameter("b", "int", "REF"))
                    })
            })
        }
        check(source, snapshot) { _, _, diagnostics ->
            val messages = diagnostics.map { it.message }
            assertTrue(messages.any { "requires a compile-time constant" in it }, messages.toString())
            assertEquals(2, messages.count { "compiler-issued REF location" in it }, messages.toString())
            assertTrue(messages.any { "requires a direct inline lambda" in it }, messages.toString())
            assertEquals(listOf("Cannot assign to const callback parameter `constant`"),
                messages.filter { "Cannot assign to const callback parameter" in it })
        }
    }

    @Test fun `receiver generic macros specialize callback signatures and preserve navigation`() {
        val source = """
            namespace generic
            func list<E>.map<E, R>(callback: (E) -> R): list<R> = example.Provider.map
            func main(items: list<int>) {
                var output = items.map({ item -> "converted" });
            }
        """.trimIndent()
        val callbackParameter = top.mcfpp.language.MniSymbolSnapshot.Parameter().apply {
            name = "callback"; typeName = "(E) -> R"; mode = "INLINE"
            macroCallbackParameters = listOf(top.mcfpp.language.MniSymbolSnapshot.Parameter().apply {
                name = "item"; typeName = "E"; mode = "VALUE"
            })
        }
        val snapshot = top.mcfpp.language.MniSymbolSnapshot().apply {
            projectUri = root.toUri().toString(); generation = 2
            symbols = listOf(top.mcfpp.language.MniSymbolSnapshot.Symbol().apply {
                namespace = "generic"; name = "map"; receiverType = "list<E>"; typeParameters = listOf("E", "R")
                typeName = "list<R>"; detail = "map(callback: (E) -> R): list<R>"; macro = true
                declarationUri = root.resolve("main.mcfpp").toUri().toString()
                declarationLine = 1; declarationCharacter = source.lines()[1].indexOf("map")
                uri = root.resolve("Provider.kt").toUri().toString(); endCharacter = 3
                parameters = listOf(callbackParameter)
            })
        }
        check(source, snapshot) { service, uri, diagnostics ->
            assertTrue(diagnostics.none { it.severity == DiagnosticSeverity.Error },
                diagnostics.joinToString("\n") { "${it.range}: ${it.message}" })
            assertTrue(complete(service, uri, source, "items.").contains("map"))
            val signature = service.signatureHelp(SignatureHelpParams(TextDocumentIdentifier(uri),
                position(source, """items.map({ item -> "converted" }"""))).get()?.signatures?.first()?.label.orEmpty()
            assertTrue(signature.replace(" ", "").contains("(int)->string"), signature)
            assertTrue(signature.contains("list<string>"), signature)
            val document = TextDocumentIdentifier(uri)
            val usage = position(source, "items.map")
            assertEquals(uri, service.definition(DefinitionParams(document, usage)).get().left.single().uri)
            assertEquals(root.resolve("Provider.kt").toUri().toString(),
                service.implementation(ImplementationParams(document, usage)).get().left.single().uri)
            val hover = service.hover(HoverParams(document, usage)).get()
            assertContains(hover.contents.right.value, "list<int>")
        }
    }

    @Test fun `import aliases expose receiver generic macro completions`() {
        val source = """
            namespace caller
            import generic:map as transform
            func main(items: list<int>) {
                var output = items.transform({ item -> "converted" });
            }
        """.trimIndent()
        val callback = top.mcfpp.language.MniSymbolSnapshot.Parameter().apply {
            name = "callback"; typeName = "(E) -> R"; mode = "INLINE"
            macroCallbackParameters = listOf(top.mcfpp.language.MniSymbolSnapshot.Parameter().apply {
                name = "item"; typeName = "E"; mode = "VALUE"
            })
        }
        val snapshot = top.mcfpp.language.MniSymbolSnapshot().apply {
            projectUri = root.toUri().toString(); generation = 3
            symbols = listOf(top.mcfpp.language.MniSymbolSnapshot.Symbol().apply {
                namespace = "generic"; name = "map"; receiverType = "list<E>"; typeParameters = listOf("E", "R")
                typeName = "list<R>"; macro = true; parameters = listOf(callback)
                declarationUri = root.resolve("api.mcfpp").toUri().toString()
                uri = root.resolve("Provider.kt").toUri().toString()
            })
        }
        check(source, snapshot) { service, uri, diagnostics ->
            assertTrue(diagnostics.none { it.severity == DiagnosticSeverity.Error }, diagnostics.joinToString("\n") { it.message })
            assertTrue(complete(service, uri, source, "items.").contains("transform"))
            val help = service.signatureHelp(SignatureHelpParams(TextDocumentIdentifier(uri),
                position(source, """items.transform({ item -> "converted" }"""))).get()
            assertContains(assertNotNull(help).signatures.first().label, "(int) -> string")
        }
    }
}
