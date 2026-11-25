package top.mcfpp.lsp

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

class MCFPPNbtEditorTest {
    @TempDir lateinit var root: Path

    @Test
    fun `merge resolves on variables and literals and reports a missing argument`() {
        val source = """
            import mcfpp.minecraft.entity:*
            entity StoneDisplay: BlockDisplay {
                override func summon(d: nbt): EntityRef<BlockDisplay> {
                    return super.summon({ item: { id: "minecraft:stone", count: 1 } }.merge(d))
                }
            }
            func mergeData(d: nbt) {
                var merged: nbt = d.merge({extra: 1})
                var literal: nbt = {
                    item: { id: "minecraft:stone", count: 1 }, label: "} ("
                }.merge(d)
                var inferred = d.merge({})
                inferred.merge(d)
                var literalInferred = {foo: 1}.merge(d)
                literalInferred.merge(d)
                d.merge()
            }
        """.trimIndent()
        root.resolve("mcfpp.toml").writeText("schema=1\nname=\"nbt\"\nnamespace=\"nbt\"\nversion=\"1.21.8\"\n")
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
            val errors = assertNotNull(messages.poll(30, TimeUnit.SECONDS)).diagnostics.filter { it.severity == DiagnosticSeverity.Error }
            assertEquals(listOf("Argument count mismatch for `merge`: expected 1, found 0"), errors.map { it.message })
            for (call in listOf("}.merge(d)", "d.merge({extra", "inferred.merge(d)", "literalInferred.merge(d)")) {
                val member = position(source, call, call.indexOf(".merge") + 1)
                val completions = service.completion(CompletionParams(TextDocumentIdentifier(uri), member)).get()
                assertTrue((completions.left ?: completions.right.items).any { it.label == "merge" }, call)
                val signature = service.signatureHelp(SignatureHelpParams(TextDocumentIdentifier(uri),
                    position(source, call, call.indexOf(".merge") + ".merge(".length))).get()
                assertEquals("merge(nbt): nbt", signature.signatures.single().label)
                val definition = service.definition(DefinitionParams(TextDocumentIdentifier(uri),
                    position(source, call, call.indexOf(".merge") + 3))).get()
                assertTrue(definition.left?.isNotEmpty() == true || definition.right?.isNotEmpty() == true, call)
            }
        } finally { server.shutdown().get() }
    }

    private fun position(source: String, needle: String, within: Int): Position {
        val start = source.indexOf(needle).also { assertTrue(it >= 0, needle) }
        val prefix = source.substring(0, start + within)
        return Position(prefix.count { it == '\n' }, prefix.substringAfterLast('\n').length)
    }
}
