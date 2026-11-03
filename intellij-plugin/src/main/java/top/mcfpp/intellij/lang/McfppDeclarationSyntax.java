package top.mcfpp.intellij.lang;

import java.util.List;
import java.util.Set;
import java.util.HashSet;

/** Distinguishes declaration annotations from qualified names sharing the colon token. */
final class McfppDeclarationSyntax {
    private static final Set<String> DECLARATIONS = Set.of("var", "const", "external", "data", "enum");

    static boolean isAnnotationColon(CharSequence source, int offset) {
        List<McfppSourceTokens.Token> tokens = McfppSourceTokens.lex(source.subSequence(0, offset).toString())
                .stream().filter(token -> !token.trivia()).toList();
        return isAnnotationAfter(tokens, tokens.size() - 1);
    }

    static Set<Integer> annotationColons(CharSequence source) {
        List<McfppSourceTokens.Token> tokens = McfppSourceTokens.lex(source.toString()).stream()
                .filter(token -> !token.trivia()).toList();
        Set<Integer> offsets = new HashSet<>();
        for (int index = 0; index < tokens.size(); index++) {
            if (tokens.get(index).type() == McfppTokenTypes.COLON && isAnnotationAfter(tokens, index - 1)) {
                offsets.add(tokens.get(index).start());
            }
        }
        return Set.copyOf(offsets);
    }

    static boolean endsWithValueDeclarationName(CharSequence source) {
        List<McfppSourceTokens.Token> tokens = McfppSourceTokens.lex(source.toString()).stream()
                .filter(token -> !token.trivia()).toList();
        int last = tokens.size() - 1;
        if (last < 1 || tokens.get(last).type() != McfppTokenTypes.IDENTIFIER ||
                List.of("data", "enum").contains(tokens.get(last - 1).text())) return false;
        return isAnnotationAfter(tokens, last);
    }

    private static boolean isAnnotationAfter(List<McfppSourceTokens.Token> tokens, int last) {
        if (last < 0) return false;
        var name = tokens.get(last);
        if (name.type() == McfppTokenTypes.RIGHT_PARENTHESIS) return true;
        if (name.type() != McfppTokenTypes.IDENTIFIER || last == 0) return false;
        String before = tokens.get(last - 1).text();
        if (DECLARATIONS.contains(before)) return true;
        if (!List.of("(", ",", "<", "var").contains(before)) return false;
        for (int index = last - 1; index >= 0; index--) {
            String text = tokens.get(index).text();
            if (text.equals("func") || text.equals("constructor") || text.equals("data")) return true;
            if (List.of("{", "}", ";").contains(text)) return false;
        }
        return false;
    }
}
