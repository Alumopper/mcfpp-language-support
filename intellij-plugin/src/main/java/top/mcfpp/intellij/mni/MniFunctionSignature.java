package top.mcfpp.intellij.mni;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The part of a native MCFPP declaration used by the compiler to select an {@code @MNIFunction}. */
record MniFunctionSignature(
        @NotNull String name,
        @NotNull List<String> readOnlyTypes,
        @NotNull List<String> normalTypes,
        @NotNull String returnType
) {
    private static final Pattern FUNCTION_NAME = Pattern.compile(
            "\\bfunc\\s+([A-Za-z_$][A-Za-z0-9_$]*)"
    );

    MniFunctionSignature {
        readOnlyTypes = List.copyOf(readOnlyTypes);
        normalTypes = List.copyOf(normalTypes);
    }

    static Optional<MniFunctionSignature> from(
            @NotNull CharSequence source,
            @NotNull MniJavaTargetParser.Target target
    ) {
        if (target.kind() != MniJavaTargetParser.Kind.JAVA_METHOD) return Optional.empty();
        int start = Math.max(0, Math.min(target.declarationStartOffset(), source.length()));
        int end = Math.max(start, Math.min(target.startOffset(), source.length()));
        String header = source.subSequence(start, end).toString();
        int assignment = header.lastIndexOf('=');
        if (assignment >= 0) header = header.substring(0, assignment);

        Matcher function = FUNCTION_NAME.matcher(header);
        if (!function.find()) return Optional.empty();
        int cursor = skipWhitespace(header, function.end());

        List<String> readOnly = List.of();
        if (cursor < header.length() && header.charAt(cursor) == '<') {
            int close = matchingDelimiter(header, cursor, '<', '>');
            if (close < 0) return Optional.empty();
            readOnly = parameterTypes(header.substring(cursor + 1, close));
            cursor = skipWhitespace(header, close + 1);
        }
        if (cursor >= header.length() || header.charAt(cursor) != '(') return Optional.empty();
        int normalClose = matchingDelimiter(header, cursor, '(', ')');
        if (normalClose < 0) return Optional.empty();
        List<String> normal = parameterTypes(header.substring(cursor + 1, normalClose));

        cursor = skipWhitespace(header, normalClose + 1);
        String returnType = "void";
        if (cursor + 1 < header.length() && header.charAt(cursor) == '-' && header.charAt(cursor + 1) == '>') {
            returnType = normalizeType(header.substring(cursor + 2));
            if (returnType.isEmpty()) returnType = "void";
        }
        return Optional.of(new MniFunctionSignature(function.group(1), readOnly, normal, returnType));
    }

    boolean parametersMatch(MniJavaAnnotation annotation) {
        return readOnlyTypes.equals(annotation.readOnlyTypes()) && normalTypes.equals(annotation.normalTypes());
    }

    int javaArgumentCount() {
        return readOnlyTypes.size() + normalTypes.size() + ("void".equals(returnType) ? 0 : 1);
    }

    String parametersText() {
        String readOnly = readOnlyTypes.isEmpty() ? "" : "<" + String.join(", ", readOnlyTypes) + ">";
        return readOnly + "(" + String.join(", ", normalTypes) + ")";
    }

    private static List<String> parameterTypes(String parameters) {
        List<String> result = new ArrayList<>();
        for (String parameter : splitTopLevel(parameters)) {
            String type = parameterType(parameter);
            if (!type.isEmpty()) result.add(type);
        }
        return List.copyOf(result);
    }

    private static String parameterType(String parameter) {
        int assignment = topLevelCharacter(parameter, '=');
        String declaration = assignment < 0 ? parameter : parameter.substring(0, assignment);
        int as = topLevelWord(declaration, "as");
        if (as >= 0) return normalizeType(declaration.substring(as + 2));

        String withoutModifiers = declaration.strip();
        boolean changed;
        do {
            changed = false;
            for (String modifier : List.of("static", "var")) {
                if (startsWithWord(withoutModifiers, modifier)) {
                    withoutModifiers = withoutModifiers.substring(modifier.length()).stripLeading();
                    changed = true;
                }
            }
        } while (changed);
        return normalizeType(withoutModifiers);
    }

    private static List<String> splitTopLevel(String text) {
        List<String> result = new ArrayList<>();
        int start = 0;
        int angle = 0;
        int parentheses = 0;
        int brackets = 0;
        int braces = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (value == '\\') escaped = true;
                else if (value == quote) quote = 0;
                continue;
            }
            if (value == '\'' || value == '"') {
                quote = value;
                continue;
            }
            switch (value) {
                case '<' -> angle++;
                case '>' -> angle = Math.max(0, angle - 1);
                case '(' -> parentheses++;
                case ')' -> parentheses = Math.max(0, parentheses - 1);
                case '[' -> brackets++;
                case ']' -> brackets = Math.max(0, brackets - 1);
                case '{' -> braces++;
                case '}' -> braces = Math.max(0, braces - 1);
                case ',' -> {
                    if (angle == 0 && parentheses == 0 && brackets == 0 && braces == 0) {
                        result.add(text.substring(start, index));
                        start = index + 1;
                    }
                }
                default -> {
                }
            }
        }
        if (start < text.length() || !text.isBlank()) result.add(text.substring(start));
        return result;
    }

    private static int matchingDelimiter(String text, int openOffset, char open, char close) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = openOffset; index < text.length(); index++) {
            char value = text.charAt(index);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (value == '\\') escaped = true;
                else if (value == quote) quote = 0;
                continue;
            }
            if (value == '\'' || value == '"') {
                quote = value;
                continue;
            }
            if (value == open) depth++;
            else if (value == close && --depth == 0) return index;
        }
        return -1;
    }

    private static int topLevelCharacter(String text, char wanted) {
        List<String> beforeAndAfter = splitOnTopLevelCharacter(text, wanted);
        return beforeAndAfter.size() == 2 ? beforeAndAfter.getFirst().length() : -1;
    }

    private static List<String> splitOnTopLevelCharacter(String text, char wanted) {
        int angle = 0;
        int parentheses = 0;
        int brackets = 0;
        int braces = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (value == '\\') escaped = true;
                else if (value == quote) quote = 0;
                continue;
            }
            if (value == '\'' || value == '"') {
                quote = value;
                continue;
            }
            if (value == wanted && angle == 0 && parentheses == 0 && brackets == 0 && braces == 0) {
                return List.of(text.substring(0, index), text.substring(index + 1));
            }
            switch (value) {
                case '<' -> angle++;
                case '>' -> angle = Math.max(0, angle - 1);
                case '(' -> parentheses++;
                case ')' -> parentheses = Math.max(0, parentheses - 1);
                case '[' -> brackets++;
                case ']' -> brackets = Math.max(0, brackets - 1);
                case '{' -> braces++;
                case '}' -> braces = Math.max(0, braces - 1);
                default -> {
                }
            }
        }
        return List.of(text);
    }

    private static int topLevelWord(String text, String word) {
        int angle = 0;
        int parentheses = 0;
        int brackets = 0;
        int braces = 0;
        for (int index = 0; index + word.length() <= text.length(); index++) {
            char value = text.charAt(index);
            switch (value) {
                case '<' -> angle++;
                case '>' -> angle = Math.max(0, angle - 1);
                case '(' -> parentheses++;
                case ')' -> parentheses = Math.max(0, parentheses - 1);
                case '[' -> brackets++;
                case ']' -> brackets = Math.max(0, brackets - 1);
                case '{' -> braces++;
                case '}' -> braces = Math.max(0, braces - 1);
                default -> {
                }
            }
            if (angle == 0 && parentheses == 0 && brackets == 0 && braces == 0 &&
                    text.regionMatches(index, word, 0, word.length()) &&
                    (index == 0 || !Character.isJavaIdentifierPart(text.charAt(index - 1))) &&
                    (index + word.length() == text.length() ||
                            !Character.isJavaIdentifierPart(text.charAt(index + word.length())))) {
                return index;
            }
        }
        return -1;
    }

    private static int skipWhitespace(String text, int offset) {
        int result = offset;
        while (result < text.length() && Character.isWhitespace(text.charAt(result))) result++;
        return result;
    }

    private static boolean startsWithWord(String text, String word) {
        return text.startsWith(word) &&
                (text.length() == word.length() || !Character.isJavaIdentifierPart(text.charAt(word.length())));
    }

    static String normalizeType(String type) {
        return type.replaceAll("\\s+", "").strip();
    }
}
