package com.simplelanguage.lsp

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentContentChangeEvent

/** Applies LSP content changes in the order in which the client sent them. */
internal object MCFPPTextChanges {
    fun apply(text: String, changes: List<TextDocumentContentChangeEvent>): String {
        var updatedText = text
        changes.forEach { change ->
            val range = change.range
            updatedText = if (range == null) {
                change.text
            } else {
                val startOffset = positionToOffset(updatedText, range.start)
                val endOffset = positionToOffset(updatedText, range.end).coerceAtLeast(startOffset)
                updatedText.replaceRange(startOffset, endOffset, change.text)
            }
        }
        return updatedText
    }

    fun positionToOffset(text: String, position: Position): Int {
        val targetLine = position.line.coerceAtLeast(0)
        var currentLine = 0
        var lineStart = 0
        var index = 0

        while (currentLine < targetLine && index < text.length) {
            when (text[index]) {
                '\r' -> {
                    if (index + 1 < text.length && text[index + 1] == '\n') {
                        index += 1
                    }
                    currentLine += 1
                    lineStart = index + 1
                }
                '\n' -> {
                    currentLine += 1
                    lineStart = index + 1
                }
            }
            index += 1
        }

        if (currentLine < targetLine) {
            return text.length
        }

        var lineEnd = lineStart
        while (lineEnd < text.length && text[lineEnd] != '\r' && text[lineEnd] != '\n') {
            lineEnd += 1
        }
        val character = position.character.coerceIn(0, lineEnd - lineStart)
        return lineStart + character
    }
}
