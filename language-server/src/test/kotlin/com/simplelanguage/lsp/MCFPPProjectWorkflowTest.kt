package com.simplelanguage.lsp

import org.eclipse.lsp4j.Position
import top.mcfpp.language.MniSymbolSnapshot
import kotlin.test.*
import kotlin.test.Test

class MCFPPProjectWorkflowTest {
    @Test fun lambdaParametersAndImplicitItHaveLocalScopes() {
        val text = "func run() { consume { value: int -> print(value) }; var callback: (int)->int = { it + 1 }; print(value) }"
        val document = MCFPPDocumentIndex.analyze("file:///lambda.mcfpp", text)
        assertTrue(document.parserDiagnostics.isEmpty(), document.parserDiagnostics.toString())
        val value = document.symbols.first { it.name == "value" }
        assertEquals("int", value.typeName)
        assertTrue(document.isScopeVisible(value.enclosingFunctionScopeId, Position(0, text.indexOf("print(value)") + 6)))
        assertFalse(document.isScopeVisible(value.enclosingFunctionScopeId, Position(0, text.lastIndexOf("print(value)") + 6)))
        val implicit = document.symbols.first { it.name == "it" }
        assertTrue(implicit.isSynthetic)
        assertTrue(document.isScopeVisible(implicit.enclosingFunctionScopeId, Position(0, text.indexOf("it +"))))
    }

    @Test fun macroSnapshotsReplaceThePreviousGeneration() {
        val index = MCFPPProjectIndex()
        val symbol = MniSymbolSnapshot.Symbol().apply {
            name = "answer"; namespace = "example"; typeName = "int"; uri = "file:///Example.java"
            detail = "func answer(): int"; endCharacter = 6
        }
        val snapshot = MniSymbolSnapshot().apply { projectUri = "file:///project/"; generation = 2; symbols = listOf(symbol) }
        index.setMniSymbols(snapshot)
        assertEquals("answer", index.refresh(emptyMap()).librarySymbols.single().name)
        index.setMniSymbols(MniSymbolSnapshot().apply { projectUri = snapshot.projectUri; generation = 3 })
        assertTrue(index.refresh(emptyMap()).librarySymbols.isEmpty())
        index.setMniSymbols(snapshot)
        assertTrue(index.refresh(emptyMap()).librarySymbols.isEmpty())
    }
}
