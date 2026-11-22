package top.mcfpp.intellij.lang;

import com.intellij.lexer.Lexer;
import com.intellij.psi.tree.IElementType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/** Tokenizes source and supplies shared token range operations for the file model. */
final class McfppTokenScanner {
    static final int MAX_HEADER_TOKENS = 256;

    record Token(IElementType type, String text, int start, int end, boolean newlineBefore) { }
    record Lexed(List<Token> tokens, Map<Integer, String> documentationBefore) { }
    record MatchData(Map<Integer, Integer> braces, Map<Integer, Integer> parentheses) { }

    static MatchData matchDelimiters(List<Token> tokens) {
        Map<Integer, Integer> braces = new HashMap<>();
        Map<Integer, Integer> parentheses = new HashMap<>();
        Deque<Integer> braceStack = new ArrayDeque<>();
        Deque<Integer> parenthesisStack = new ArrayDeque<>();
        for (int index = 0; index < tokens.size(); index++) {
            IElementType type = tokens.get(index).type();
            if (type == McfppTokenTypes.LEFT_BRACE) braceStack.push(index);
            else if (type == McfppTokenTypes.RIGHT_BRACE && !braceStack.isEmpty()) {
                int open = braceStack.pop();
                braces.put(open, index);
                braces.put(index, open);
            } else if (type == McfppTokenTypes.LEFT_PARENTHESIS) parenthesisStack.push(index);
            else if (type == McfppTokenTypes.RIGHT_PARENTHESIS && !parenthesisStack.isEmpty()) {
                int open = parenthesisStack.pop();
                parentheses.put(open, index);
                parentheses.put(index, open);
            }
        }
        return new MatchData(Map.copyOf(braces), Map.copyOf(parentheses));
    }

    static Lexed lex(String source) {
        Lexer lexer = new McfppLexer();
        lexer.start(source);
        List<Token> tokens = new ArrayList<>();
        Map<Integer, String> docs = new LinkedHashMap<>();
        boolean newline = false;
        String pendingDocumentation = null;
        while (lexer.getTokenType() != null) {
            IElementType type = lexer.getTokenType();
            int start = lexer.getTokenStart();
            int end = lexer.getTokenEnd();
            String text = source.substring(start, end);
            if (type == McfppTokenTypes.WHITE_SPACE) {
                newline |= text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
            } else if (type == McfppTokenTypes.DOC_COMMENT) {
                pendingDocumentation = normalizeDocumentation(text);
                newline = true;
            } else if (type != McfppTokenTypes.COMMENT && type != McfppTokenTypes.VERSION_DIRECTIVE) {
                int tokenIndex = tokens.size();
                tokens.add(new Token(type, text, start, end, newline));
                if (pendingDocumentation != null) docs.put(tokenIndex, pendingDocumentation);
                pendingDocumentation = null;
                newline = false;
            }
            lexer.advance();
        }
        return new Lexed(List.copyOf(tokens), Map.copyOf(docs));
    }

    static String normalizeDocumentation(String text) {
        return text.lines().map(line -> {
            String content = line.stripLeading().substring(3);
            return content.startsWith(" ") ? content.substring(1) : content;
        }).collect(java.util.stream.Collectors.joining("\n"));
    }

    static int nextIdentifier(List<Token> tokens, int start, int limit) {
        int end = Math.min(tokens.size(), start + limit);
        for (int index = start; index < end; index++) {
            Token token = tokens.get(index);
            if (token.type() == McfppTokenTypes.IDENTIFIER) return index;
            if (token.type() == McfppTokenTypes.LEFT_BRACE || token.type() == McfppTokenTypes.RIGHT_BRACE ||
                    token.type() == McfppTokenTypes.SEMICOLON) return -1;
        }
        return -1;
    }

    static int previousIdentifier(List<Token> tokens, int start, int lowerBound) {
        for (int index = start; index > lowerBound; index--) {
            if (tokens.get(index).type() == McfppTokenTypes.IDENTIFIER) return index;
        }
        return -1;
    }

    static int nextCode(List<Token> tokens, int index) {
        return index < tokens.size() ? index : -1;
    }

    static int previousCode(List<Token> tokens, int index) {
        return index >= 0 ? index : -1;
    }

    static int findNext(
            List<Token> tokens,
            int start,
            IElementType wanted,
            int limit,
            boolean stopAtSemicolon
    ) {
        int end = Math.min(tokens.size(), start + limit);
        for (int index = start; index < end; index++) {
            IElementType type = tokens.get(index).type();
            if (type == wanted) return index;
            if (type == McfppTokenTypes.RIGHT_BRACE || stopAtSemicolon && type == McfppTokenTypes.SEMICOLON) return -1;
        }
        return -1;
    }

    static int findFunctionParameterOpen(List<Token> tokens, int start, MatchData matches) {
        int end = Math.min(tokens.size(), start + MAX_HEADER_TOKENS);
        int angles = 0;
        for (int index = start; index < end; index++) {
            Token token = tokens.get(index);
            if ("<".equals(token.text())) {
                angles++;
                continue;
            }
            if (">".equals(token.text()) && angles > 0) {
                angles--;
                continue;
            }
            if (token.type() == McfppTokenTypes.LEFT_PARENTHESIS && angles == 0 &&
                    matches.parentheses().containsKey(index)) {
                int previous = previousCode(tokens, index - 1);
                if (previous >= start && tokens.get(previous).type() == McfppTokenTypes.IDENTIFIER) return index;
                if (previous >= start && ">".equals(tokens.get(previous).text())) {
                    int readOnlyOpen = matchingOpeningAngle(tokens, previous, start);
                    int name = previousIdentifier(tokens, readOnlyOpen - 1, start - 1);
                    if (readOnlyOpen >= start && name >= start) return index;
                }
            }
            if (token.type() == McfppTokenTypes.LEFT_BRACE || token.type() == McfppTokenTypes.RIGHT_BRACE ||
                    token.type() == McfppTokenTypes.SEMICOLON) return -1;
        }
        return -1;
    }

    static int matchingOpeningAngle(List<Token> tokens, int close, int lowerBound) {
        int depth = 0;
        for (int index = close; index >= lowerBound; index--) {
            String text = tokens.get(index).text();
            if (">".equals(text)) depth++;
            else if ("<".equals(text) && --depth == 0) return index;
        }
        return -1;
    }

    static int findText(List<Token> tokens, int start, String wanted, int limit, boolean stopAtSemicolon) {
        int end = Math.min(tokens.size(), start + limit);
        for (int index = start; index < end; index++) {
            Token token = tokens.get(index);
            if (wanted.equals(token.text())) return index;
            if (token.type() == McfppTokenTypes.RIGHT_BRACE ||
                    stopAtSemicolon && token.type() == McfppTokenTypes.SEMICOLON) return -1;
        }
        return -1;
    }

    static int findTypeInRange(
            List<Token> tokens,
            int start,
            int end,
            IElementType wanted
    ) {
        for (int index = start; index < end; index++) if (tokens.get(index).type() == wanted) return index;
        return -1;
    }

    static int tokenAtOffset(List<Token> tokens, int offset) {
        for (int index = 0; index < tokens.size(); index++) if (tokens.get(index).start() == offset) return index;
        return -1;
    }

    static String signature(String source, int start, int end) {
        if (start < 0 || end < start || start > source.length()) return "";
        String value = source.substring(start, Math.min(end, source.length()));
        return value.replaceAll("\\s+", " ").strip();
    }
}
