package top.mcfpp.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.*

class MCFPPBasicsShowcaseTest {
    @TempDir lateinit var root: Path

    private fun project(nested: Boolean = false): Path {
        val project = if (nested) root.resolve("01-language-basics") else root
        for (file in listOf("mcfpp.toml", "src/main/mcfpp/main.mcfpp", "src/test/mcfpp/basics_test.mcfpp")) {
            val destination = project.resolve(file)
            destination.parent.createDirectories()
            destination.writeText(javaClass.getResource("/showcase-basics/$file")!!.readText())
        }
        return project
    }

    private fun check(file: Path, action: (MCFPPLanguageServer, String, String, List<Diagnostic>) -> Unit) {
        val uri = file.toUri().toString()
        val source = file.readText()
        val received = LinkedBlockingQueue<List<Diagnostic>>()
        val server = MCFPPLanguageServer()
        server.connect(object : LanguageClient {
            override fun telemetryEvent(value: Any?) {}
            override fun publishDiagnostics(value: PublishDiagnosticsParams) {
                if (value.uri == uri) received.offer(value.diagnostics)
            }
            override fun showMessage(value: MessageParams) {}
            override fun showMessageRequest(value: ShowMessageRequestParams): CompletableFuture<MessageActionItem> = CompletableFuture.completedFuture(null)
            override fun logMessage(value: MessageParams) {}
        })
        server.initialize(InitializeParams().apply { rootUri = root.toUri().toString() }).get()
        try {
            server.textDocumentService.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, source)))
            val diagnostics = assertNotNull(received.poll(20, TimeUnit.SECONDS), "No diagnostics for $uri")
            action(server, uri, source, diagnostics)
        } finally { server.shutdown().get() }
    }

    @Test fun `basics main resolves range bounds and selects float print overload`() {
        val main = project().resolve("src/main/mcfpp/main.mcfpp")
        check(main) { server, uri, source, diagnostics ->
            assertEquals(emptyList(), diagnostics.filter { it.severity == DiagnosticSeverity.Error }.map { it.message })
            val line = source.lines().indexOfFirst { "1..limit" in it }
            val position = Position(line, source.lines()[line].indexOf("limit") + 1)
            val definition = server.textDocumentService.definition(DefinitionParams(TextDocumentIdentifier(uri), position)).get().left.single()
            assertEquals(source.lines().indexOfFirst { "func sumTo" in it }, definition.range.start.line)
        }
    }

    @Test fun `test calls resolve main declarations with the main file closed`() {
        val test = project().resolve("src/test/mcfpp/basics_test.mcfpp")
        checkTestCalls(test)
    }

    @Test fun `opening the example collection discovers each nested project`() {
        val test = project(nested = true).resolve("src/test/mcfpp/basics_test.mcfpp")
        checkTestCalls(test)
    }

    @Test fun `intrinsic chains infer their results and reject unknown members`() {
        val main = project().resolve("src/main/mcfpp/main.mcfpp")
        main.writeText("""
            data Reward { var amount: int? }
            func accept(reward: Reward) {}
            func invoke(callback: () -> void) { callback() }
            func main() {
                var reward: Reward = {}
                accept(reward)
                var callback: () -> void = {}
                invoke(callback)
                var numbers = [1, 2]
                var names = numbers.filter { it > 0 }.map { "title" }
                var total = numbers.fold(0) { acc, n -> acc + n }
                var read = nbtStorageRef<int>("demo:test", "value").tryRead { value -> print(value) }
                "  text  ".trim().undefinedMember()
            }
        """.trimIndent())
        check(main) { _, _, _, diagnostics ->
            assertEquals(listOf("Undefined symbol `undefinedMember`"), diagnostics.filter { it.severity == DiagnosticSeverity.Error }.map { it.message })
        }
    }

    private fun checkTestCalls(file: Path) = check(file) { server, uri, source, diagnostics ->
        assertEquals(emptyList(), diagnostics.filter { it.severity == DiagnosticSeverity.Error }.map { it.message })
        for (name in listOf("sumTo", "factorial", "award", "half")) {
            val line = source.lines().indexOfFirst { "$name(" in it }
            val position = Position(line, source.lines()[line].indexOf("$name(") + 1)
            val targets = server.textDocumentService.definition(DefinitionParams(TextDocumentIdentifier(uri), position)).get().left
            assertEquals(1, targets.size, "Definition of $name")
            assertTrue(targets.single().uri.endsWith("/src/main/mcfpp/main.mcfpp"), targets.toString())
        }
    }

    @Test fun `invalid range bounds and argument types still report errors`() {
        val main = project().resolve("src/main/mcfpp/main.mcfpp")
        main.writeText(main.readText() + "\nfunc invalid() {\nfor (value : 1..missingLimit) {}\nhalf(\"wrong\")\n}\n")
        check(main) { _, _, _, diagnostics ->
            assertTrue(diagnostics.any { it.message == "Undefined symbol `missingLimit`" }, diagnostics.toString())
            assertTrue(diagnostics.any { "expected `float`, found `string`" in it.message }, diagnostics.toString())
        }
    }
}
