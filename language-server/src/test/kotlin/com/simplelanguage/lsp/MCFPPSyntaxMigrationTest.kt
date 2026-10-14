package com.simplelanguage.lsp

import org.antlr.v4.runtime.CharStreams
import org.eclipse.lsp4j.SymbolKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MCFPPSyntaxMigrationTest {
    @Test fun `indexes colon declarations implicit type parameters and direct modifiers`() {
        val source = """
            data Box<
                T,
                N: int
            > {
                const limit: int = 4
                var value: T
                constructor(value: T) { this.value = value }
            }
            func identity<T>(value: T): T { return value }
            func run() {
                dynamic counter: int = 0
                const limit: int = 2
                import var imported: int
                var box: Box = Box<int, 4>(1)
            }
        """.trimIndent()
        val analysis = MCFPPDocumentIndex.analyze("file:///colon.mcfpp", source)
        assertEquals(emptyList(), analysis.parserDiagnostics.map { it.message })
        val box = analysis.declarationsByName.getValue("Box").single { it.kind == SymbolKind.Class }
        assertEquals(listOf("T", "N: int"), box.parameters.map { it.label() })
        assertTrue(analysis.declarationsByName.getValue("T").all { it.kind == SymbolKind.TypeParameter })
        assertEquals("int", analysis.declarationsByName.getValue("counter").single().typeName)
        assertEquals("int", analysis.declarationsByName.getValue("imported").single().typeName)
        assertEquals("T", analysis.typeMembers.getValue("Box").single { it.name == "value" }.typeName)
        assertEquals(listOf("T", "value: T"), analysis.declarationsByName.getValue("identity").single().parameters.map { it.label() })
    }

    @Test fun `accepts parenthesized and bare conditions with nested calls and bodies`() {
        for (source in listOf(
            "func run(flag: bool) { if flag { return } else if (flag) { return } else { return } }",
            "func run(flag: bool) { while flag { break } }",
            "func run(flag: bool) { do { break } while flag }",
            "func run(flag: bool) { if check(flag) return }",
            "func run(flag: bool) { if flag return }",
            "func run(flag: bool) { while (flag) { break } }",
            "func run(flag: bool) { do { break } while (flag) }"
        )) {
            assertEquals(emptyList(), MCFPPDocumentIndex.analyze("file:///conditions.mcfpp", source).parserDiagnostics.map { it.message }, source)
        }
    }

    @Test fun `rejects declaration as and var modifier combinations while preserving casts and aliases`() {
        for (source in listOf(
            "var value as int = 1",
            "func old(value as int) {}",
            "func old<T as Type>() {}",
            "data Box { var value as int }",
            "const var value: int = 1",
            "dynamic var value: int = 1"
        )) {
            assertTrue(MCFPPDocumentIndex.analyze("file:///old-declarations.mcfpp", source).parserDiagnostics.isNotEmpty(), source)
        }
        val source = """
            import library:Item as Renamed
            typealias int as Count
            data Wrapped as int
            func cast(value: int): float { return value as float }
        """.trimIndent()
        assertEquals(emptyList(), MCFPPDocumentIndex.analyze("file:///preserved-as.mcfpp", source).parserDiagnostics.map { it.message })
    }

    @Test fun `checks bool conditions with either parenthesis style`() {
        for (condition in listOf("true", "(true)", "1", "(1)")) {
            for (statement in listOf("if $condition { return }", "while $condition { break }", "do { break } while $condition")) {
                val analysis = MCFPPDocumentIndex.analyze("file:///bool-conditions.mcfpp", "func run() { $statement }")
                assertEquals(emptyList(), analysis.parserDiagnostics.map { it.message })
                val checker = MCFPPTypeChecker(analysis, emptyList(), { _, _ -> null }, { _, _ -> null }, { null }, { _, _, _ -> null })
                val conditionErrors = checker.collectDiagnostics().filter { it.message.startsWith("Condition must be") }
                assertEquals(if (condition.contains("1")) 1 else 0, conditionErrors.size, statement)
            }
        }
    }

    @Test fun `retains markdown headings lists and code indentation in documentation`() {
        val source = """
            /// # Counter
            /// **Value** and [link](https://example.com).
            ///
            /// - item
            ///     indented
            /// ```mcfpp
            /// var value = 1;
            /// ```
            data Counter {
                /// Reads the value.
                public func read(): int { return this.value; }
                var value: int = 1;
            }
        """.trimIndent()
        val analysis = MCFPPDocumentIndex.analyze("file:///syntax.mcfpp", source)
        assertEquals(emptyList(), analysis.parserDiagnostics.map { it.message })
        val documentation = analysis.declarationsByName.getValue("Counter").single { it.kind == SymbolKind.Class }.documentation!!
        assertEquals("# Counter\n**Value** and [link](https://example.com).\n\n- item\n    indented\n```mcfpp\nvar value = 1;\n```", documentation)
        assertEquals("int", analysis.declarationsByName.getValue("read").single().typeName)
        assertEquals("Reads the value.", analysis.declarationsByName.getValue("read").single().documentation)
    }

    @Test fun `rejects legacy syntax and accepts comments commands and division`() {
        for (source in listOf("func old()->int{return 1;}", "# comment\nfunc old(){}", "data Box {value as int;}")) {
            assertTrue(MCFPPDocumentIndex.analyze("file:///old.mcfpp", source).parserDiagnostics.isNotEmpty(), source)
        }
        val source = "// Comment\nfunc sample(): int {\nvar value = 8 / 2; // inline\n/say // command text\nreturn value;\n}"
        assertEquals(emptyList(), MCFPPDocumentIndex.analyze("file:///new.mcfpp", source).parserDiagnostics)
        assertEquals(mcfppLexer.DOC_COMMENT, mcfppLexer(CharStreams.fromString("/// EOF")).nextToken().type)
        val invalid = MCFPPDocumentIndex.analyze("file:///fields.mcfpp", "data Box {\nvalue as int;\n}")
        assertTrue(invalid.symbols.none { it.name == "value" && it.kind == SymbolKind.Field })
    }
}
