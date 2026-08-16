package com.simplelanguage.lsp

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class MCFPPTextChangesTest {
    @Test
    fun `applies sequential incremental changes with utf16 positions and mixed line endings`() {
        val original = "first\r\nA😀B\nthird"
        val changes = listOf(
            TextDocumentContentChangeEvent(
                Range(Position(1, 1), Position(1, 3)),
                "x"
            ),
            TextDocumentContentChangeEvent(
                Range(Position(2, 0), Position(2, 5)),
                "last"
            )
        )

        assertEquals("first\r\nAxB\nlast", MCFPPTextChanges.apply(original, changes))
    }

    @Test
    fun `full document change replaces preceding content`() {
        val changes = listOf(TextDocumentContentChangeEvent("namespace replacement"))

        assertEquals("namespace replacement", MCFPPTextChanges.apply("old text", changes))
    }

    @Test
    fun `positions outside the document are clamped safely`() {
        assertEquals(3, MCFPPTextChanges.positionToOffset("abc", Position(99, 99)))
        assertEquals(0, MCFPPTextChanges.positionToOffset("abc", Position(-1, -1)))
    }
}
