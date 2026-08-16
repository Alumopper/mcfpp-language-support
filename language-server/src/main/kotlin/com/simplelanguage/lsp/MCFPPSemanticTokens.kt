package com.simplelanguage.lsp

internal object MCFPPSemanticTokenLegend {
    // Keep this order synchronized with the VS Code command-token bridge legend.
    val tokenTypes = listOf(
        "namespace",
        "type",
        "class",
        "enum",
        "interface",
        "struct",
        "typeParameter",
        "parameter",
        "variable",
        "property",
        "enumMember",
        "event",
        "function",
        "method",
        "macro",
        "keyword",
        "modifier",
        "comment",
        "string",
        "number",
        "regexp",
        "operator",
        "decorator",
        "error",
        "escape",
        "literal",
        "resourceLocation",
        "vector"
    )

    val tokenModifiers = listOf(
        "declaration",
        "definition",
        "readonly",
        "static",
        "deprecated",
        "abstract",
        "async",
        "modification",
        "documentation",
        "defaultLibrary"
    )

    fun typeIndex(type: String): Int = tokenTypes.indexOf(type).coerceAtLeast(0)

    fun modifierBits(vararg modifiers: String): Int = modifiers.fold(0) { bits, modifier ->
        val index = tokenModifiers.indexOf(modifier)
        if (index < 0) bits else bits or (1 shl index)
    }
}
