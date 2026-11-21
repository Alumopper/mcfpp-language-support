package top.mcfpp.lsp

import org.eclipse.lsp4j.SymbolKind

internal val IDENTIFIER_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")
internal val CALLABLE_SYMBOL_KINDS = setOf(SymbolKind.Function, SymbolKind.Method, SymbolKind.Constructor)
internal val TYPE_SYMBOL_KINDS = setOf(SymbolKind.Class, SymbolKind.Enum)
internal val TYPE_DEFINITION_SYMBOL_KINDS = TYPE_SYMBOL_KINDS + SymbolKind.TypeParameter

internal fun normalizeTypeName(typeName: String?): String? {
    if (typeName.isNullOrBlank()) {
        return null
    }
    return typeName.substringBefore('<').substringBefore('?').substringAfterLast(':').trim().trimEnd(';', ',')
}

internal fun typeParameterSubstitutions(
    symbol: MCFPPSymbol,
    readOnlyArguments: List<String>
): Map<String, String> = symbol.parameters
    .filter { it.isReadOnly && normalizeTypeName(it.typeName).equals("type", ignoreCase = true) }
    .zip(readOnlyArguments)
    .mapNotNull { (parameter, argument) ->
        val name = parameter.name ?: return@mapNotNull null
        argument.trim().takeIf { it.isNotEmpty() }?.let { name to it }
    }
    .toMap()

internal fun substituteTypeParameters(typeName: String, substitutions: Map<String, String>): String {
    return substitutions.entries.fold(typeName) { current, (parameter, argument) ->
        current.replace(
            Regex("(?<![A-Za-z0-9_])${Regex.escape(parameter)}(?![A-Za-z0-9_])"),
            Regex.escapeReplacement(argument)
        )
    }
}

internal fun buildSignatureLabel(symbol: MCFPPSymbol, displayName: String = symbol.name): String = buildString {
    if (symbol.contextParameters.isNotEmpty()) append(symbol.contextParameters.joinToString(", ", "context(", ") ") { it.label() })
    append(displayName)
    val readOnly = symbol.parameters.filter { it.isReadOnly }
    if (readOnly.isNotEmpty()) {
        append('<')
        append(readOnly.joinToString(", ") { it.label() })
        append('>')
    }
    append('(')
    append(symbol.parameters.filterNot { it.isReadOnly }.joinToString(", ") { it.label() })
    append(')')
    symbol.typeName
        ?.takeIf { it.isNotBlank() && it != "void" && symbol.kind != SymbolKind.Constructor }
        ?.let { append(": ").append(it) }
}

internal fun parameterSignatureKey(symbol: MCFPPSymbol): String = symbol.parameters.joinToString("|") { parameter ->
    listOf(
        if (parameter.isReadOnly) "readonly" else "normal",
        parameter.typeName
    ).joinToString(":")
}

internal fun symbolIdentityKey(symbol: MCFPPSymbol): String = listOf(
    symbol.uri,
    symbol.name,
    symbol.kind.name,
    symbol.range.start.line,
    symbol.range.start.character,
    symbol.namespaceName.orEmpty(),
    symbol.containerName.orEmpty(),
    symbol.receiverType.orEmpty(),
    parameterSignatureKey(symbol)
).joinToString("|")

internal fun sameSymbol(left: MCFPPSymbol, right: MCFPPSymbol): Boolean {
    if (left.isExternal || right.isExternal) {
        return left.isExternal == right.isExternal &&
            left.name == right.name &&
            left.kind == right.kind &&
            left.namespaceName == right.namespaceName &&
            left.containerName == right.containerName &&
            left.receiverType == right.receiverType &&
            parameterSignatureKey(left) == parameterSignatureKey(right)
    }
    return left.uri == right.uri &&
        left.kind == right.kind &&
        left.range == right.range &&
        left.enclosingTypeScopeId == right.enclosingTypeScopeId &&
        left.enclosingFunctionScopeId == right.enclosingFunctionScopeId
}

internal fun resolveNamespaceQualifier(analysis: MCFPPDocumentAnalysis, qualifier: String): String {
    return analysis.imports.firstOrNull { imported ->
        imported.importedName == "*" && imported.alias == qualifier
    }?.namespace ?: qualifier
}

internal fun lastTopLevelMemberSeparator(expression: String): Int {
    var parentheses = 0
    var brackets = 0
    var angles = 0
    for (index in expression.indices.reversed()) {
        when (expression[index]) {
            ')' -> parentheses++
            '(' -> if (parentheses > 0) parentheses--
            ']' -> brackets++
            '[' -> if (brackets > 0) brackets--
            '>' -> angles++
            '<' -> if (angles > 0) angles--
            '.' -> if (parentheses == 0 && brackets == 0 && angles == 0) return index
        }
    }
    return -1
}
