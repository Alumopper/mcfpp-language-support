package top.mcfpp.lsp

import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MCFPPWorkspaceCacheTest {
    @Test
    fun `body-only edit keeps global and other document caches hot`() {
        val lifecycle = MCFPPDocumentLifecycle(MCFPPLanguageServer())
        val firstUri = "file:///workspace/first.mcfpp"
        val secondUri = "file:///workspace/second.mcfpp"
        val firstText = "func first() { var value: int = 1; }"
        val secondText = "func second() { first(); }"
        try {
            lifecycle.didOpen(DidOpenTextDocumentParams(TextDocumentItem(firstUri, "mcfpp", 1, firstText)))
            lifecycle.didOpen(DidOpenTextDocumentParams(TextDocumentItem(secondUri, "mcfpp", 1, secondText)))
            val first = analysis(lifecycle, firstUri)
            val second = analysis(lifecycle, secondUri)
            Thread.sleep(250)

            val caches = lifecycle.workspace.symbolCaches
            val globalSymbols = lifecycle.workspace.workspaceSymbols()
            val firstCaches = caches.forDocument(first)
            val secondCaches = caches.forDocument(second)
            val visible = lifecycle.workspace.visibleGlobalSymbolsForDocument(second)
            lifecycle.diagnosticsAndTokens.semanticTokensFull(second)
            assertSame(visible, secondCaches.visible)
            assertTrue(secondCaches.semanticTokens != null)

            val changedText = "func first() { var value: int = 2; }"
            lifecycle.didChange(DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(firstUri, 2),
                listOf(TextDocumentContentChangeEvent(null, null, changedText))
            ))
            val changed = analysis(lifecycle, firstUri)

            assertSame(caches, lifecycle.workspace.symbolCaches)
            assertSame(globalSymbols, lifecycle.workspace.workspaceSymbols())
            assertSame(secondCaches, caches.forDocument(second))
            assertNotSame(firstCaches, caches.forDocument(changed))
        } finally {
            lifecycle.shutdown()
        }
    }

    @Test
    fun `macro signatures and enum member names are indexed for lookups`() {
        val range = Range(Position(0, 0), Position(0, 1))
        val macro = MCFPPSymbol(
            uri = "file:///macro.mcfpp", name = "log", kind = SymbolKind.Function, range = range,
            namespaceName = "demo", parameters = listOf(MCFPPFunctionParameter(null, "string")), isMacro = true
        )
        val native = macro.copy(uri = "file:///native.mcfpp", isMacro = false, isNative = true)
        val enumMember = macro.copy(name = "Red", kind = SymbolKind.EnumMember, isMacro = false, parameters = emptyList())
        val analysis = MCFPPDocumentIndex.analyze("file:///open.mcfpp", "func run() {}").copy(
            symbols = listOf(macro, native, enumMember),
            topLevelSymbols = listOf(macro, native),
            typeMembers = mapOf("Color" to listOf(enumMember))
        )
        val workspace = MCFPPWorkspaceView(MCFPPProjectIndex()) { mapOf(analysis.uri to analysis) }

        assertTrue(workspace.isMacroSignature(native))
        assertTrue(workspace.hasEnumMember("Red"))
    }

    @Test
    fun `body newline shifting downstream declaration refreshes workspace symbol ranges`() {
        val lifecycle = MCFPPDocumentLifecycle(MCFPPLanguageServer())
        val uri = "file:///workspace/shifted.mcfpp"
        val initial = "func first() {\n    var x: int = 1;\n}\nfunc second() {}\n"
        val shifted = "func first() {\n    var x: int = 1;\n\n}\nfunc second() {}\n"
        try {
            lifecycle.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, initial)))
            analysis(lifecycle, uri)
            val beforeLine = lifecycle.workspace.workspaceSymbols().first { it.name == "second" }.range.start.line
            assertEquals(3, beforeLine)

            lifecycle.didChange(DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(uri, 2),
                listOf(TextDocumentContentChangeEvent(null, null, shifted))
            ))
            analysis(lifecycle, uri)
            val afterLine = lifecycle.workspace.workspaceSymbols().first { it.name == "second" }.range.start.line
            assertEquals(4, afterLine)
        } finally {
            lifecycle.shutdown()
        }
    }

    private fun analysis(lifecycle: MCFPPDocumentLifecycle, uri: String): MCFPPDocumentAnalysis =
        lifecycle.withEditorAnalysis<MCFPPDocumentAnalysis>(uri, null) { it }
            .get(5, TimeUnit.SECONDS)
}
