package top.mcfpp.intellij.lang;

import top.mcfpp.api.resources.PackResources;

import java.util.List;
import java.util.regex.Pattern;

/** Reads the annotation prefix of a parameter while preserving its ordinary declaration. */
final class McfppParameterSyntax {
    private static final Pattern RESOURCE_KIND = Pattern.compile("[a-z0-9_]+(?:/[a-z0-9_]+)*");

    record Prefix(int next, String resourceKind) {}
    record Declaration(String text, String resourceKind) {}

    private McfppParameterSyntax() {}

    static Prefix prefix(List<McfppTokenScanner.Token> tokens, int start, int end) {
        int index = start;
        String kind = null;
        boolean seenResource = false;
        while (index < end && tokens.get(index).type() == McfppTokenTypes.ANNOTATION) {
            String annotation = tokens.get(index++).text();
            boolean resource = annotation.equals("@Resource") && !seenResource;
            seenResource |= annotation.equals("@Resource");
            if (index == end || tokens.get(index).type() != McfppTokenTypes.LEFT_PARENTHESIS) continue;
            int open = index++;
            int depth = 1;
            while (index < end && depth > 0) {
                var type = tokens.get(index++).type();
                if (type == McfppTokenTypes.LEFT_PARENTHESIS) depth++;
                if (type == McfppTokenTypes.RIGHT_PARENTHESIS) depth--;
            }
            if (resource && index - open == 3 && depth == 0 &&
                    tokens.get(open + 1).type() == McfppTokenTypes.STRING) {
                String literal = tokens.get(open + 1).text();
                if (literal.length() >= 2) {
                    String value = literal.substring(1, literal.length() - 1);
                    if (RESOURCE_KIND.matcher(value).matches()) kind = PackResources.resourceKind(value);
                }
            }
        }
        return new Prefix(index, kind);
    }

    static Declaration declaration(String parameter) {
        if (!parameter.stripLeading().startsWith("@")) return new Declaration(parameter, null);
        var tokens = McfppTokenScanner.lex(parameter).tokens();
        var prefix = prefix(tokens, 0, tokens.size());
        return new Declaration(prefix.next() == tokens.size() ? "" : parameter.substring(tokens.get(prefix.next()).start()),
                prefix.resourceKind());
    }
}
