package com.simplelanguage.lsp

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import top.mcfpp.language.MniSymbolSnapshot

internal fun MniSymbolSnapshot.toSymbols(): List<MCFPPSymbol> = symbols.orEmpty().map { symbol ->
    val range = Range(Position(symbol.line, symbol.character), Position(symbol.endLine, symbol.endCharacter))
    MCFPPSymbol(uri = symbol.uri, name = symbol.name, kind = SymbolKind.Function, range = range,
        detail = symbol.detail, typeName = symbol.typeName, topLevel = true, namespaceName = symbol.namespace,
        documentation = symbol.documentation, isExternal = true,
        parameters = symbol.parameters.orEmpty().map { MCFPPFunctionParameter(it.name, it.typeName) })
}

