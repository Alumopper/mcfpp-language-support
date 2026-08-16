package top.mcfpp.intellij.lang;

import com.intellij.openapi.editor.colors.TextAttributesKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Context-aware lexical coloring shared by embedded MCFPP commands and .mcfunction files. */
final class McfppCommandSyntax {
    private static final Set<String> LITERALS = Set.of(
            "add", "align", "anchored", "append", "as", "at", "biome", "block", "blocks", "bossbar",
            "center", "clear", "contents", "destroy", "dimension", "disable", "discard", "dummy", "enable",
            "entity", "everything", "facing", "feet", "first", "fixed", "force", "from", "function", "get",
            "give", "grant", "horizontal", "if", "in", "insert", "keep", "last", "list", "masked", "matches",
            "merge", "modify", "mount", "move", "normal", "objectives", "on", "only", "positioned", "prepend",
            "predicate", "query", "remove", "replace", "result", "revoke", "rotated", "run", "score", "set",
            "setdisplay", "spawn", "storage", "strict", "success", "summon", "through", "until", "unless",
            "value", "vertical", "with", "world", "eyes"
    );
    private static final Set<String> BOOLEAN_LITERALS = Set.of("true", "false");

    private McfppCommandSyntax() {
    }

    static List<McfppSemanticTokens.EmbeddedRange> highlight(CharSequence source) {
        return highlightLines(source, true);
    }

    static List<McfppSemanticTokens.EmbeddedRange> highlightMcfunction(CharSequence source) {
        return highlightLines(source, false);
    }

    private static List<McfppSemanticTokens.EmbeddedRange> highlightLines(CharSequence source, boolean requireSlash) {
        List<McfppSemanticTokens.EmbeddedRange> ranges = new ArrayList<>();
        int lineStart = 0;
        while (lineStart < source.length()) {
            int lineEnd = lineEnd(source, lineStart);
            int content = firstNonWhitespace(source, lineStart, lineEnd);
            if (content < lineEnd && source.charAt(content) != '#') {
                if (requireSlash && source.charAt(content) == '/') {
                    highlightLine(source, content + 1, lineEnd, ranges);
                } else if (!requireSlash) {
                    if (source.charAt(content) == '$') {
                        add(ranges, content, content + 1, McfppSyntaxHighlighter.COMMAND_MACRO);
                        content++;
                    }
                    highlightLine(source, content, lineEnd, ranges);
                }
            }
            lineStart = lineEnd;
            if (lineStart < source.length() && source.charAt(lineStart) == '\r') lineStart++;
            if (lineStart < source.length() && source.charAt(lineStart) == '\n') lineStart++;
        }
        return List.copyOf(ranges);
    }

    private static void highlightLine(
            CharSequence source,
            int start,
            int end,
            List<McfppSemanticTokens.EmbeddedRange> ranges
    ) {
        int index = nextNonWhitespace(source, start, end);
        int rootEnd = wordEnd(source, index, end);
        if (rootEnd > index) add(ranges, index, rootEnd, McfppSyntaxHighlighter.COMMAND_ROOT);
        index = rootEnd;
        int squareDepth = 0;
        int curlyDepth = 0;

        while (index < end) {
            char character = source.charAt(index);
            if (Character.isWhitespace(character)) {
                index++;
                continue;
            }
            if (character == '$' && index + 1 < end && source.charAt(index + 1) == '{') {
                index = highlightInterpolation(source, index, end, ranges);
                continue;
            }
            if (character == '$' && index + 1 < end && source.charAt(index + 1) == '(') {
                index = highlightFunctionMacro(source, index, end, ranges);
                continue;
            }
            if (character == '"' || character == '\'') {
                int stringEnd = quotedEnd(source, index, end, character);
                add(ranges, index, stringEnd, McfppSyntaxHighlighter.STRING);
                index = stringEnd;
                continue;
            }
            if (character == '@' && index + 1 < end && "paresn".indexOf(source.charAt(index + 1)) >= 0) {
                add(ranges, index, index + 2, McfppSyntaxHighlighter.TARGET_SELECTOR);
                index += 2;
                continue;
            }
            if (isNumberStart(source, index, end)) {
                int numberEnd = numberEnd(source, index, end);
                TextAttributesKey key = character == '~' || character == '^'
                        ? McfppSyntaxHighlighter.COMMAND_COORDINATE
                        : McfppSyntaxHighlighter.NUMBER;
                add(ranges, index, numberEnd, key);
                index = numberEnd;
                continue;
            }
            if (isWordCharacter(character) || character == '#') {
                int wordEnd = wordEnd(source, index, end);
                String word = source.subSequence(index, wordEnd).toString();
                int next = nextNonWhitespace(source, wordEnd, end);
                boolean property = next < end && (source.charAt(next) == '=' || source.charAt(next) == ':');
                boolean selectorKey = squareDepth > 0 && curlyDepth == 0 && property;
                TextAttributesKey key;
                if (selectorKey) key = McfppSyntaxHighlighter.COMMAND_SELECTOR_KEY;
                else if (property) key = McfppSyntaxHighlighter.COMMAND_PROPERTY;
                else if (BOOLEAN_LITERALS.contains(word)) key = McfppSyntaxHighlighter.BOOLEAN;
                else if (LITERALS.contains(word)) key = McfppSyntaxHighlighter.COMMAND_KEYWORD;
                else if (resourceColon(word) >= 0) key = McfppSyntaxHighlighter.COMMAND_RESOURCE;
                else if (squareDepth > 0 && previousNonWhitespace(source, index, start) == '=') {
                    key = McfppSyntaxHighlighter.COMMAND_SELECTOR_VALUE;
                } else key = McfppSyntaxHighlighter.COMMAND_ARGUMENT;

                int colon = resourceColon(word);
                int namespaceStart = word.startsWith("#") ? 1 : 0;
                if (colon > namespaceStart) {
                    add(ranges, index, index + namespaceStart, McfppSyntaxHighlighter.OPERATOR);
                    add(ranges, index + namespaceStart, wordEnd, McfppSyntaxHighlighter.COMMAND_RESOURCE);
                } else {
                    add(ranges, index, wordEnd, key);
                }
                index = wordEnd;
                continue;
            }

            TextAttributesKey punctuation = switch (character) {
                case '{', '}' -> McfppSyntaxHighlighter.BRACES;
                case '[', ']' -> McfppSyntaxHighlighter.BRACKETS;
                case '(', ')' -> McfppSyntaxHighlighter.PARENTHESES;
                case '=', ':', ',', '!', '~', '^', '#' -> McfppSyntaxHighlighter.OPERATOR;
                default -> null;
            };
            if (punctuation != null) add(ranges, index, index + 1, punctuation);
            if (character == '[') squareDepth++;
            else if (character == ']') squareDepth = Math.max(0, squareDepth - 1);
            else if (character == '{') curlyDepth++;
            else if (character == '}') curlyDepth = Math.max(0, curlyDepth - 1);
            index++;
        }
    }

    private static int highlightInterpolation(
            CharSequence source,
            int start,
            int end,
            List<McfppSemanticTokens.EmbeddedRange> ranges
    ) {
        int interpolationEnd = balancedEnd(source, start + 1, end, '{', '}');
        add(ranges, start, Math.min(start + 2, interpolationEnd), McfppSyntaxHighlighter.COMMAND_MACRO);
        int index = start + 2;
        while (index < interpolationEnd - 1) {
            char character = source.charAt(index);
            if (Character.isJavaIdentifierStart(character)) {
                int identifierEnd = index + 1;
                while (identifierEnd < interpolationEnd && Character.isJavaIdentifierPart(source.charAt(identifierEnd))) identifierEnd++;
                add(ranges, index, identifierEnd, McfppSyntaxHighlighter.VARIABLE);
                index = identifierEnd;
            } else if (isNumberStart(source, index, interpolationEnd)) {
                int numberEnd = numberEnd(source, index, interpolationEnd);
                add(ranges, index, numberEnd, McfppSyntaxHighlighter.NUMBER);
                index = numberEnd;
            } else {
                index++;
            }
        }
        if (interpolationEnd > start + 2) {
            add(ranges, interpolationEnd - 1, interpolationEnd, McfppSyntaxHighlighter.COMMAND_MACRO);
        }
        return interpolationEnd;
    }

    private static int highlightFunctionMacro(
            CharSequence source,
            int start,
            int end,
            List<McfppSemanticTokens.EmbeddedRange> ranges
    ) {
        int macroEnd = balancedEnd(source, start + 1, end, '(', ')');
        boolean closed = macroEnd > start + 2 && source.charAt(macroEnd - 1) == ')';
        int contentEnd = closed ? macroEnd - 1 : macroEnd;
        add(ranges, start, Math.min(start + 2, macroEnd), McfppSyntaxHighlighter.COMMAND_MACRO);
        int index = start + 2;
        while (index < contentEnd) {
            if (Character.isJavaIdentifierStart(source.charAt(index))) {
                int identifierEnd = index + 1;
                while (identifierEnd < contentEnd && Character.isJavaIdentifierPart(source.charAt(identifierEnd))) {
                    identifierEnd++;
                }
                add(ranges, index, identifierEnd, McfppSyntaxHighlighter.VARIABLE);
                index = identifierEnd;
            } else {
                index++;
            }
        }
        if (closed) add(ranges, macroEnd - 1, macroEnd, McfppSyntaxHighlighter.COMMAND_MACRO);
        return macroEnd;
    }

    private static int balancedEnd(CharSequence source, int open, int end, char opening, char closing) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = open; index < end; index++) {
            char character = source.charAt(index);
            if (escaped) escaped = false;
            else if (quote != 0 && character == '\\') escaped = true;
            else if (quote != 0 && character == quote) quote = 0;
            else if (quote == 0 && (character == '"' || character == '\'')) quote = character;
            else if (quote == 0 && character == opening) depth++;
            else if (quote == 0 && character == closing && --depth == 0) return index + 1;
        }
        return end;
    }

    private static int quotedEnd(CharSequence source, int start, int end, char quote) {
        boolean escaped = false;
        for (int index = start + 1; index < end; index++) {
            char character = source.charAt(index);
            if (escaped) escaped = false;
            else if (character == '\\') escaped = true;
            else if (character == quote) return index + 1;
        }
        return end;
    }

    private static boolean isNumberStart(CharSequence source, int index, int end) {
        char character = source.charAt(index);
        if (Character.isDigit(character)) return true;
        if (character == '~' || character == '^') return true;
        return (character == '+' || character == '-') && index + 1 < end && Character.isDigit(source.charAt(index + 1));
    }

    private static int numberEnd(CharSequence source, int start, int end) {
        int index = start;
        if (index < end && (source.charAt(index) == '~' || source.charAt(index) == '^')) index++;
        if (index < end && (source.charAt(index) == '+' || source.charAt(index) == '-')) index++;
        while (index < end) {
            char character = source.charAt(index);
            if (!Character.isDigit(character) && character != '.' && character != 'e' && character != 'E' &&
                    character != '+' && character != '-' && character != 'b' && character != 's' &&
                    character != 'l' && character != 'f' && character != 'd') break;
            index++;
        }
        return index;
    }

    private static int wordEnd(CharSequence source, int start, int end) {
        int index = start;
        while (index < end && (isWordCharacter(source.charAt(index)) || index == start && source.charAt(index) == '#')) index++;
        return index;
    }

    private static boolean isWordCharacter(char character) {
        return Character.isLetterOrDigit(character) || character == '_' || character == '-' || character == '.' ||
                character == ':' || character == '/';
    }

    private static int resourceColon(String word) {
        int start = word.startsWith("#") ? 1 : 0;
        int colon = word.indexOf(':', start);
        return colon > start ? colon : -1;
    }

    private static int lineEnd(CharSequence source, int start) {
        int index = start;
        while (index < source.length() && source.charAt(index) != '\n' && source.charAt(index) != '\r') index++;
        return index;
    }

    private static int firstNonWhitespace(CharSequence source, int start, int end) {
        int index = start;
        while (index < end && (source.charAt(index) == ' ' || source.charAt(index) == '\t' || source.charAt(index) == '\f')) index++;
        return index;
    }

    private static int nextNonWhitespace(CharSequence source, int start, int end) {
        int index = start;
        while (index < end && Character.isWhitespace(source.charAt(index))) index++;
        return index;
    }

    private static char previousNonWhitespace(CharSequence source, int start, int lowerBound) {
        for (int index = start - 1; index >= lowerBound; index--) {
            if (!Character.isWhitespace(source.charAt(index))) return source.charAt(index);
        }
        return 0;
    }

    private static void add(
            List<McfppSemanticTokens.EmbeddedRange> ranges,
            int start,
            int end,
            TextAttributesKey key
    ) {
        if (start < end) ranges.add(new McfppSemanticTokens.EmbeddedRange(start, end, key));
    }
}
