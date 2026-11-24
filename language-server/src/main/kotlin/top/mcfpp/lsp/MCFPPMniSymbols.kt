package top.mcfpp.lsp

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import top.mcfpp.language.MniSymbolSnapshot
import top.mcfpp.library.model.MacroParameterMode

internal fun MniSymbolSnapshot.toSymbols(): List<MCFPPSymbol> = symbols.orEmpty().map { symbol ->
    val range = Range(Position(symbol.line, symbol.character), Position(symbol.endLine, symbol.endCharacter))
    val declaration = symbol.declarationUri?.let {
        Range(Position(symbol.declarationLine, symbol.declarationCharacter),
            Position(symbol.declarationLine, symbol.declarationCharacter + symbol.name.length))
    }
    MCFPPSymbol(uri = symbol.declarationUri ?: symbol.uri, name = symbol.name, kind = SymbolKind.Function, range = declaration ?: range,
        detail = symbol.detail, typeName = symbol.typeName, topLevel = true, namespaceName = symbol.namespace,
        documentation = symbol.documentation, isExternal = true, isNative = true, isMacro = symbol.macro,
        nativeImplementation = symbol.uri?.let { Location(it, range) },
        parameters = symbol.parameters.orEmpty().map(MniSymbolSnapshot.Parameter::toParameter))
}

private fun MniSymbolSnapshot.Parameter.toParameter(): MCFPPFunctionParameter =
    MCFPPFunctionParameter(name, typeName, macroMode = MacroParameterMode.entries.find { it.name == mode },
        macroCallbackParameters = macroCallbackParameters.orEmpty().map(MniSymbolSnapshot.Parameter::toParameter))
