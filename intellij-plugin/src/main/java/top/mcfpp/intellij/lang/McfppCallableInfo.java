package top.mcfpp.intellij.lang;

import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

record McfppCallableInfo(@NotNull String signature, @NotNull List<TextRange> parameters) {
    static @NotNull McfppCallableInfo from(@NotNull McfppSymbol symbol) {
        return fromSignature(symbol.signature());
    }

    static @NotNull McfppCallableInfo implicitConstructor(@NotNull String typeName) {
        return new McfppCallableInfo(typeName + "()", List.of());
    }

    static @NotNull McfppCallableInfo fromSignature(@NotNull String signature) {
        int open = signature.indexOf('(');
        if (open < 0) return new McfppCallableInfo(signature, List.of());
        int close = matchingParenthesis(signature, open);
        if (close < 0) close = signature.length();

        List<TextRange> ranges = new ArrayList<>();
        int segmentStart = open + 1;
        int parentheses = 0;
        int brackets = 0;
        int braces = 0;
        int angles = 0;
        for (int index = segmentStart; index <= close; index++) {
            char value = index == close ? ',' : signature.charAt(index);
            if (value == '(') parentheses++;
            else if (value == ')' && parentheses > 0) parentheses--;
            else if (value == '[') brackets++;
            else if (value == ']' && brackets > 0) brackets--;
            else if (value == '{') braces++;
            else if (value == '}' && braces > 0) braces--;
            else if (value == '<') angles++;
            else if (value == '>' && angles > 0) angles--;
            else if (value == ',' && parentheses == 0 && brackets == 0 && braces == 0 && angles == 0) {
                int start = segmentStart;
                int end = index;
                while (start < end && Character.isWhitespace(signature.charAt(start))) start++;
                while (end > start && Character.isWhitespace(signature.charAt(end - 1))) end--;
                if (start < end) ranges.add(new TextRange(start, end));
                segmentStart = index + 1;
            }
        }
        return new McfppCallableInfo(signature, List.copyOf(ranges));
    }

    private static int matchingParenthesis(String signature, int open) {
        int depth = 0;
        for (int index = open; index < signature.length(); index++) {
            char value = signature.charAt(index);
            if (value == '(') depth++;
            else if (value == ')' && --depth == 0) return index;
        }
        return -1;
    }
}
