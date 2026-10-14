package com.simplelanguage.lsp

object MCFPPDocumentation {
    fun findDocumentationBefore(text: String, declarationLine: Int): String? {
        val lines = text.split("\n")
        if (declarationLine <= 0 || declarationLine > lines.size) return null
        var index = declarationLine - 1
        while (index >= 0 && (lines[index].isBlank() || lines[index].trimStart().startsWith("@"))) index--
        val content = mutableListOf<String>()
        while (index >= 0 && lines[index].trimStart().startsWith("///")) {
            val line = lines[index].trimStart().removePrefix("///").removePrefix(" ").removeSuffix("\r")
            content += line
            index--
        }
        return content.asReversed().joinToString("\n").trim('\r', '\n').takeIf { it.isNotBlank() }
    }
}
