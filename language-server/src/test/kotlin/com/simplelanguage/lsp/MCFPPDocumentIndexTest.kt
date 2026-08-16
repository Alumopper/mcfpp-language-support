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
                    a as int;
                    const ROWS as int = 20;
                    var value as nbt;
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
    fun `extracts readonly normal static and default function parameters`() {
        val analysis = MCFPPDocumentIndex.analyze(
            "file:///function-parameters.mcfpp",
            """
                func choose<limit as int = 1>(static value as int, fallback as int = 0) -> int {
                    return value;
                }
            """.trimIndent()
        )

        val function = analysis.topLevelSymbols.single { it.name == "choose" }
        assertEquals(3, function.parameters.size)
        assertEquals("limit as int = 1", function.parameters[0].label())
        assertTrue(function.parameters[0].isReadOnly)
        assertEquals("static value as int", function.parameters[1].label())
        assertTrue(function.parameters[1].isStatic)
        assertEquals("fallback as int = 0", function.parameters[2].label())
        assertTrue(function.parameters[2].hasDefault)

        val genericAnalysis = MCFPPDocumentIndex.analyze(
            "file:///type-parameter.mcfpp",
            "func identity<T as Type, count as int>(value as T) -> T { return value; }"
        )
        assertEquals(SymbolKind.TypeParameter, genericAnalysis.declarationsByName.getValue("T").single().kind)
        assertEquals(SymbolKind.Variable, genericAnalysis.declarationsByName.getValue("count").single().kind)
    }

    @Test
    fun `extracts direct template and interface supertypes`() {
        val analysis = MCFPPDocumentIndex.analyze(
            "file:///type-hierarchy.mcfpp",
            """
                data Base {}
                interface Contract {}
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
                func nativeAdd(value as Count) -> Count = example.NativeBridge.add;
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
                    operator + (other as Vector) -> Vector { return other; }
                    operator - (other as Vector) -> Vector = example.NativeBridge.minus;
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
                    constructor(value as string) {}
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
                data Box<T as Type> {
                    constructor(value as T) {}
                }
                data EmptyBox<T as Type> {}
                data Wrapped<T as Type> as T;
            """.trimIndent()
        )

        assertEquals(emptyList(), analysis.parserDiagnostics.map { it.message })
        assertEquals(
            listOf("T as Type", "value as T"),
            analysis.typeMembers.getValue("Box")
                .single { it.kind == SymbolKind.Constructor }
                .parameters
                .map { it.label() }
        )
        assertEquals(
            listOf("T as Type"),
            analysis.typeMembers.getValue("EmptyBox")
                .single { it.kind == SymbolKind.Constructor }
                .parameters
                .map { it.label() }
        )
        assertEquals(
            listOf("T as Type", "value as T"),
            analysis.typeMembers.getValue("Wrapped")
                .single { it.kind == SymbolKind.Constructor }
                .parameters
                .map { it.label() }
        )
        assertEquals(
            listOf("T as Type"),
            analysis.topLevelSymbols.single { it.name == "Box" }.parameters.map { it.label() }
        )
    }

    @Test
    fun `extracts template member access modifiers`() {
        val analysis = MCFPPDocumentIndex.analyze(
            "file:///access-modifiers.mcfpp",
            """
                data Example {
                    private var hidden as int;
                    protected func inherited() {}
                    public var visible as string;
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
                func use(value as root.models:Thing, left as int, right as int) {
                    root.utils:make();
                    left combine right;
                    value[key = left];
                }
            """.trimIndent()
        )

        val references = analysis.referenceOccurrencesByName
        assertTrue("root" !in references)
        assertTrue("models" !in references)
        assertTrue("utils" !in references)
        assertTrue("combine" !in references)
        assertTrue("key" !in references)
        assertTrue("Thing" in references)
        assertTrue("make" in references)
    }
}
