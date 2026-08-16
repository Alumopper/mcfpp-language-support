package com.simplelanguage.lsp

object MCFPPDocumentation {
    fun findDocumentationBefore(text: String, declarationLine: Int): String? {
        val lines = text.split("\n")
        if (declarationLine <= 0 || declarationLine > lines.lastIndex + 1) {
            return null
        }

        var index = declarationLine - 1
        while (index >= 0 && lines[index].trim().isBlank()) {
            index--
        }
        while (index >= 0 && lines[index].trim().startsWith("@")) {
            index--
        }
        if (index < 0) {
            return null
        }

        val markerLine = lines[index].trim()
        if (markerLine.startsWith("###")) {
            return findLegacyDocumentation(lines, index)
        }
        if (!lines[index].contains("}#")) {
            return null
        }

        val block = mutableListOf<String>()
        while (index >= 0) {
            val current = lines[index]
            block += current
            if (current.contains("#{")) {
                break
            }
            index--
        }

        if (block.none { it.contains("#{") }) {
            return null
        }

        return normalize(block.asReversed())
    }

    private fun findLegacyDocumentation(lines: List<String>, endIndex: Int): String? {
        var index = endIndex
        val block = mutableListOf<String>()
        while (index >= 0) {
            val current = lines[index]
            block += current
            if (index != endIndex && current.trim().startsWith("###")) {
                break
            }
            index--
        }
        if (block.count { it.trim().startsWith("###") } < 2) {
            return null
        }

        return block.asReversed()
            .drop(1)
            .dropLast(1)
            .joinToString("\n") { it.trim().removePrefix("#").trimStart() }
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun normalize(lines: List<String>): String? {
        val content = lines.mapIndexed { lineIndex, raw ->
            var current = raw
            if (lineIndex == 0) {
                current = current.substringAfter("#{", current)
            }
            if (lineIndex == lines.lastIndex) {
                current = current.substringBefore("}#", current)
            }
            val trimmed = current.trim()
            // Keep markdown heading prefixes (#, ##, ###...) intact.
            if (trimmed.startsWith("# ")) {
                trimmed.removePrefix("# ").trimStart()
            } else {
                trimmed
            }
        }
            .joinToString("\n")
            .trim()
        return content.takeIf { it.isNotBlank() }
    }
}