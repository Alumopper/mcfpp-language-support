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
import top.mcfpp.language.ContextBuiltins

class MCFPPContextEditorTest {
    @TempDir lateinit var root: Path
    private fun position(source: String, needle: String, after: String = needle): Position {
        val offset = source.indexOf(needle).also { assertTrue(it >= 0, needle) } + needle.indexOf(after) + after.length
        val prefix = source.substring(0, offset)
        return Position(prefix.count { it == '\n' }, prefix.substringAfterLast('\n').length)
    }
    private fun check(source: String, action: (org.eclipse.lsp4j.services.TextDocumentService, String, List<Diagnostic>) -> Unit) {
        root.resolve("mcfpp.toml").writeText("schema=1\nname=\"contexts\"\nnamespace=\"contexts\"\nversion=\"26.3\"\n")
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
        } finally { server.shutdown().get() }
    }

    @Test fun `context hierarchy and component views match the compiler`() {
        for (actual in ContextBuiltins.TYPES) for (expected in ContextBuiltins.TYPES) {
            val a = assertNotNull(top.mcfpp.core.lang.context.ContextTypes.named(actual))
            val b = assertNotNull(top.mcfpp.core.lang.context.ContextTypes.named(expected))
            assertEquals(top.mcfpp.type.TypeRelations.isSubtype(a, b), ContextBuiltins.isSubtype(actual, expected), "$actual <: $expected")
        }
    }

    @Test fun `contexts infer callback types complete nominal members and navigate parameters`() {
        val source = """
            context(space: PosContext, actor: EntityContext)
            func probe() {
                var location = space.pos
                var executor = actor.executor
            }
            func main() {
                var target = @s
                target.withEntityCtx { actor ->
                    target.withPosCtx { space ->
                        probe()
                        probe(actor = actor, space = space)
                        context(space, actor) { probe(space = space, actor = actor) }
                        var inherited = space.rotation
                        space.onPassengers { passenger ->
                            var subject = passenger.executor
                        }
                    }
                }
            }
        """.trimIndent()
        check(source) { service, uri, diagnostics ->
            assertTrue(diagnostics.none { it.severity == DiagnosticSeverity.Error }, diagnostics.joinToString("\n") { "${it.range}: ${it.message}" })
            fun completions(needle: String, after: String) = service.completion(CompletionParams(TextDocumentIdentifier(uri), position(source, needle, after))).get()
                .let { it.left ?: it.right.items }.map { it.label }.toSet()
            val positionMembers = completions("space.pos", "space.")
            assertTrue(positionMembers.containsAll(listOf("pos", "dimension")), positionMembers.toString())
            assertFalse("executor" in positionMembers || "rotation" in positionMembers)
            assertTrue(completions("space.rotation", "space.").containsAll(listOf("pos", "executor", "rotation", "onPassengers")))
            assertTrue(completions("passenger.executor", "passenger.").containsAll(listOf("executor", "pos", "onVehicle")))
            val definition = service.definition(DefinitionParams(TextDocumentIdentifier(uri), position(source, "space.pos", "spac"))).get()
            assertEquals(Position(0, 8), definition.left.single().range.start)
            val help = service.signatureHelp(SignatureHelpParams(TextDocumentIdentifier(uri), position(source, "probe(actor = actor", "probe("))).get()
            assertTrue(help.signatures.any { "context(space: PosContext, actor: EntityContext)" in it.label }, help.toString())
            val named = service.definition(DefinitionParams(TextDocumentIdentifier(uri), position(source, "probe(actor = actor", "probe(act"))).get()
            assertEquals(Position(0, source.indexOf("actor: EntityContext")), named.left.single().range.start)
            val rename = service.rename(RenameParams(TextDocumentIdentifier(uri), Position(0, source.indexOf("actor: EntityContext") + 2), "executorContext")).get()
            assertEquals(4, rename.changes.getValue(uri).size, rename.toString())
        }
    }

    @Test fun `missing ambiguous wrong and unprovided contexts are diagnosed`() {
        val source = """
            context(space: PosContext) func probe() {}
            func main() {
                probe()
                withCurrentCtx { first ->
                    withCurrentCtx { second ->
                        context(first, second) { probe() }
                        probe(space = 1)
                    }
                }
            }
            func ordinary(space: PosContext) { probe() }
        """.trimIndent()
        check(source) { _, _, diagnostics ->
            assertTrue(diagnostics.count { "Missing context" in it.message } >= 2, diagnostics.toString())
            assertTrue(diagnostics.any { "Ambiguous context" in it.message }, diagnostics.toString())
            assertTrue(diagnostics.any { "requires `PosContext`" in it.message }, diagnostics.toString())
        }
    }

    @Test fun `narrow context members and escaping context returns are rejected`() {
        val source = """
            context(space: PosContext)
            func bad(): any { return space }
            context(space: PosContext)
            func view() { var illegal = space.executor }
            func legacy() { execute(pos = ~ ~ ~) {} }
        """.trimIndent()
        check(source) { _, _, diagnostics ->
            assertTrue(diagnostics.any { "cannot escape" in it.message }, diagnostics.toString())
            assertTrue(diagnostics.any { "has no member `executor`" in it.message }, diagnostics.toString())
            assertTrue(diagnostics.any { it.range.start.line == 4 && it.severity == DiagnosticSeverity.Error }, diagnostics.toString())
        }
    }

    @Test fun `function values retain context requirements`() {
        val source = """
            context(space: PosContext) func probe() {}
            func main() {
                var callback: context(PosContext) () -> void = probe
                callback()
                withCurrentCtx { available -> callback() }
            }
        """.trimIndent()
        check(source) { _, _, diagnostics ->
            val errors = diagnostics.filter { it.severity == DiagnosticSeverity.Error }
            assertEquals(1, errors.size, errors.toString())
            assertTrue("Missing context" in errors.single().message, errors.toString())
        }
    }
}
