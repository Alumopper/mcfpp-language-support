package com.simplelanguage.lsp

import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.junit.jupiter.api.io.TempDir
import top.mcfpp.language.VersionPreprocessor
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MCFPPCompilerFeaturesTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `supports compound assignments unary minus and trailing operator continuation`() {
        val source = """
            func calculate(value as float) -> float {
                var x = 12 /
                    3
                x +=
                    2
                x -= 1
                x *= 3
                x /= 2
                x %= 4
                var opposite = -x
                var nested = -(-value)
                return -value +
                    1.0f
            }
        """.trimIndent()
        val analysis = MCFPPDocumentIndex.analyze("file:///arithmetic.mcfpp", source)
        assertEquals(emptyList(), analysis.parserDiagnostics.map { it.message })
        assertEquals(6, analysis.referenceOccurrencesByName.getValue("x").size)
        assertTrue("opposite" in analysis.declarationsByName)
        assertTrue("nested" in analysis.declarationsByName)
    }

    @Test
    fun `newline separates return and unary minus statements`() {
        val parser = mcfppParser(CommonTokenStream(mcfppLexer(CharStreams.fromString("return\n-1\n"))))
        val statements = parser.compilationUnit().topStatement().statement()
        assertEquals(0, parser.numberOfSyntaxErrors)
        assertEquals(2, statements.size)
        assertEquals(null, statements[0].returnStatement().expression())
        assertEquals("-1", statements[1].statementExpression().expression().text)
        assertTrue(MCFPPDocumentIndex.analyze("file:///invalid.mcfpp", "var x = 1\nx++\n").parserDiagnostics.isNotEmpty())
    }

    @Test
    fun `filters nested branches imports and invalid inactive source without moving symbols`() {
        val source = """
            #if MC >= 26.3
            import modern:*
            func selected() -> float {
            #if MC == 26.3
                return -1.5f
            #else
                invalid inactive code !!!
            #endif
            }
            #elif MC >= 26.1
            import legacy:*
            func selected() -> int { return 2; }
            #else
            func selected() -> int { return 1; }
            #endif
        """.trimIndent()
        val current = MCFPPDocumentIndex.analyze("file:///versions.mcfpp", source, "26.3")
        assertEquals(source, current.text)
        assertEquals(source.length, current.analysisText.length)
        assertEquals(emptyList(), current.parserDiagnostics.map { it.message })
        assertEquals(listOf("modern"), current.imports.map { it.namespace })
        assertEquals("float", current.declarationsByName.getValue("selected").single().typeName)
        assertEquals(2, current.declarationsByName.getValue("selected").single().range.start.line)
        assertEquals(7, current.versionDirectives.size)
        assertFalse(current.referenceOccurrencesByName.containsKey("invalid"))
        val old = MCFPPDocumentIndex.analyze("file:///versions.mcfpp", source, "26.2")
        assertEquals(listOf("legacy"), old.imports.map { it.namespace })
        assertEquals(11, old.declarationsByName.getValue("selected").single().range.start.line)
    }

    @Test
    fun `version comparison uses integer segments and preserves CRLF offsets`() {
        for ((target, operator, threshold, selected) in listOf(
            listOf("26.10", ">", "26.3", "yes"),
            listOf("1.21.10", ">=", "1.21.8", "yes"),
            listOf("26.3", "==", "26.3.0", "yes"),
            listOf("26.3", "!=", "26.3", "no"),
            listOf("26.2", "<", "26.3", "yes"),
            listOf("26.2", "<=", "26.2", "yes")
        )) {
            val source = "#if MC $operator $threshold\r\nyes\r\n#else\r\nno\r\n#endif\r\n"
            val result = VersionPreprocessor.process(source, target)
            assertEquals(source.length, result.length)
            assertEquals(source.indices.filter { source[it] in "\r\n" }, result.indices.filter { result[it] in "\r\n" })
            assertTrue(result.contains(selected))
            assertFalse(result.contains(if (selected == "yes") "no" else "yes"))
        }
    }

    @Test
    fun `ignores directive text in comments strings and commands`() {
        for ((open, close) in listOf("##" to "##", "#{" to "}#", "###" to "###", "\"\"\"" to "\"\"\"", "\"" to "\"", "'" to "'")) {
            val source = "$open\n#if MC >= 26.3\n$close\nfunc visible(){}\n"
            val result = VersionPreprocessor.preprocess(source, "26.2")
            assertEquals(source, result.text())
            assertTrue(result.directives().isEmpty())
            val prefix = "$open\n#i"
            assertEquals(-1, VersionPreprocessor.directivePrefixStart(prefix, prefix.length))
        }
        val commands = "/say \"unterminated quote #if MC >= 26.3\n#if MC >= 26.3\nnew\n#else\nold\n#endif\n"
        assertTrue(VersionPreprocessor.process(commands, "26.2").contains("old"))
        assertEquals("#ifdef MC >= 26.3", VersionPreprocessor.process("#ifdef MC >= 26.3", "26.2"))
        assertEquals(2, VersionPreprocessor.directivePrefixStart("  #i", 4))
    }

    @Test
    fun `reports malformed directives at their original lines`() {
        for ((source, line) in listOf(
            "#else" to 1,
            "#endif" to 1,
            "#if MC >= 21.6\n#endif" to 1,
            "#if MC >= 26.3 && MC < 27.1\n#endif" to 1,
            "#if MC >= 26.3\n#else\n#else\n#endif" to 3,
            "#if MC >= 26.3\n#else\n#elif MC >= 26.1\n#endif" to 3,
            "#if MC >= 26.3\n#endif extra" to 2,
            "\n#if MC >= 26.3\nfunc missing(){}" to 2
        )) {
            val error = assertFailsWith<VersionPreprocessor.Error> { VersionPreprocessor.process(source, "26.3") }
            assertEquals(line, error.line)
            val analysis = MCFPPDocumentIndex.analyze("file:///directive-error.mcfpp", source, "26.3")
            assertTrue(analysis.parserDiagnostics.any { it.range.start.line == line - 1 && it.message == error.message })
        }
    }

    @Test
    fun `changing only the project version invalidates cached workspace branches`() {
        val config = root.resolve("mcfpp.json")
        fun configure(version: String) = config.writeText("""{"version":"$version","compileArgs":["-ignoreStdLib"]}""")
        configure("26.3")
        val source = root.resolve("main.mcfpp")
        source.writeText("#if MC >= 26.3\nfunc modern(){}\n#else\nfunc legacy(){}\n#endif\n")
        val index = MCFPPProjectIndex()
        index.setWorkspaceRoots(listOf(root.toUri().toString()))
        val uri = source.toUri().toString()
        assertEquals(listOf("modern"), index.refresh(emptyMap()).fileAnalyses.getValue(uri).symbols.map { it.name })
        assertEquals("26.3", index.targetVersionForUri(uri))
        configure("26.2")
        assertEquals(listOf("legacy"), index.refresh(emptyMap()).fileAnalyses.getValue(uri).symbols.map { it.name })
        assertEquals("26.2", index.targetVersionForUri(uri))
    }
}
