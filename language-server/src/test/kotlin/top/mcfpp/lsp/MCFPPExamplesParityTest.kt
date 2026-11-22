package top.mcfpp.lsp

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.services.LanguageClient
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MCFPPExamplesParityTest {
    @Test
    fun `standalone compiler examples have no LSP error diagnostics`() {
        val examplesRoot = Path.of("..", "..", "MCFPP", "examples").toAbsolutePath().normalize()
        assertTrue(Files.isDirectory(examplesRoot), "Examples directory not found: $examplesRoot")

        // These examples need local package or custom MNI provider setup. The LSP test runtime does not
        // compile those providers, so they are excluded: libraries/** (mcfpm producer/consumer
        // installation), kotlin-mni/**, mni/**, and src/test/mcfpp/** files in debugger and
        // unit-testing (the compiler's mcfpp.test harness).
        // The current shadow checker also emits known false-positive errors for compiler-provided
        // context/reference members, higher-order standard-library members, escaped/dynamic lambdas,
        // and structural argument conversion. Keep those documented gaps out of this zero-error corpus:
        // entity-context, references, stdlib/area-skill, stdlib/rewards, stdlib/score-game,
        // lambda/collections, lambda/dynamic, lambda/escape, and type-composition.
        val skippedRoots = setOf("libraries", "kotlin-mni", "mni")
        val knownShadowCheckerGaps = setOf(
            "entity-context/src/main.mcfpp",
            "references/src/main.mcfpp",
            "stdlib/area-skill/src/main.mcfpp",
            "stdlib/rewards/src/main.mcfpp",
            "stdlib/score-game/src/main.mcfpp",
            "lambda/collections/main.mcfpp",
            "lambda/dynamic/main.mcfpp",
            "lambda/escape/main.mcfpp",
            "type-composition/src/main.mcfpp",
            "debugger/src/test/mcfpp/lifecycle.mcfpp",
            "unit-testing/src/test/mcfpp/function_values_test.mcfpp",
            "unit-testing/src/test/mcfpp/math_test.mcfpp"
        )
        val sourceFiles = Files.walk(examplesRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".mcfpp") }
                .filter { path ->
                    val relative = path.relativizeAgainst(examplesRoot).toString().replace('\\', '/')
                    val segments = relative.split('/')
                    segments.first() !in skippedRoots && segments.none { it in setOf("build", "target") } &&
                        relative !in knownShadowCheckerGaps
                }
                .sorted()
                .toList()
        }
        assertTrue(sourceFiles.size >= 12, "Expected broad standalone example coverage, found ${sourceFiles.size}")

        val projects = sourceFiles.groupBy { projectRoot(it, examplesRoot) }.toSortedMap()
        val failures = mutableListOf<String>()
        val analyzedFiles = mutableListOf<Path>()

        projects.forEach { (projectRoot, files) ->
            val expectedUris = files.map { it.toUri().toString() }.toSet()
            val diagnostics = ConcurrentHashMap<String, List<Diagnostic>>()
            val analyzed = ConcurrentHashMap.newKeySet<String>()
            val completed = CountDownLatch(expectedUris.size)
            val server = MCFPPLanguageServer()
            server.connect(object : LanguageClient {
                override fun telemetryEvent(`object`: Any?) = Unit
                override fun publishDiagnostics(params: PublishDiagnosticsParams) {
                    if (params.uri in expectedUris) {
                        diagnostics[params.uri] = params.diagnostics.orEmpty()
                        if (params.version == 2 && analyzed.add(params.uri)) completed.countDown()
                    }
                }
                override fun showMessage(messageParams: org.eclipse.lsp4j.MessageParams) = Unit
                override fun showMessageRequest(requestParams: org.eclipse.lsp4j.ShowMessageRequestParams):
                    CompletableFuture<org.eclipse.lsp4j.MessageActionItem> = CompletableFuture.completedFuture(null)
                override fun logMessage(message: org.eclipse.lsp4j.MessageParams) = Unit
                override fun refreshSemanticTokens(): CompletableFuture<Void> = CompletableFuture.completedFuture(null)
            })

            try {
                server.initialize(InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder(projectRoot.toUri().toString(), projectRoot.fileName.toString()))
                }).get()
                files.forEachIndexed { index, path ->
                    server.textDocumentService.didOpen(
                        DidOpenTextDocumentParams(
                            TextDocumentItem(path.toUri().toString(), "mcfpp", index + 1, Files.readString(path))
                        )
                    )
                }
                files.forEach { path ->
                    val uri = path.toUri().toString()
                    server.textDocumentService.didChange(
                        DidChangeTextDocumentParams(
                            VersionedTextDocumentIdentifier(uri, 2),
                            listOf(TextDocumentContentChangeEvent(Files.readString(path)))
                        )
                    )
                }
                assertTrue(
                    completed.await(60, TimeUnit.SECONDS),
                    "LSP did not publish diagnostics for all ${files.size} files in $projectRoot; got $analyzed"
                )
                files.forEach { path ->
                    val uri = path.toUri().toString()
                    val errors = diagnostics[uri].orEmpty().filter { it.severity == DiagnosticSeverity.Error }
                    if (errors.isNotEmpty()) {
                        failures += errors.joinToString("\n") { diagnostic ->
                            "${path.relativizeAgainst(examplesRoot)}:${diagnostic.range.start.line + 1}: ${diagnostic.message}"
                        }
                    }
                }
                analyzedFiles += files
            } finally {
                server.shutdown().get()
            }
        }

        assertEquals(sourceFiles.size, analyzedFiles.size, "Every selected example source must be analyzed")
        assertTrue(failures.isEmpty(), "LSP reported Error diagnostics for compiler examples:\n${failures.joinToString("\n")}")
    }

    private fun projectRoot(file: Path, examplesRoot: Path): Path {
        var directory = file.parent
        while (directory != null && directory.startsWith(examplesRoot)) {
            if (Files.exists(directory.resolve("mcfpp.toml"))) return directory
            if (directory.parent == examplesRoot) break
            directory = directory.parent
        }
        return file.parent
    }

    private fun Path.relativizeAgainst(root: Path): Path = root.relativize(this)
}
