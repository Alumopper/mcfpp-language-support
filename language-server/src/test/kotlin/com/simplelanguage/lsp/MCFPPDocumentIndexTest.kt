package com.simplelanguage.lsp

import org.eclipse.lsp4j.SymbolKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MCFPPDocumentIndexTest {
    @Test
    fun `data template fields accept official bare const and var forms`() {
        val analysis = MCFPPDocumentIndex.analyze(
            "file:///template-fields.mcfpp",
            """
                namespace test
                data Test {
                    var a: int;
                    const ROWS: int = 20;
                    var value: nbt;
                }
            """.trimIndent()
        )

        assertEquals(emptyList(), analysis.parserDiagnostics.map { it.message })
        val fields = analysis.typeMembers["Test"]
            .orEmpty()
            .filter { it.kind == SymbolKind.Field }
            .map { it.name }
            .toSet()
        assertTrue(setOf("a", "ROWS", "value").all(fields::contains), "Extracted fields: $fields")
    }

    @Test
    fun `extracts readonly normal and default function parameters`() {
        val analysis = MCFPPDocumentIndex.analyze(
            "file:///function-parameters.mcfpp",
            """
                func choose<limit: int = 1>(value: int, fallback: int = 0) : int {
                    return value;
                }
            """.trimIndent()
        )

        val function = analysis.topLevelSymbols.single { it.name == "choose" }
        assertEquals(3, function.parameters.size)
        assertEquals("limit: int = 1", function.parameters[0].label())
        assertTrue(function.parameters[0].isReadOnly)
        assertEquals("value: int", function.parameters[1].label())
        assertEquals("fallback: int = 0", function.parameters[2].label())
        assertTrue(function.parameters[2].hasDefault)

        val genericAnalysis = MCFPPDocumentIndex.analyze(
            "file:///type-parameter.mcfpp",
            "func identity<T, count: int>(value: T) : T { return value; }"
        )
        assertEquals(SymbolKind.TypeParameter, genericAnalysis.declarationsByName.getValue("T").single().kind)
        assertEquals(SymbolKind.Variable, genericAnalysis.declarationsByName.getValue("count").single().kind)
    }

    @Test
    fun `extracts direct concrete and abstract data supertypes`() {
        val analysis = MCFPPDocumentIndex.analyze(
            "file:///type-hierarchy.mcfpp",
            """
                data Base {}
                abstract data Contract {}
                data Middle: Base, Contract {}
            """.trimIndent()
        )

        assertEquals(emptyList(), analysis.parserDiagnostics.map { it.message })
        assertEquals(
            listOf("Base", "Contract"),
            analysis.topLevelSymbols.single { it.name == "Middle" }.superTypes
        )
    }

    @Test
    fun `indexes type aliases and native function signatures without java references`() {
        val analysis = MCFPPDocumentIndex.analyze(
            "file:///native-and-alias.mcfpp",
            """
                typealias int as Count;
                func nativeAdd(value: Count) : Count = example.NativeBridge.add;
            """.trimIndent()
        )

        assertEquals(emptyList(), analysis.parserDiagnostics.map { it.message })
        val alias = analysis.topLevelSymbols.single { it.name == "Count" }
        assertEquals("int", alias.typeName)
        val nativeFunction = analysis.topLevelSymbols.single { it.name == "nativeAdd" }
        assertEquals("Count", nativeFunction.typeName)
        assertEquals(listOf("Count"), nativeFunction.parameters.map { it.typeName })
        assertTrue("nativeAdd" !in analysis.referenceOccurrencesByName)
        assertTrue("example" !in analysis.referenceOccurrencesByName)
        assertTrue("NativeBridge" !in analysis.referenceOccurrencesByName)
    }

    @Test
    fun `indexes implemented and native operator overrides as methods`() {
        val analysis = MCFPPDocumentIndex.analyze(
            "file:///operators.mcfpp",
            """
                data Vector {
                    operator + (other: Vector) : Vector { return other; }
                    operator - (other: Vector) : Vector = example.NativeBridge.minus;
                }
            """.trimIndent()
        )

        assertEquals(emptyList(), analysis.parserDiagnostics.map { it.message })
        val operators = analysis.typeMembers.getValue("Vector")
            .filter { it.name.startsWith("operator ") }
        assertEquals(listOf("operator +", "operator -"), operators.map { it.name })
        assertTrue(operators.all { it.kind == org.eclipse.lsp4j.SymbolKind.Method })
        assertTrue(operators.all { it.typeName == "Vector" })
        assertTrue(operators.all { it.parameters.single().typeName == "Vector" })
        assertTrue("example" !in analysis.referenceOccurrencesByName)
    }

    @Test
    fun `synthesizes compiler compatible default constructors`() {
        val analysis = MCFPPDocumentIndex.analyze(
            "file:///constructors.mcfpp",
            """
                data Empty {}
                data Wrapped as int;
                data Explicit {
                    constructor(value: string) {}
                }
            """.trimIndent()
        )

        assertEquals(emptyList(), analysis.parserDiagnostics.map { it.message })
        assertEquals(emptyList(), analysis.typeMembers.getValue("Empty").single { it.kind == org.eclipse.lsp4j.SymbolKind.Constructor }.parameters)
        assertEquals(
            listOf("int"),
            analysis.typeMembers.getValue("Wrapped")
                .single { it.kind == org.eclipse.lsp4j.SymbolKind.Constructor }
                .parameters
                .map { it.typeName }
        )
        val delegatedValue = analysis.typeMembers.getValue("Wrapped").single { it.name == "value" }
        assertEquals("int", delegatedValue.typeName)
        assertEquals(MCFPPAccessModifier.PRIVATE, delegatedValue.accessModifier)
        assertTrue(delegatedValue.isSynthetic)
        assertEquals(
            listOf("string"),
            analysis.typeMembers.getValue("Explicit")
                .single { it.kind == org.eclipse.lsp4j.SymbolKind.Constructor }
                .parameters
                .map { it.typeName }
        )
    }

    @Test
    fun `generic type parameters are shared with explicit and synthetic constructors`() {
        val analysis = MCFPPDocumentIndex.analyze(
            "file:///generic-constructors.mcfpp",
            """
                data Box<T> {
                    constructor(value: T) {}
                }
                data EmptyBox<T> {}
                data Wrapped<T> as T;
            """.trimIndent()
        )

        assertEquals(emptyList(), analysis.parserDiagnostics.map { it.message })
        assertEquals(
            listOf("T", "value: T"),
            analysis.typeMembers.getValue("Box")
                .single { it.kind == SymbolKind.Constructor }
                .parameters
                .map { it.label() }
        )
        assertEquals(
            listOf("T"),
            analysis.typeMembers.getValue("EmptyBox")
                .single { it.kind == SymbolKind.Constructor }
                .parameters
                .map { it.label() }
        )
        assertEquals(
            listOf("T", "value: T"),
            analysis.typeMembers.getValue("Wrapped")
                .single { it.kind == SymbolKind.Constructor }
                .parameters
                .map { it.label() }
        )
        assertEquals(
            listOf("T"),
            analysis.topLevelSymbols.single { it.name == "Box" }.parameters.map { it.label() }
        )
    }

    @Test
    fun `extracts template member access modifiers`() {
        val analysis = MCFPPDocumentIndex.analyze(
            "file:///access-modifiers.mcfpp",
            """
                data Example {
                    private var hidden: int;
                    protected func inherited() {}
                    public var visible: string;
                }
            """.trimIndent()
        )

        val members = analysis.typeMembers.getValue("Example").associateBy { it.name }
        assertEquals(MCFPPAccessModifier.PRIVATE, members.getValue("hidden").accessModifier)
        assertEquals(MCFPPAccessModifier.PROTECTED, members.getValue("inherited").accessModifier)
        assertEquals(MCFPPAccessModifier.PUBLIC, members.getValue("visible").accessModifier)
    }

    @Test
    fun `does not index namespace prefixes operators or property keys as variables`() {
        val analysis = MCFPPDocumentIndex.analyze(
            "file:///syntactic-identifiers.mcfpp",
            """
                func use(value: root.models:Thing, left: int, right: int) {
                    root.utils:make();
                    left combine right;
                    value[key = left];
                }
            """.trimIndent()
        )

        val references = analysis.referenceOccurrencesByName
        assertEquals(emptyList(), analysis.parserDiagnostics.map { it.message })
        assertTrue("root" !in references, references.toString())
        assertTrue("models" !in references)
        assertTrue("utils" !in references)
        assertTrue("combine" !in references)
        assertTrue("key" !in references)
        assertTrue("Thing" in references)
        assertTrue("make" in references)
    }
}
