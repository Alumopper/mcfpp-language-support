package top.mcfpp.intellij.lang;

import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Small type-name helpers for editor features that do not need compiler-level type checking. */
final class McfppTypes {
    private McfppTypes() {
    }

    static @Nullable String declaredType(McfppSymbol symbol) {
        String signature = symbol.signature();
        int as = wordIndex(signature, "as");
        if (as < 0) return null;
        int cursor = as + 2;
        while (cursor < signature.length() && Character.isWhitespace(signature.charAt(cursor))) cursor++;
        int start = cursor;
        while (cursor < signature.length()) {
            char value = signature.charAt(cursor);
            if (!(Character.isUnicodeIdentifierPart(value) || value == '_' || value == '$' || value == '.' || value == ':')) {
                break;
            }
            cursor++;
        }
        if (cursor == start) return null;
        String type = signature.substring(start, cursor);
        int colon = type.lastIndexOf(':');
        return colon < 0 ? type : type.substring(colon + 1);
    }

    static List<String> parameterTypes(McfppSymbol symbol) {
        McfppCallableInfo callable = McfppCallableInfo.from(symbol);
        return callable.parameters().stream()
                .map(range -> parameterType(callable.signature(), range))
                .toList();
    }

    private static String parameterType(String signature, TextRange range) {
        String parameter = range.substring(signature).strip();
        int assignment = parameter.indexOf('=');
        if (assignment >= 0) parameter = parameter.substring(0, assignment).stripTrailing();
        int as = wordIndex(parameter, "as");
        String type = as < 0 ? parameter : parameter.substring(as + 2).strip();
        int space = type.lastIndexOf(' ');
        if (space >= 0) type = type.substring(space + 1);
        int colon = type.lastIndexOf(':');
        return colon < 0 ? type : type.substring(colon + 1);
    }

    private static int wordIndex(String text, String word) {
        for (int index = 0; index <= text.length() - word.length(); index++) {
            if (!text.regionMatches(index, word, 0, word.length())) continue;
            boolean left = index == 0 || !Character.isUnicodeIdentifierPart(text.charAt(index - 1));
            int end = index + word.length();
            boolean right = end == text.length() || !Character.isUnicodeIdentifierPart(text.charAt(end));
            if (left && right) return index;
        }
        return -1;
    }
}
