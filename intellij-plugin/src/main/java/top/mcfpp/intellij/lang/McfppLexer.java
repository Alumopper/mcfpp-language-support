package top.mcfpp.intellij.lang;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.mcfpp.language.VersionPreprocessor;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class McfppLexer extends LexerBase {
    private static final Set<String> CONTROL_KEYWORDS = Set.of(
            "if", "else", "while", "for", "do", "try", "context", "break", "continue", "return", "store"
    );
    private static final Set<String> DECLARATION_KEYWORDS = Set.of(
            "import", "object", "data", "entity", "func", "enum", "operator", "typealias", "constructor",
            "new", "global", "var", "const", "external", "get", "set", "namespace"
    );
    private static final Set<String> MODIFIER_KEYWORDS = Set.of(
            "extends", "native", "concrete", "final", "public", "protected", "private", "override",
            "abstract", "impl", "inline"
    );
    private static final Set<String> TYPE_KEYWORDS = Set.of(
            "vec", "int", "bool", "byte", "short", "long", "float", "double", "selector", "string",
            "text", "nbt", "any", "void", "list", "map", "dict", "type", "Type", "ByteArray", "IntArray", "LongArray", "ScoreRef", "NBTRef", "EntityRef"
    );
    private static final Set<String> KEYWORDS = Set.of(
            "this", "super", "as", "from"
    );
    private static final String[] MULTI_CHARACTER_OPERATORS = {
            "...", "++", "--", "&&", "||", "+=", "-=", "*=", "/=", "%=", "=>", "->", "..", "::",
            "<=", ">=", "!=", "==", "~=", "?:", "!!"
    };

    private CharSequence buffer = "";
    private int bufferEnd;
    private int tokenStart;
    private int tokenEnd;
    private IElementType tokenType;
    private final Map<Integer, McfppCommandBlocks.Kind> commandBlockLines = new HashMap<>();
    private final ArrayDeque<Interpolation> interpolations = new ArrayDeque<>();
    private int tokenState;

    private static final class Interpolation {
        final boolean multiline;
        int braceDepth = -1; // A negative depth means the lexer is reading literal text.

        Interpolation(boolean multiline) {
            this.multiline = multiline;
        }
    }

    private record StringEnd(int offset, boolean interpolation) {}

    @Override
    public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        this.buffer = buffer;
        this.bufferEnd = endOffset;
        indexCommandBlocks();
        interpolations.clear();
        this.tokenStart = initialState == 0 ? startOffset : 0;
        while (tokenStart < startOffset) {
            locateToken();
            tokenStart = tokenEnd;
        }
        locateToken();
    }

    /**
     * Command blocks are a lexical mode in the compiler. Rebuild the line map on each
     * start so incremental highlighting does not depend on lexer state restoration.
     */
    private void indexCommandBlocks() {
        commandBlockLines.clear();
        for (McfppCommandBlocks.Line line : McfppCommandBlocks.lines(buffer)) {
            if (line.kind() != McfppCommandBlocks.Kind.ORDINARY || line.insideBlock()) {
                commandBlockLines.put(line.contentStart(), line.kind());
            }
        }
    }

    @Override
    public int getState() {
        return tokenState;
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
        tokenState = interpolations.isEmpty() ? 0 : 1;
        if (tokenStart >= bufferEnd) {
            tokenEnd = tokenStart;
            tokenType = null;
            return;
        }

        char current = buffer.charAt(tokenStart);
        Interpolation interpolation = interpolations.peek();
        if (interpolation != null && interpolation.braceDepth < 0) {
            if (startsWith(tokenStart, "${")) {
                interpolation.braceDepth = 0;
                tokenEnd = tokenStart + 2;
                tokenType = McfppTokenTypes.INTERPOLATION_DELIMITER;
                return;
            }
            StringEnd end = consumeInterpolatedText(tokenStart, interpolation.multiline);
            tokenEnd = end.offset();
            if (!end.interpolation()) interpolations.pop();
            if (tokenEnd > tokenStart) {
                tokenType = McfppTokenTypes.STRING;
                return;
            }
            interpolation = null;
        }
        if (interpolation != null) {
            if (current == '{') interpolation.braceDepth++;
            if (current == '}' && interpolation.braceDepth-- == 0) {
                tokenEnd = tokenStart + 1;
                tokenType = McfppTokenTypes.INTERPOLATION_DELIMITER;
                return;
            }
        }

        if (Character.isWhitespace(current)) {
            tokenEnd = consumeWhile(tokenStart + 1, character -> Character.isWhitespace(character));
            tokenType = McfppTokenTypes.WHITE_SPACE;
            return;
        }

        McfppCommandBlocks.Kind blockLine = interpolation == null ? commandBlockLines.get(tokenStart) : null;
        if (blockLine == McfppCommandBlocks.Kind.OPEN) {
            tokenEnd = tokenStart + 2;
            tokenType = McfppTokenTypes.COMMAND_BLOCK_OPEN;
            return;
        }
        if (blockLine == McfppCommandBlocks.Kind.CLOSE) {
            tokenEnd = tokenStart + 1;
            tokenType = McfppTokenTypes.RIGHT_BRACE;
            return;
        }
        if (blockLine == McfppCommandBlocks.Kind.COMMAND) {
            tokenEnd = consumeLine(tokenStart);
            tokenType = McfppTokenTypes.COMMAND;
            return;
        }

        if (current == '#') {
            int lineEnd = consumeLine(tokenStart);
            if (isAtLineStart(tokenStart) && VersionPreprocessor.isDirective(buffer.subSequence(tokenStart, lineEnd).toString())) {
                tokenEnd = lineEnd;
                tokenType = McfppTokenTypes.VERSION_DIRECTIVE;
                return;
            }
        }

        if (startsWith(tokenStart, "//")) {
            tokenEnd = consumeLine(tokenStart + 2);
            tokenType = startsWith(tokenStart, "///") ? McfppTokenTypes.DOC_COMMENT : McfppTokenTypes.COMMENT;
            if (tokenType == McfppTokenTypes.DOC_COMMENT) {
                int next = tokenEnd;
                while (next < bufferEnd) {
                    if (buffer.charAt(next) == '\r') next++;
                    if (next >= bufferEnd || buffer.charAt(next) != '\n') break;
                    next++;
                    while (next < bufferEnd && (buffer.charAt(next) == ' ' || buffer.charAt(next) == '\t')) next++;
                    if (!startsWith(next, "///")) break;
                    tokenEnd = consumeLine(next + 3);
                    next = tokenEnd;
                }
            }
            return;
        }

        // Lines starting with `/` inside a command block are ordinary MCFPP statements,
        // not raw command lines. The compiler removes that slash before parsing.
        if (blockLine == McfppCommandBlocks.Kind.ORDINARY && current == '/' && isAtLineStart(tokenStart)) {
            tokenEnd = tokenStart + 1;
            tokenType = McfppTokenTypes.WHITE_SPACE;
            return;
        }
        if (interpolation == null && current == '/' && isAtLineStart(tokenStart)) {
            tokenEnd = consumeLine(tokenStart + 1);
            tokenType = McfppTokenTypes.COMMAND;
            return;
        }

        if (current == '"') {
            boolean multiline = startsWith(tokenStart, "\"\"\"");
            StringEnd end = consumeInterpolatedText(tokenStart + (multiline ? 3 : 1), multiline);
            tokenEnd = end.offset();
            if (end.interpolation()) interpolations.push(new Interpolation(multiline));
            tokenType = McfppTokenTypes.STRING;
            return;
        }
        if (current == '\'') {
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
            int previous = tokenStart - 1;
            while (previous >= 0 && Character.isWhitespace(buffer.charAt(previous))) previous--;
            IElementType keyword = keywordType(text);
            tokenType = keyword != McfppTokenTypes.IDENTIFIER && previous >= 0 && buffer.charAt(previous) == '.'
                    ? McfppTokenTypes.IDENTIFIER : keyword;
            return;
        }

        if (startsWith(tokenStart, "?.")) {
            tokenEnd = tokenStart + 2;
            tokenType = McfppTokenTypes.DOT;
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
        int offset = start + 1;
        boolean escaped = false;
        while (offset < bufferEnd) {
            // An unfinished single-line string must not swallow the next line while typing.
            if (buffer.charAt(offset) == '\r' || buffer.charAt(offset) == '\n') break;
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

    private StringEnd consumeInterpolatedText(int offset, boolean multiline) {
        while (offset < bufferEnd) {
            char current = buffer.charAt(offset);
            if (startsWith(offset, "${")) return new StringEnd(offset, true);
            if (multiline) {
                if (startsWith(offset, "\"\"\"")) {
                    return new StringEnd(consumeWhile(offset + 3, character -> character == '"'), false);
                }
            } else {
                if (current == '"') return new StringEnd(offset + 1, false);
                if (current == '\r' || current == '\n') break;
                if (current == '\\' && offset + 1 < bufferEnd) {
                    char next = buffer.charAt(offset + 1);
                    if (next == '\\' || next == '"' || startsWith(offset + 1, "${")) offset++;
                }
            }
            offset++;
        }
        return new StringEnd(offset, false);
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
