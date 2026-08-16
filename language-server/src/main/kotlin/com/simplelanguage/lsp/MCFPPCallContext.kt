package com.simplelanguage.lsp

import org.eclipse.lsp4j.Position

internal enum class MCFPPArgumentGroup {
    READ_ONLY,
    NORMAL
}

internal data class MCFPPCallContext(
    val name: String,
    val qualifier: String?,
    val qualifierSeparator: Char?,
    val argumentGroup: MCFPPArgumentGroup,
    val activeParameter: Int,
    val arguments: List<String>,
    val readOnlyArguments: List<String> = emptyList()
)

/** Finds incomplete call expressions without requiring the parser to recover a missing closing delimiter. */
internal object MCFPPCallContextFinder {
    private val nonCallKeywords = setOf(
        "func", "constructor", "if", "else", "while", "for", "do", "try", "store", "execute",
        "return", "data", "interface", "enum", "import", "namespace", "as", "from"
    )

    fun findCandidates(text: String, position: Position): List<MCFPPCallContext> {
        val cursorOffset = MCFPPTextChanges.positionToOffset(text, position)
        if (cursorOffset <= 0) {
            return emptyList()
        }
        val mask = codeMask(text, cursorOffset)
        val expectedOpen = ArrayDeque<Char>()
        val candidates = mutableListOf<MCFPPCallContext>()
        var index = cursorOffset - 1

        while (index >= 0) {
            if (!mask[index]) {
                index -= 1
                continue
            }
            when (val character = text[index]) {
                ')' -> expectedOpen.addLast('(')
                ']' -> expectedOpen.addLast('[')
                '}' -> expectedOpen.addLast('{')
                '>' -> {
                    val angleOpen = matchingOpen(text, index, '<', '>', mask)
                    if (angleOpen >= 0 && parseHead(text, angleOpen, mask) != null) {
                        index = angleOpen - 1
                        continue
                    }
                }
                '(', '[', '{' -> {
                    if (expectedOpen.lastOrNull() == character) {
                        expectedOpen.removeLast()
                    } else if (expectedOpen.isEmpty()) {
                        if (character == '(') {
                            buildContext(text, index, cursorOffset, mask, MCFPPArgumentGroup.NORMAL)?.let(candidates::add)
                        } else {
                            break
                        }
                    }
                }
                '<' -> if (expectedOpen.isEmpty()) {
                    buildContext(text, index, cursorOffset, mask, MCFPPArgumentGroup.READ_ONLY)?.let(candidates::add)
                }
            }
            index -= 1
        }
        return candidates
    }

    private fun buildContext(
        text: String,
        openOffset: Int,
        cursorOffset: Int,
        mask: BooleanArray,
        group: MCFPPArgumentGroup
    ): MCFPPCallContext? {
        val head = parseHead(text, openOffset, mask) ?: return null
        val arguments = splitArguments(text, openOffset + 1, cursorOffset, mask)
        val readOnlyArguments = if (group == MCFPPArgumentGroup.NORMAL) {
            readOnlyArgumentsBefore(text, openOffset, mask)
        } else {
            arguments
        }
        return MCFPPCallContext(
            name = head.name,
            qualifier = head.qualifier,
            qualifierSeparator = head.separator,
            argumentGroup = group,
            activeParameter = (arguments.size - 1).coerceAtLeast(0),
            arguments = arguments,
            readOnlyArguments = readOnlyArguments
        )
    }

    private fun readOnlyArgumentsBefore(text: String, normalOpenOffset: Int, mask: BooleanArray): List<String> {
        val closeOffset = skipWhitespaceBackward(text, normalOpenOffset - 1)
        if (closeOffset < 0 || text[closeOffset] != '>') {
            return emptyList()
        }
        val openOffset = matchingOpen(text, closeOffset, '<', '>', mask)
        if (openOffset < 0 || parseHead(text, openOffset, mask) == null) {
            return emptyList()
        }
        return splitArguments(text, openOffset + 1, closeOffset, mask)
    }

    private data class CallHead(val name: String, val qualifier: String?, val separator: Char?)

    private fun parseHead(text: String, openOffset: Int, mask: BooleanArray): CallHead? {
        var headEnd = openOffset
        var index = skipWhitespaceBackward(text, headEnd - 1)
        if (openOffset < text.length && text[openOffset] == '(' && index >= 0 && text[index] == '>') {
            val genericOpen = matchingOpen(text, index, '<', '>', mask)
            if (genericOpen < 0) {
                return null
            }
            headEnd = genericOpen
            index = skipWhitespaceBackward(text, headEnd - 1)
        }
        if (index < 0 || !isIdentifierPart(text[index])) {
            return null
        }

        val nameEnd = index + 1
        while (index >= 0 && isIdentifierPart(text[index])) {
            index -= 1
        }
        val name = text.substring(index + 1, nameEnd)
        if (name in nonCallKeywords) {
            return null
        }

        val beforeName = skipWhitespaceBackward(text, index)
        val separator = text.getOrNull(beforeName)?.takeIf { it == '.' || it == ':' }
        val qualifier = separator?.let {
            var qualifierEnd = skipWhitespaceBackward(text, beforeName - 1) + 1
            var qualifierStart = qualifierEnd - 1
            while (qualifierStart >= 0 && (isIdentifierPart(text[qualifierStart]) || text[qualifierStart] == '.')) {
                qualifierStart -= 1
            }
            text.substring(qualifierStart + 1, qualifierEnd).takeIf(String::isNotBlank)
        }

        val keywordEnd = if (separator == null) beforeName + 1 else -1
        if (keywordEnd > 0) {
            var keywordIndex = skipWhitespaceBackward(text, keywordEnd - 1)
            val previousWordEnd = keywordIndex + 1
            while (keywordIndex >= 0 && isIdentifierPart(text[keywordIndex])) {
                keywordIndex -= 1
            }
            if (previousWordEnd > keywordIndex + 1 && text.substring(keywordIndex + 1, previousWordEnd) in nonCallKeywords) {
                return null
            }
        }
        return CallHead(name, qualifier, separator)
    }

    private fun splitArguments(text: String, start: Int, end: Int, mask: BooleanArray): List<String> {
        if (start >= end) {
            return listOf("")
        }
        val result = mutableListOf<String>()
        var segmentStart = start
        var parentheses = 0
        var brackets = 0
        var braces = 0
        var angles = 0
        var index = start
        while (index < end) {
            if (!mask[index]) {
                index += 1
                continue
            }
            when (text[index]) {
                '(' -> parentheses += 1
                ')' -> if (parentheses > 0) parentheses -= 1
                '[' -> brackets += 1
                ']' -> if (brackets > 0) brackets -= 1
                '{' -> braces += 1
                '}' -> if (braces > 0) braces -= 1
                '<' -> if (matchingClose(text, index, end, mask) >= 0 && parseHead(text, index, mask) != null) angles += 1
                '>' -> if (angles > 0) angles -= 1
                ',' -> if (parentheses == 0 && brackets == 0 && braces == 0 && angles == 0) {
                    result += text.substring(segmentStart, index)
                    segmentStart = index + 1
                }
            }
            index += 1
        }
        result += text.substring(segmentStart, end)
        return result
    }

    private fun matchingOpen(text: String, closeOffset: Int, open: Char, close: Char, mask: BooleanArray): Int {
        var depth = 1
        var index = closeOffset - 1
        while (index >= 0) {
            if (mask[index]) {
                when (text[index]) {
                    close -> depth += 1
                    open -> {
                        depth -= 1
                        if (depth == 0) return index
                    }
                }
            }
            index -= 1
        }
        return -1
    }

    private fun matchingClose(text: String, openOffset: Int, end: Int, mask: BooleanArray): Int {
        var depth = 1
        var index = openOffset + 1
        while (index < end) {
            if (mask[index]) {
                when (text[index]) {
                    '<' -> depth += 1
                    '>' -> {
                        depth -= 1
                        if (depth == 0) return index
                    }
                }
            }
            index += 1
        }
        return -1
    }

    private fun skipWhitespaceBackward(text: String, start: Int): Int {
        var index = start
        while (index >= 0 && text[index].isWhitespace()) index -= 1
        return index
    }

    private fun isIdentifierPart(character: Char): Boolean = character == '_' || character.isLetterOrDigit()

    private fun codeMask(text: String, end: Int): BooleanArray {
        val mask = BooleanArray(end)
        var index = 0
        while (index < end) {
            when {
                text.startsWith("\"\"\"", index) -> index = skipDelimited(text, index, end, "\"\"\"")
                text[index] == '"' -> index = skipQuoted(text, index, end, '"')
                text[index] == '\'' -> index = skipQuoted(text, index, end, '\'')
                text.startsWith("#{", index) -> index = skipUntil(text, index, end, "}#")
                text.startsWith("###", index) -> {
                    while (index < end && text[index] != '\r' && text[index] != '\n') index += 1
                }
                text.startsWith("##", index) -> index = skipUntil(text, index, end, "##", 2)
                text[index] == '#' -> {
                    while (index < end && text[index] != '\r' && text[index] != '\n') index += 1
                }
                else -> {
                    mask[index] = true
                    index += 1
                }
            }
        }
        return mask
    }

    private fun skipQuoted(text: String, start: Int, end: Int, quote: Char): Int {
        var index = start + 1
        while (index < end) {
            if (text[index] == '\\') {
                index += 2
            } else if (text[index] == quote) {
                return index + 1
            } else {
                index += 1
            }
        }
        return end
    }

    private fun skipDelimited(text: String, start: Int, end: Int, delimiter: String): Int {
        val close = text.indexOf(delimiter, start + delimiter.length)
        return if (close < 0 || close >= end) end else close + delimiter.length
    }

    private fun skipUntil(text: String, start: Int, end: Int, delimiter: String, advance: Int = delimiter.length): Int {
        val close = text.indexOf(delimiter, start + advance)
        return if (close < 0 || close >= end) end else close + delimiter.length
    }
}
