package top.mcfpp.lsp

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertTrue

/** Fast static guardrails enforcing LSP and shared module rules from AGENTS.md. */
class LspArchitectureGuardrailsTest {
    companion object {
        private const val DEFAULT_MAX_LINES = 700
        private val GRANDFATHERED_LINE_CAPS = mapOf(
            "language-server/src/main/kotlin/top/mcfpp/lsp/MCFPPTypeChecker.kt" to 1287,
            "language-server/src/main/kotlin/top/mcfpp/lsp/MCFPPParserSemanticExtractor.kt" to 1111,
        )
    }

    private fun supportRepoRoot(): Path {
        var dir = Path.of("").toAbsolutePath().normalize()
        while (!Files.isDirectory(dir.resolve("language-server")) && dir.parent != null) {
            dir = dir.parent
        }
        check(Files.isDirectory(dir.resolve("language-server"))) { "Cannot locate mcfpp-language-support root from $dir" }
        return dir
    }

    @Test
    fun `no duplicate antlr grammar files exist in mcfpp-language-support`() {
        val root = supportRepoRoot()
        val grammars = Files.walk(root).use { stream ->
            stream.asSequence()
                .filter { path ->
                    val rel = root.relativize(path).invariantSeparatorsPathString
                    !rel.startsWith(".git/") && !rel.contains("/build/") && !rel.contains("/node_modules/")
                }
                .filter { it.isRegularFile() && it.extension == "g4" }
                .map { root.relativize(it).invariantSeparatorsPathString }
                .toList()
        }
        assertTrue(grammars.isEmpty(), "Grammar files must not be duplicated outside MCFPP/src/main/antlr: $grammars")
    }

    @Test
    fun `language-server and shared source files stay within 700-line limit or ratchet`() {
        val root = supportRepoRoot()
        val sourceRoots = listOf(
            root.resolve("language-server/src/main"),
            root.resolve("shared/src/main")
        ).filter { Files.isDirectory(it) }

        val violations = mutableListOf<String>()
        for (sourceRoot in sourceRoots) {
            Files.walk(sourceRoot).use { stream ->
                stream.asSequence()
                    .filter { it.isRegularFile() && (it.extension == "kt" || it.extension == "java") }
                    .forEach { file ->
                        val rel = root.relativize(file).invariantSeparatorsPathString
                        val lines = file.readLines().size
                        val cap = GRANDFATHERED_LINE_CAPS[rel] ?: DEFAULT_MAX_LINES
                        if (lines > cap) {
                            violations += "$rel has $lines lines (limit: $cap)"
                        }
                    }
            }
        }
        assertTrue(violations.isEmpty(), "File size guardrail violations:\n${violations.joinToString("\n")}")
    }
}
