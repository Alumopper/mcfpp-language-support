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
            values.add(3)
            var mapped = values.map { value -> value + 1 }
            var filtered = values.filter { value -> value > 1 }
            print(values.fold(0) { sum, value -> sum + value })
            var label = " MCFPP ".trim().lowercase()
            var ref = nbtStorageRef<list<int>>("test:values", "source")
            ref.tryRead({ input ->
                print(input)
            })
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
        assertEquals(setOf("tryRead", "set", "let", "also"), check(dot, "ref.", 2))
        for ((index, expression) in listOf("print(", "print(in)", "print(inp)").withIndex()) {
            val text = source.replace("print(input)", expression)
            assertTrue("input" in check(text, expression.removeSuffix(")"), index + 3), text)
        }
        val afterScope = source.dropLast(1) + "    print(in)\n}"
        assertFalse("input" in check(afterScope, "print(in", 6))
    }

    @Test fun builtinsExposeDocumentationDeclarationsAndRuntimeRestrictions() = withServer { server, document ->
        val service = server.textDocumentService
        val macros = mapOf("map" to "mapList", "filter" to "filterList", "fold" to "foldList")
        val signatures = mapOf(
            "add" to "add(int)",
            "fold" to "fold(initial: int, operation: (int, int) -> int): int",
            "trim" to "trim(): string",
            "lowercase" to "lowercase(): string",
            "tryRead" to "(list<int>) -> void"
        )
        val labels = service.completion(CompletionParams(document, position(source, "values.")))
            .get(10, TimeUnit.SECONDS).left.map { it.label }
        for (name in macros.keys + "add") assertEquals(1, labels.count { it == name }, labels.toString())
        for (name in listOf("add", "map", "filter", "fold", "trim", "lowercase", "tryRead")) {
            val at = position(source, ".$name")
            val hover = service.hover(HoverParams(document, at)).get(10, TimeUnit.SECONDS)
            assertTrue("\n---\n" in hover.contents.right.value, hover.toString())
            signatures[name]?.let { expected ->
                assertContains(hover.contents.right.value, expected)
                val marker = if (name == "fold") ".fold(0" else ".$name("
                val help = assertNotNull(service.signatureHelp(SignatureHelpParams(document, position(source, marker)))
                    .get(10, TimeUnit.SECONDS), name)
                val active = help.signatures[help.activeSignature]
                assertContains(active.label, expected)
                assertTrue(active.documentation.right.value.isNotBlank(), name)
            }
            val location = service.definition(DefinitionParams(document, at)).get(10, TimeUnit.SECONDS).left.single()
            assertTrue(location.uri.endsWith(if (name in macros) "collections.mcfpp" else ".kt"), location.toString())
            assertEquals(name, sourceAt(location))
            val implementation = service.implementation(ImplementationParams(document, at)).get(10, TimeUnit.SECONDS).left.single()
            assertTrue(implementation.uri.endsWith(if (name in macros) "CollectionMacros.kt" else ".kt"))
            assertEquals(macros[name] ?: name, sourceAt(implementation))
        }
        val runtime = "func main(value: string) { print(value.length()) }"
        service.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(document.uri, 2),
            listOf(TextDocumentContentChangeEvent(runtime))))
        val members = service.completion(CompletionParams(document, position(runtime, "value.")))
            .get(10, TimeUnit.SECONDS).left.map { it.label }
        assertContains(members, "length")
        assertFalse("trim" in members || "lowercase" in members)
        val receivers = """
            external input: string
            data Holder { var title: string }
            func readText(): string { return "runtime" }
            func show(holder: Holder) {
                print(input.length())
                print(holder.title.length())
                print(readText().length())
                const label = "closed"
                print(label.trim())
            }
        """.trimIndent()
        service.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(document.uri, 3),
            listOf(TextDocumentContentChangeEvent(receivers))))
        for (receiver in listOf("input.", "holder.title.", "readText().", "label.")) {
            val labels = service.completion(CompletionParams(document, position(receivers, receiver)))
                .get(10, TimeUnit.SECONDS).left.map { it.label }
            assertContains(labels, "length", receiver)
            assertEquals(receiver == "label.", "trim" in labels, receiver)
            assertEquals(receiver == "label.", "lowercase" in labels, receiver)
            assertTrue(labels.none { it == "add" || it == "fold" || it == "tryRead" }, receiver)
        }
    }

    private fun sourceAt(location: Location): String {
        val line = Files.readAllLines(Path.of(URI.create(location.uri)))[location.range.start.line]
        return line.substring(location.range.start.character, location.range.end.character)
    }

    @Test fun genericCallbacksBindReceiverAndMethodTypes() = withServer { server, document ->
        val text = """
            func main(values: list<int>, immutable: ImmutableList<int>) {
                var flags = values.mapIndexed({ index, value -> value > index })
                var total = values.fold(0.0, { accumulator, value -> accumulator + value })
                var mapped = immutable.map { value -> value > 1 }
            }
        """.trimIndent()
        val service = server.textDocumentService
        service.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(document.uri, 2),
            listOf(TextDocumentContentChangeEvent(text))))
        val mapping = service.hover(HoverParams(document, position(text, ".mapIndexed"))).get(10, TimeUnit.SECONDS)
        assertContains(mapping.contents.right.value, "callback: (int, int) -> bool): list<bool>")
        val folding = service.hover(HoverParams(document, position(text, ".fold"))).get(10, TimeUnit.SECONDS)
        assertContains(folding.contents.right.value, "fold(initial: float, operation: (float, int) -> float): float")
        val mappedAt = position(text, "immutable.map")
        val immutableMapping = service.hover(HoverParams(document, mappedAt)).get(10, TimeUnit.SECONDS)
        assertContains(immutableMapping.contents.right.value, "map(transform: (int) -> bool): list<bool>")
        val implementation = service.implementation(ImplementationParams(document, mappedAt)).get(10, TimeUnit.SECONDS).left.single()
        assertEquals("mapImmutableList", sourceAt(implementation))
    }

    @Test fun matchingSourceJarResolvesExactJvmOverload() {
        val root = Files.createTempDirectory("mcfpp-source-jar-test")
        try {
            val source = """
                package sample;
                public final class Provider {
                    public static void expand(int n) { System.out.println(n); }
                    public static void
                    expand(
                        String value
                    ) {
                        System.out.println(value);
                    }
                }
            """.trimIndent()
            val sourceFile = root.resolve("Provider.java")
            Files.writeString(sourceFile, source)
            assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", root.toString(), sourceFile.toString()))
            fun archive(file: Path, name: String, data: ByteArray) {
                java.util.zip.ZipOutputStream(Files.newOutputStream(file)).use { zip ->
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    zip.write(data)
                    zip.closeEntry()
                }
            }
            val binary = root.resolve("example-2.1.jar")
            archive(binary, "sample/Provider.class", Files.readAllBytes(root.resolve("sample/Provider.class")))
            archive(root.resolve("example-2.0-sources.jar"), "sample/Provider.java", source.toByteArray())
            val binding = top.mcfpp.library.model.NativeBinding(null, "sample.Provider", "expand", listOf("java.lang.String"), "void")
            assertNull(MCFPPSourceJarResolver(binary, emptyList()).implementation(binding))
            archive(root.resolve("example-2.1-sources.jar"), "sample/Provider.java", source.toByteArray())
            val location = assertNotNull(MCFPPSourceJarResolver(binary, emptyList()).implementation(binding))
            assertEquals(Position(4, 4), location.range.start)
            assertTrue(location.uri.endsWith("/sample/Provider.java"))
        } finally {
            root.toFile().deleteRecursively()
        }
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
