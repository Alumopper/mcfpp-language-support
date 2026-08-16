package top.mcfpp.intellij.lang;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public final class McfppLexer extends LexerBase {
    private static final Set<String> CONTROL_KEYWORDS = Set.of(
            "if", "else", "while", "for", "do", "try", "execute", "break", "continue", "return", "store"
    );
    private static final Set<String> DECLARATION_KEYWORDS = Set.of(
            "import", "object", "data", "func", "enum", "operator", "typealias", "constructor",
            "global", "var", "get", "set", "namespace"
    );
    private static final Set<String> MODIFIER_KEYWORDS = Set.of(
            "static", "extends", "native", "concrete", "final", "public", "protected", "private", "override",
            "abstract", "impl", "const", "dynamic", "inline"
    );
    private static final Set<String> TYPE_KEYWORDS = Set.of(
            "vec", "int", "entity", "bool", "byte", "short", "long", "float", "double", "selector", "string",
            "text", "nbt", "any", "void", "list", "map", "dict", "Type", "ByteArray", "IntArray", "LongArray"
    );
    private static final Set<String> KEYWORDS = Set.of(
            "this", "super", "as", "from"
    );
    private static final String[] MULTI_CHARACTER_OPERATORS = {
            "...", "++", "--", "&&", "||", "+=", "-=", "*=", "/=", "%=", "->", "=>", "..", "::",
            "<=", ">=", "!=", "==", "~="
    };

    private CharSequence buffer = "";
    private int bufferEnd;
    private int tokenStart;
    private int tokenEnd;
    private IElementType tokenType;

    @Override
    public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        this.buffer = buffer;
        this.bufferEnd = endOffset;
        this.tokenStart = startOffset;
        locateToken();
    }

    @Override
    public int getState() {
        return 0;
    }

    @Override
    public @Nullable IElementType getTokenType() {
        return tokenType;
    }

    @Override
    public int getTokenStart() {
        return tokenStart;
    }

    @Override
    public int getTokenEnd() {
        return tokenEnd;
    }

    @Override
    public void advance() {
        tokenStart = tokenEnd;
        locateToken();
    }

    @Override
    public @NotNull CharSequence getBufferSequence() {
        return buffer;
    }

    @Override
    public int getBufferEnd() {
        return bufferEnd;
    }

    private void locateToken() {
        if (tokenStart >= bufferEnd) {
            tokenEnd = tokenStart;
            tokenType = null;
            return;
        }

        char current = buffer.charAt(tokenStart);
        if (Character.isWhitespace(current)) {
            tokenEnd = consumeWhile(tokenStart + 1, character -> Character.isWhitespace(character));
            tokenType = McfppTokenTypes.WHITE_SPACE;
            return;
        }

        if (current == '#') {
            tokenEnd = consumeComment(tokenStart);
            tokenType = startsWith(tokenStart, "#{") || startsWith(tokenStart, "###")
                    ? McfppTokenTypes.DOC_COMMENT
                    : McfppTokenTypes.COMMENT;
            return;
        }

        if (current == '/' && isAtLineStart(tokenStart)) {
            tokenEnd = consumeLine(tokenStart + 1);
            tokenType = McfppTokenTypes.COMMAND;
            return;
        }

        if (current == '\'' || current == '"') {
            tokenEnd = consumeString(tokenStart, current);
            tokenType = McfppTokenTypes.STRING;
            return;
        }

        if (Character.isDigit(current)) {
            tokenEnd = consumeNumber(tokenStart);
            tokenType = McfppTokenTypes.NUMBER;
            return;
        }

        if (current == '@' && tokenStart + 1 < bufferEnd && isIdentifierStart(buffer.charAt(tokenStart + 1))) {
            tokenEnd = consumeWhile(tokenStart + 2, McfppLexer::isIdentifierPart);
            String text = buffer.subSequence(tokenStart, tokenEnd).toString();
            tokenType = text.length() == 2 && "arpse".indexOf(text.charAt(1)) >= 0
                    ? McfppTokenTypes.TARGET_SELECTOR
                    : McfppTokenTypes.ANNOTATION;
            return;
        }

        if (isIdentifierStart(current)) {
            tokenEnd = consumeWhile(tokenStart + 1, McfppLexer::isIdentifierPart);
            String text = buffer.subSequence(tokenStart, tokenEnd).toString();
            tokenType = keywordType(text);
            return;
        }

        for (String operator : MULTI_CHARACTER_OPERATORS) {
            if (startsWith(tokenStart, operator)) {
                tokenEnd = tokenStart + operator.length();
                tokenType = McfppTokenTypes.OPERATOR;
                return;
            }
        }

        tokenEnd = tokenStart + 1;
        tokenType = switch (current) {
            case '{' -> McfppTokenTypes.LEFT_BRACE;
            case '}' -> McfppTokenTypes.RIGHT_BRACE;
            case '[' -> McfppTokenTypes.LEFT_BRACKET;
            case ']' -> McfppTokenTypes.RIGHT_BRACKET;
            case '(' -> McfppTokenTypes.LEFT_PARENTHESIS;
            case ')' -> McfppTokenTypes.RIGHT_PARENTHESIS;
            case ',' -> McfppTokenTypes.COMMA;
            case '.' -> McfppTokenTypes.DOT;
            case ':' -> McfppTokenTypes.COLON;
            case ';' -> McfppTokenTypes.SEMICOLON;
            case '*', '%', '/', '+', '-', '!', '=', '@', '?', '<', '>', '&', '|', '~', '^' -> McfppTokenTypes.OPERATOR;
            default -> McfppTokenTypes.BAD_CHARACTER;
        };
    }

    private static IElementType keywordType(String text) {
        if (CONTROL_KEYWORDS.contains(text)) return McfppTokenTypes.CONTROL_KEYWORD;
        if (DECLARATION_KEYWORDS.contains(text)) return McfppTokenTypes.DECLARATION_KEYWORD;
        if (MODIFIER_KEYWORDS.contains(text)) return McfppTokenTypes.MODIFIER_KEYWORD;
        if (TYPE_KEYWORDS.contains(text) || text.startsWith("vec") && text.length() > 3 && digitsOnly(text, 3)) {
            return McfppTokenTypes.TYPE_KEYWORD;
        }
        if (text.equals("true") || text.equals("false")) return McfppTokenTypes.BOOLEAN_LITERAL;
        if (text.equals("null")) return McfppTokenTypes.NULL_LITERAL;
        return KEYWORDS.contains(text) ? McfppTokenTypes.KEYWORD : McfppTokenTypes.IDENTIFIER;
    }

    private static boolean digitsOnly(String text, int start) {
        for (int index = start; index < text.length(); index++) {
            if (!Character.isDigit(text.charAt(index))) return false;
        }
        return true;
    }

    private boolean isAtLineStart(int offset) {
        for (int index = offset - 1; index >= 0; index--) {
            char character = buffer.charAt(index);
            if (character == '\n' || character == '\r') return true;
            if (character != ' ' && character != '\t' && character != '\f') return false;
        }
        return true;
    }

    private int consumeComment(int start) {
        if (startsWith(start, "#{")) {
            return consumeUntil(start + 2, "}#");
        }
        if (startsWith(start, "###")) {
            int closing = indexOf("###", start + 3);
            return closing >= 0 ? closing + 3 : consumeLine(start + 3);
        }
        if (startsWith(start, "##")) {
            return consumeUntil(start + 2, "##");
        }
        return consumeLine(start + 1);
    }

    private int consumeNumber(int start) {
        int offset = start;
        if (startsWith(start, "0x") || startsWith(start, "0X")) {
            offset = start + 2;
            while (offset < bufferEnd && Character.digit(buffer.charAt(offset), 16) >= 0) offset++;
            return offset;
        }

        while (offset < bufferEnd && Character.isDigit(buffer.charAt(offset))) offset++;
        if (offset + 1 < bufferEnd && buffer.charAt(offset) == '.' && buffer.charAt(offset + 1) != '.' &&
                Character.isDigit(buffer.charAt(offset + 1))) {
            offset += 2;
            while (offset < bufferEnd && Character.isDigit(buffer.charAt(offset))) offset++;
        }

        if (offset < bufferEnd && (buffer.charAt(offset) == 'e' || buffer.charAt(offset) == 'E')) {
            int exponentEnd = offset + 1;
            if (exponentEnd < bufferEnd && (buffer.charAt(exponentEnd) == '+' || buffer.charAt(exponentEnd) == '-')) {
                exponentEnd++;
            }
            int exponentDigits = exponentEnd;
            while (exponentEnd < bufferEnd && Character.isDigit(buffer.charAt(exponentEnd))) exponentEnd++;
            if (exponentEnd > exponentDigits) offset = exponentEnd;
        }

        if (offset < bufferEnd && "bBsSlLfFdD".indexOf(buffer.charAt(offset)) >= 0) offset++;
        return offset;
    }

    private int consumeString(int start, char quote) {
        if (quote == '"' && startsWith(start, "\"\"\"")) {
            return consumeUntil(start + 3, "\"\"\"");
        }
        int offset = start + 1;
        boolean escaped = false;
        while (offset < bufferEnd) {
            char character = buffer.charAt(offset++);
            if (character == quote && !escaped) {
                break;
            }
            escaped = character == '\\' && !escaped;
            if (character != '\\') {
                escaped = false;
            }
        }
        return offset;
    }

    private int consumeUntil(int offset, String delimiter) {
        int closing = indexOf(delimiter, offset);
        return closing >= 0 ? closing + delimiter.length() : bufferEnd;
    }

    private int consumeLine(int offset) {
        while (offset < bufferEnd && buffer.charAt(offset) != '\n' && buffer.charAt(offset) != '\r') {
            offset++;
        }
        return offset;
    }

    private int consumeWhile(int offset, CharacterPredicate predicate) {
        while (offset < bufferEnd && predicate.test(buffer.charAt(offset))) {
            offset++;
        }
        return offset;
    }

    private boolean startsWith(int offset, String value) {
        if (offset + value.length() > bufferEnd) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (buffer.charAt(offset + index) != value.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private int indexOf(String value, int fromOffset) {
        int lastStart = bufferEnd - value.length();
        for (int offset = fromOffset; offset <= lastStart; offset++) {
            if (startsWith(offset, value)) {
                return offset;
            }
        }
        return -1;
    }

    private static boolean isIdentifierStart(char character) {
        return Character.isUnicodeIdentifierStart(character) || character == '_' || character == '$';
    }

    private static boolean isIdentifierPart(char character) {
        return Character.isUnicodeIdentifierPart(character) || character == '_' || character == '$';
    }

    @FunctionalInterface
    private interface CharacterPredicate {
        boolean test(char character);
    }
}
