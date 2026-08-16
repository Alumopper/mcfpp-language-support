package com.simplelanguage.lsp

import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.Token
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

object MCFPPFormatter {
    fun format(text: String, options: FormattingOptions, range: Range? = null): List<TextEdit> {
        val lexer = mcfppLexer(CharStreams.fromString(text)).apply {
            removeErrorListeners()
        }
        val tokensByLine = lexer.allTokens
            .filter { it.type != Token.EOF }
            .groupBy { (it.line - 1).coerceAtLeast(0) }
        val lines = text.replace("\r\n", "\n").split('\n')
        val indentationUnit = if (options.isInsertSpaces) {
            " ".repeat(options.tabSize.coerceAtLeast(1))
        } else {
            "\t"
        }
        val selectedLines = selectedLineRange(range, lines.lastIndex)
        val edits = mutableListOf<TextEdit>()
        var depth = 0

        lines.forEachIndexed { lineIndex, line ->
            val lineTokens = tokensByLine[lineIndex].orEmpty()
            val leadingClosingBraces = lineTokens.takeWhile { it.type == mcfppLexer.RCURL }.size
            val lineDepth = (depth - leadingClosingBraces).coerceAtLeast(0)
            val currentIndent = line.takeWhile { it == ' ' || it == '\t' }
            val desiredIndent = if (line.substring(currentIndent.length).isBlank()) {
                ""
            } else {
                indentationUnit.repeat(lineDepth)
            }
            if (lineIndex in selectedLines && currentIndent != desiredIndent) {
                edits += TextEdit(
                    Range(Position(lineIndex, 0), Position(lineIndex, currentIndent.length)),
                    desiredIndent
                )
            }

            val openingBraces = lineTokens.count { it.type == mcfppLexer.LCURL }
            val closingBraces = lineTokens.count { it.type == mcfppLexer.RCURL }
            depth = (depth + openingBraces - closingBraces).coerceAtLeast(0)
        }
        return edits
    }

    private fun selectedLineRange(range: Range?, lastLine: Int): IntRange {
        if (range == null) {
            return 0..lastLine.coerceAtLeast(0)
        }
        val boundedLastLine = lastLine.coerceAtLeast(0)
        val first = range.start.line.coerceIn(0, boundedLastLine)
        val exclusiveEnd = range.end.line.coerceIn(first, boundedLastLine + 1)
        val last = when {
            exclusiveEnd <= first -> first
            range.end.character == 0 -> exclusiveEnd - 1
            else -> exclusiveEnd
        }.coerceIn(first, boundedLastLine)
        return first..last
    }
}
