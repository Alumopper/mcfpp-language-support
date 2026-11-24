package top.mcfpp.lsp

import com.google.gson.Gson
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.mcfpp.library.format.LibraryModelCodec
import top.mcfpp.library.format.NativeCodec
import top.mcfpp.library.model.*
import top.mcfpp.library.model.MacroParameterMode.*
import top.mcfpp.library.syntax.*
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.*
import kotlin.test.*

class MCFPPLibraryMacroEditorTest {
    @TempDir lateinit var root: Path
    private val javaSource = """
        package acceptance.library;
        public final class Provider {
            public static int expand(int value) { return value; }
            public static int
            expand(
                String value
            ) {
                return value.length();
            }
        }
    """.trimIndent()

    @Test fun binaryLibraryMacroHasSignaturesAndDocsWithoutSourcesAndNavigatesWithMatchingSources() {
        val consumer = """
            namespace consumer
            import library:foo as bar
            func main() {
                var number = bar(7)
                var word = bar("hello")
            }
        """.trimIndent()
        val library = writeLibrary()
        val binary = writeBinary()
        writeProject(library, binary)
        fun verifySources(available: Boolean) = withServer(consumer) { service, document, diagnostics ->
            assertTrue(diagnostics.none { it.severity == DiagnosticSeverity.Error }, diagnostics.toString())
            for ((argument, type, methodLine) in listOf(Triple("7", "int", 2), Triple("\"hello\"", "string", 4))) {
                val call = "bar($argument"
                val callOffset = consumer.indexOf(call)
                assertTrue(callOffset >= 0, call)
                val name = offsetToPosition(consumer, callOffset + 1)
                val expected = "bar(value: $type): int"
                val doc = "Expands the $type dependency argument."
                val hover = service.hover(HoverParams(document, name)).await().contents.right.value
                assertContains(hover, "(value: $type): int")
                assertContains(hover, doc)
                val help = assertNotNull(service.signatureHelp(SignatureHelpParams(document,
                    offsetToPosition(consumer, callOffset + call.length))).await())
                assertEquals(setOf("bar(value: int): int", "bar(value: string): int"), help.signatures.map { it.label }.toSet())
                assertEquals(expected, help.signatures[help.activeSignature].label)
                val locations = service.implementation(ImplementationParams(document, name)).await().left
                if (available) {
                    val location = locations.single()
                    assertTrue(location.uri.endsWith("/acceptance/library/Provider.java"), location.toString())
                    val line = javaSource.lines()[methodLine]
                    assertEquals(Position(methodLine, line.indexOf("expand")), location.range.start)
                    val resolved = Path.of(URI.create(location.uri)).readLines()[methodLine]
                    assertEquals("expand", resolved.substring(location.range.start.character, location.range.end.character))
                    assertEquals(locations, service.definition(DefinitionParams(document, name)).await().left)
                } else {
                    assertTrue(locations.isEmpty(), locations.toString())
                }
            }
            val completion = service.completion(CompletionParams(document, position(consumer, "var number = ba"))).await().left
            val alias = completion.single { it.label == "bar" }
            val documentedOverloads = mapOf("bar(value: int): int" to "Expands the int dependency argument.",
                "bar(value: string): int" to "Expands the string dependency argument.")
            assertEquals(documentedOverloads.getValue(alias.detail), alias.documentation.right.value)
            assertTrue(completion.none { it.label == "foo" })
        }
        verifySources(false)
        archive(root.resolve("acceptance-library-2.0-sources.jar"), "acceptance/library/Provider.java", javaSource.toByteArray())
        verifySources(false)
        archive(root.resolve("acceptance-library-2.1-sources.jar"), "acceptance/library/Provider.java", javaSource.toByteArray())
        verifySources(true)
    }

    @Test fun importedBinaryMacroReportsAllParameterModesAtTheAliasedCall() {
        writeProject(writeLibrary())
        val source = """
            namespace consumer
            import library:modes as expand
            func ordinary(value: int) {}
            func main(runtime: int) {
                expand(runtime, 3, ordinary)
            }
        """.trimIndent()
        withServer(source) { _, _, diagnostics ->
            val errors = diagnostics.filter { it.severity == DiagnosticSeverity.Error }
            assertEquals(3, errors.size, diagnostics.toString())
            for ((argument, expected) in mapOf(
                "runtime" to "requires a compile-time constant",
                "3" to "requires a compiler-issued REF location",
                "ordinary" to "requires a direct inline lambda"
            )) {
                val error = errors.single { expected in it.message }
                val line = source.lines()[error.range.start.line]
                assertEquals(argument, line.substring(error.range.start.character, error.range.end.character))
            }
        }
    }

    private fun writeLibrary(): Path {
        val environment = EnvironmentId(1)
        val namespace = DeclarationId(1)
        val int = MacroType.Source(TypeRef(1))
        val string = MacroType.Source(TypeRef(2))
        val void = MacroType.Source(TypeRef(3))
        val intKey = KeyType.Builtin("int")
        val stringKey = KeyType.Builtin("string")
        val voidKey = KeyType.Builtin("void")
        val callbackKey = KeyType.Function(FunctionExecution.NORMAL, listOf(intKey), voidKey)
        fun macro(name: String, method: String, jvm: List<String>, parameters: List<MacroParameter>, keys: List<KeyType>,
                  result: MacroType = int, jvmResult: String = "int") = MacroDeclaration(
            DeclCommon(DeclarationKey("library", emptyList(), 11, name, DeclarationSignature(emptyList(), keys)),
                null, name, namespace, environment, DeclarationAccess.PUBLIC, 0),
            MacroBinding(NativeBinding(null, "acceptance.library.Provider", method, jvm, jvmResult), name, parameters, result)
        )
        val declarations = LibraryDeclarations(listOf(
            NamespaceDeclaration(DeclCommon(DeclarationKey("library", emptyList(), 1, "library"), null, "library",
                null, environment, DeclarationAccess.PUBLIC, 0), listOf(DeclarationId(2), DeclarationId(3), DeclarationId(4))),
            macro("foo", "expand", listOf("int"), listOf(MacroParameter("value", VALUE, int)), listOf(intKey)),
            macro("foo", "expand", listOf("java.lang.String"), listOf(MacroParameter("value", VALUE, string)), listOf(stringKey)),
            macro("modes", "modes", listOf("top.mcfpp.mni.api.MacroContext", "top.mcfpp.mni.api.Const",
                "top.mcfpp.mni.api.Ref", "top.mcfpp.mni.api.InlineFn"),
                listOf(MacroParameter("seed", CONST, int), MacroParameter("output", REF, int),
                    MacroParameter("callback", INLINE, MacroType.Callback(listOf(MacroParameter("value", VALUE, int)), void))),
                listOf(intKey, intKey, callbackKey), void, "void"),
        ), listOf(EnvironmentRecord("library", emptyList())))
        val model = LibraryModel(
            LibraryManifest(PackageKey("acceptance:library", "2.1"), "main", LibraryTarget("26.3"),
                entryEnvironments = listOf(environment), rootDeclarations = listOf(namespace)),
            declarations, LibraryTables(listOf("int", "string", "void").map(LibraryType::Builtin), emptyList()),
            LibrarySyntax(emptyList(), emptyList(), emptyList()),
            debug = LibraryDebug(emptyList(), emptyList(), listOf(
                DeclarationDocument(DeclarationId(2), "Expands the int dependency argument."),
                DeclarationDocument(DeclarationId(3), "Expands the string dependency argument.")
            ))
        )
        return root.resolve("dependency.mclib").also { it.writeBytes(LibraryModelCodec(listOf(NativeCodec)).encode(model)) }
    }

    private fun writeBinary(): Path {
        val source = root.resolve("Provider.java").also { it.writeText(javaSource) }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", root.toString(), source.toString()))
        val classFile = "acceptance/library/Provider.class"
        return root.resolve("acceptance-library-2.1.jar").also { archive(it, classFile, root.resolve(classFile).readBytes()) }
    }

    private fun archive(file: Path, entry: String, bytes: ByteArray) {
        JarOutputStream(Files.newOutputStream(file)).use { jar ->
            jar.putNextEntry(JarEntry(entry))
            jar.write(bytes)
            jar.closeEntry()
        }
    }

    private fun writeProject(library: Path, jar: Path? = null) {
        root.resolve("mcfpp.toml").writeText("schema=1\nnamespace=\"consumer\"\nversion=\"26.3\"\ncompileArgs=[\"-ignoreStdLib\"]\n")
        root.resolve(".mcfpp").createDirectories().resolve("ide-model.json").writeText(
            Gson().toJson(mapOf(
                "schema" to 1,
                "libraries" to listOf(mapOf("path" to library.toString())),
                "plugins" to listOfNotNull(jar?.let { mapOf("path" to it.toString()) })
            )))
    }

    private fun withServer(source: String, check: (TextDocumentService, TextDocumentIdentifier, List<Diagnostic>) -> Unit) {
        val document = TextDocumentIdentifier(root.resolve("src/main/mcfpp/main.mcfpp").toUri().toString())
        val messages = LinkedBlockingQueue<PublishDiagnosticsParams>()
        val server = MCFPPLanguageServer()
        server.connect(object : LanguageClient {
            override fun telemetryEvent(value: Any?) {}
            override fun publishDiagnostics(value: PublishDiagnosticsParams) {
                if (value.uri == document.uri) messages.offer(value)
            }
            override fun showMessage(value: MessageParams) {}
            override fun showMessageRequest(value: ShowMessageRequestParams): CompletableFuture<MessageActionItem> =
                CompletableFuture.completedFuture(null)
            override fun logMessage(value: MessageParams) {}
        })
        try {
            server.initialize(InitializeParams().apply {
                workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), "acceptance"))
            }).await()
            val service = server.textDocumentService
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(document.uri, "mcfpp", 1, source)))
            check(service, document, assertNotNull(messages.poll(30, TimeUnit.SECONDS)).diagnostics)
        } finally {
            server.shutdown().await()
        }
    }

    private fun position(source: String, marker: String) =
        offsetToPosition(source, source.indexOf(marker).also { assertTrue(it >= 0, marker) } + marker.length)

    private fun <T> CompletableFuture<T>.await(): T = get(30, TimeUnit.SECONDS)
}
