package top.mcfpp.intellij.lang;

import java.util.ArrayList;
import java.util.List;
import static top.mcfpp.intellij.lang.McfppSourceTokens.Token;

/** Recognizes complete import statements without interpreting comment/string contents as code. */
final class McfppImportLayout {
    record Entry(McfppImport value, int start, int end) {}

    static List<Entry> entries(String source) {
        List<Token> tokens = McfppSourceTokens.lex(source);
        List<Entry> result = new ArrayList<>();
        int braces = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.type() == McfppTokenTypes.LEFT_BRACE) braces++;
            if (token.type() == McfppTokenTypes.RIGHT_BRACE) braces--;
            if (braces != 0 || !token.text().equals("import")) continue;
            int end = i + 1;
            while (end < tokens.size() && tokens.get(end).type() != McfppTokenTypes.SEMICOLON &&
                    !hasNewline(source, tokens.get(end - 1).end(), tokens.get(end).start())) end++;
            boolean semicolon = end < tokens.size() && tokens.get(end).type() == McfppTokenTypes.SEMICOLON;
            McfppImport value = parse(tokens.subList(i + 1, end));
            if (value != null) result.add(new Entry(value, token.start(),
                    semicolon ? tokens.get(end).end() : tokens.get(end - 1).end()));
        }
        return result;
    }

    private static McfppImport parse(List<Token> tokens) {
        StringBuilder namespace = new StringBuilder();
        int i = 0;
        boolean word = true;
        while (i < tokens.size() && !tokens.get(i).text().equals(":")) {
            String text = tokens.get(i++).text();
            if (word ? !McfppNames.isIdentifier(text) : !text.equals(".")) return null;
            namespace.append(text);
            word = !word;
        }
        if (namespace.isEmpty() || word || ++i >= tokens.size()) return null;
        String name = tokens.get(i++).text();
        if (!name.equals("*") && !McfppNames.isIdentifier(name)) return null;
        String alias = null;
        if (i < tokens.size()) {
            if (!tokens.get(i++).text().equals("as") || i >= tokens.size()) return null;
            alias = tokens.get(i++).text();
            if (!McfppNames.isIdentifier(alias)) return null;
        }
        return i == tokens.size() ? new McfppImport(namespace.toString(), name, alias) : null;
    }

    static int insertionOffset(String source) {
        List<Token> tokens = McfppSourceTokens.lex(source);
        int offset = 0;
        for (int i = 0; i < tokens.size();) {
            Token token = tokens.get(i);
            if (token.type() == McfppTokenTypes.COMMENT) {
                offset = afterLine(source, token.end());
                i++;
                continue;
            }
            if (!token.text().equals("namespace") && !token.text().equals("import")) break;
            int end = i + 1;
            while (end < tokens.size() && tokens.get(end).type() != McfppTokenTypes.SEMICOLON &&
                    !hasNewline(source, tokens.get(end - 1).end(), tokens.get(end).start()) &&
                    !tokens.get(end).trivia() && tokens.get(end).type() != McfppTokenTypes.VERSION_DIRECTIVE &&
                    tokens.get(end).type() != McfppTokenTypes.LEFT_BRACE) end++;
            if (token.text().equals("import") && parse(tokens.subList(i + 1, end)) == null) break;
            if (end == i + 1) break;
            boolean semicolon = end < tokens.size() && tokens.get(end).type() == McfppTokenTypes.SEMICOLON;
            offset = afterLine(source, semicolon ? tokens.get(end).end() : tokens.get(end - 1).end());
            i = semicolon ? end + 1 : end;
        }
        return offset;
    }

    // Consume a trailing line comment too, but never skip code on the same line.
    private static int afterLine(String source, int end) {
        int cursor = end;
        while (cursor < source.length() && (source.charAt(cursor) == ' ' || source.charAt(cursor) == '\t')) cursor++;
        if (source.startsWith("//", cursor)) {
            while (cursor < source.length() && source.charAt(cursor) != '\n' && source.charAt(cursor) != '\r') cursor++;
        }
        if (cursor < source.length() && source.charAt(cursor) == '\r') cursor++;
        if (cursor < source.length() && source.charAt(cursor) == '\n') return cursor + 1;
        return end;
    }

    private static boolean hasNewline(String source, int start, int end) {
        return source.substring(start, end).indexOf('\n') >= 0 || source.substring(start, end).indexOf('\r') >= 0;
    }
}
