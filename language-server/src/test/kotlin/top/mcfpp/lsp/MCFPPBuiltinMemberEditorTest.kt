package top.mcfpp.lsp

import org.eclipse.lsp4j.*
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class MCFPPBuiltinMemberEditorTest {
    private val source = """
        func main() {
            var values = [1, 2]
            print(values.fold(0) { sum, value -> sum + value })
            var label = " MCFPP ".trim().lowercase()
            var ref = nbtStorageRef<list<int>>("test:values", "source")
            ref.tryRead { input ->
                print(input)
            }
        }
    """.trimIndent()

    @Test fun editorRecoveryRetainsReceiverAndLexicalCallbackScope() = withServer { server, document ->
        val service = server.textDocumentService
        fun check(text: String, marker: String, version: Int): Set<String> {
            service.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(document.uri, version),
                listOf(TextDocumentContentChangeEvent(text))))
            return service.completion(CompletionParams(document, position(text, marker))).get(10, TimeUnit.SECONDS)
                .left.mapTo(hashSetOf()) { it.label }
        }
        val dot = source.substringBefore("    ref.tryRead") + "    ref.\n}"
        assertEquals(setOf("tryRead", "set"), check(dot, "ref.", 2))
        for ((index, expression) in listOf("print(", "print(in)", "print(inp)").withIndex()) {
            val text = source.replace("print(input)", expression)
            assertTrue("input" in check(text, expression.removeSuffix(")"), index + 3), text)
        }
        val afterScope = source.dropLast(1) + "    print(in)\n}"
        assertFalse("input" in check(afterScope, "print(in", 6))
    }

    @Test fun builtinsExposeDocumentationDeclarationsAndRuntimeRestrictions() = withServer { server, document ->
        val service = server.textDocumentService
        for (name in listOf("fold", "trim", "lowercase", "tryRead")) {
            val at = position(source, ".$name")
            val hover = service.hover(HoverParams(document, at)).get(10, TimeUnit.SECONDS)
            assertTrue("\n---\n" in hover.contents.right.value, hover.toString())
            if (name == "fold") assertContains(hover.contents.right.value, "fold(initial: int, operation: (int, int) -> int): int")
            val location = service.definition(DefinitionParams(document, at)).get(10, TimeUnit.SECONDS).left.single()
            val line = Files.readAllLines(Path.of(URI.create(location.uri)))[location.range.start.line]
            assertEquals(name, line.substring(location.range.start.character, location.range.end.character))
        }
        val runtime = "func main(value: string) { print(value.length()) }"
        service.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(document.uri, 2),
            listOf(TextDocumentContentChangeEvent(runtime))))
        val members = service.completion(CompletionParams(document, position(runtime, "value.")))
            .get(10, TimeUnit.SECONDS).left.map { it.label }
        assertContains(members, "length")
        assertFalse("trim" in members || "lowercase" in members)
    }

    private fun position(text: String, marker: String): Position =
        offsetToPosition(text, text.lastIndexOf(marker) + marker.length)

    private fun withServer(block: (MCFPPLanguageServer, TextDocumentIdentifier) -> Unit) {
        val server = MCFPPLanguageServer()
        val document = TextDocumentIdentifier("file:///example03-editor.mcfpp")
        try {
            server.textDocumentService.didOpen(DidOpenTextDocumentParams(TextDocumentItem(document.uri, "mcfpp", 1, source)))
            block(server, document)
        } finally {
            server.shutdown().get(10, TimeUnit.SECONDS)
        }
    }
}
