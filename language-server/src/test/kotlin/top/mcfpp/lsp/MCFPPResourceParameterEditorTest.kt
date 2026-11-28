package top.mcfpp.lsp

import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MCFPPResourceParameterEditorTest {
    @TempDir lateinit var root: Path

    @Test fun `annotated calls complete warn and navigate in the selected pack and registry`() {
        root.resolve("mcfpp.toml").writeText("""
            schema = 1
            namespace = "demo"
            version = "26.1"
            stdLib = false
            dataPath = "pack/data"
        """.trimIndent())
        val predicate = root.resolve("pack/data/demo/predicate/check.json")
        predicate.parent.createDirectories()
        predicate.writeText("{}")
        val advancement = root.resolve("pack/data/demo/advancement/other.json")
        advancement.parent.createDirectories()
        advancement.writeText("{}")

        val source = """
            namespace demo
            func check(@Resource("predicate") id: string) {}
            func effect(@Resource("effect") id: string) {}
            func plain(id: string) {}
            func overloaded(id: string, value: int) {}
            func overloaded(@Resource("predicate") id: string, value: bool) {}
            func main() {
                check("demo:check")
                check("demo:other")
                check("bad ID")
                effect("minecraft:speed")
                effect("minecraft:no_such_effect")
                plain("demo:unknown")
                overloaded("demo:ordinary_string", 1)
                overloaded("demo:checked_string", true)
            }
        """.trimIndent()
        val uri = root.resolve("src/main/mcfpp/main.mcfpp").toUri().toString()
        val analysis = MCFPPDocumentIndex.analyze(uri, source, "26.1")
        val index = MCFPPProjectIndex().apply {
            setWorkspaceRoots(listOf(root.toUri().toString()))
            refresh(mapOf(uri to source))
        }
        val workspace = MCFPPWorkspaceView(index) { mapOf(uri to analysis) }
        val diagnostics = MCFPPDiagnosticsAndTokensHandler(workspace)
        val completion = MCFPPCompletionHandler(workspace)
        val navigation = MCFPPNavigationHandler(workspace)
        fun position(needle: String, advance: Int = needle.length): Position =
            offsetToPosition(source, source.indexOf(needle).also { assertTrue(it >= 0) } + advance)

        val warnings = diagnostics.collectDiagnostics(analysis).filter { it.code?.left == "mcfpp.resource" }
        assertEquals(setOf("demo:other", "bad ID", "minecraft:no_such_effect", "demo:checked_string"),
            warnings.map { source.lines()[it.range.start.line].substring(it.range.start.character, it.range.end.character) }.toSet())
        assertTrue(warnings.all { it.severity == DiagnosticSeverity.Warning })

        val document = TextDocumentIdentifier(uri)
        val jump = navigation.definition(DefinitionParams(document, position("demo:check", 6)), analysis, true).right.single()
        assertEquals(predicate.toUri().toString(), jump.targetUri)

        val dataItems = completion.completion(CompletionParams(document, position("demo:check", 6)), analysis).left
        assertEquals(listOf("demo:check"), dataItems.map { it.label })
        assertEquals(jump.originSelectionRange, dataItems.single().textEdit.left.range)
        val effectItems = completion.completion(CompletionParams(document, position("minecraft:speed", 13)), analysis).left
        assertTrue(effectItems.any { it.label == "minecraft:speed" })

        val unfinished = source.substringBeforeLast("}") + "    effect(\"spe"
        val unfinishedAnalysis = MCFPPDocumentIndex.analyze(uri, unfinished, "26.1")
        val unfinishedCompletion = completion.completion(
            CompletionParams(document, offsetToPosition(unfinished, unfinished.length)), unfinishedAnalysis).left
        assertTrue(unfinishedCompletion.any { it.label == "minecraft:speed" })

        predicate.deleteExisting()
        index.refresh(mapOf(uri to source))
        assertTrue(navigation.definition(DefinitionParams(document, position("demo:check", 6)), analysis).left.isEmpty())
        assertTrue(diagnostics.collectDiagnostics(analysis).any { it.message.contains("demo:check") })
    }
}
