package com.simplelanguage.lsp

import org.eclipse.lsp4j.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MCFPPCallContextTest {
    @Test
    fun `finds the innermost nested call`() {
        val text = "outer(inner(1, value), second)"
        val candidates = MCFPPCallContextFinder.findCandidates(text, Position(0, 16))

        assertEquals("inner", candidates.first().name)
        assertEquals(1, candidates.first().activeParameter)
        assertEquals(listOf("1", " v"), candidates.first().arguments)
    }

    @Test
    fun `counts only top level commas in strings and closed generic calls`() {
        val text = "outer(\"a,b\", generic<1, 2>(3), )"
        val cursor = Position(0, text.lastIndexOf(')'))
        val outer = MCFPPCallContextFinder.findCandidates(text, cursor).first { it.name == "outer" }

        assertEquals(2, outer.activeParameter)
        assertEquals(3, outer.arguments.size)
    }

    @Test
    fun `does not treat relational angle as an argument group`() {
        val text = "compare(a < b, )"
        val cursor = Position(0, text.lastIndexOf(')'))
        val compare = MCFPPCallContextFinder.findCandidates(text, cursor).first { it.name == "compare" }

        assertEquals(1, compare.activeParameter)
    }

    @Test
    fun `does not leak a call context across the current block`() {
        val text = "func main(){\n    value = 1;\n}"
        val candidates = MCFPPCallContextFinder.findCandidates(text, Position(1, 13))

        assertTrue(candidates.isEmpty(), "Candidates: $candidates")
    }

    @Test
    fun `normal argument context retains preceding readonly arguments`() {
        val text = "identity<int, string>(value, )"
        val cursor = Position(0, text.lastIndexOf(')'))
        val context = MCFPPCallContextFinder.findCandidates(text, cursor).first { it.name == "identity" }

        assertEquals(MCFPPArgumentGroup.NORMAL, context.argumentGroup)
        assertEquals(listOf("int", " string"), context.readOnlyArguments)
        assertEquals(listOf("value", " "), context.arguments)
    }
}
