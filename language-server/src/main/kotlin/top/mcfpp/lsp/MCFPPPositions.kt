package top.mcfpp.lsp

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

internal fun offsetToPosition(text: String, offset: Int): Position {
    val boundedOffset = offset.coerceIn(0, text.length)
    var line = 0
    var character = 0
    for (index in 0 until boundedOffset) {
        if (text[index] == '\n') {
            line += 1
            character = 0
        } else if (text[index] != '\r') {
            character += 1
        }
    }
    return Position(line, character)
}

internal fun containsPosition(range: Range, position: Position): Boolean {
    return !isBefore(position, range.start) && !isBefore(range.end, position)
}

internal fun isBeforeOrEqual(left: Position, right: Position): Boolean {
    return left.line < right.line || (left.line == right.line && left.character <= right.character)
}

private fun isBefore(left: Position, right: Position): Boolean {
    return left.line < right.line || (left.line == right.line && left.character < right.character)
}
