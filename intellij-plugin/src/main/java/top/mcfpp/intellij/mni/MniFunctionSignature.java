package top.mcfpp.intellij.mni;

import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.lang.McfppLexer;
import top.mcfpp.intellij.lang.McfppTokenTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The part of a native MCFPP declaration used by the compiler to select an {@code @MNIFunction}. */
record MniFunctionSignature(
        @NotNull String name,
        @NotNull String receiverType,
        @NotNull List<String> typeParameters,
        @NotNull List<String> readOnlyTypes,
        @NotNull List<String> normalTypes,
        @NotNull String returnType,
        @NotNull List<String> readOnlyNames,
        @NotNull List<String> normalNames,
        int nameOffset,
        int receiverStartOffset
) {
    private static final Pattern FUNCTION_NAME = Pattern.compile(
            "\\bfunc\\s+(?:([A-Za-z_$][A-Za-z0-9_$:!?<>.,\\[\\]\\s]*)\\.\\s*)?([A-Za-z_$][A-Za-z0-9_$]*)"
    );

    MniFunctionSignature {
        typeParameters = List.copyOf(typeParameters);
        readOnlyTypes = List.copyOf(readOnlyTypes);
        normalTypes = List.copyOf(normalTypes);
        readOnlyNames = List.copyOf(readOnlyNames);
        normalNames = List.copyOf(normalNames);
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
        String receiver = function.group(1) == null ? "" : normalizeType(function.group(1));
        int cursor = skipWhitespace(header, function.end());

        List<String> readOnly = List.of();
        List<String> readOnlyNames = List.of();
        if (cursor < header.length() && header.charAt(cursor) == '<') {
            int close = matchingDelimiter(header, cursor, '<', '>');
            if (close < 0) return Optional.empty();
            readOnly = parameterTypes(header.substring(cursor + 1, close), true);
            readOnlyNames = parameterNames(header.substring(cursor + 1, close), true);
            cursor = skipWhitespace(header, close + 1);
        }
        if (cursor >= header.length() || header.charAt(cursor) != '(') return Optional.empty();
        int normalClose = matchingDelimiter(header, cursor, '(', ')');
        if (normalClose < 0) return Optional.empty();
        List<String> normal = parameterTypes(header.substring(cursor + 1, normalClose), false);
        List<String> normalNames = parameterNames(header.substring(cursor + 1, normalClose), false);

        cursor = skipWhitespace(header, normalClose + 1);
        String returnType = "void";
        if (cursor < header.length() && header.charAt(cursor) == ':') {
            returnType = normalizeType(header.substring(cursor + 1));
            if (returnType.isEmpty()) returnType = "void";
        }
        return Optional.of(new MniFunctionSignature(function.group(2), receiver,
                receiver.isEmpty() ? List.of() : readOnlyNames, readOnly, normal, returnType,
                readOnlyNames, normalNames, start + function.start(2), receiver.isEmpty() ? -1 : start + function.start(1)));
    }

    boolean parametersMatch(MniJavaAnnotation annotation) {
        if (!normalTypes.equals(annotation.normalTypes())) return false;
        if (annotation.macro() && !receiverType.isEmpty()) return readOnlyTypes.stream().allMatch("type"::equals);
        return readOnlyTypes.equals(annotation.readOnlyTypes());
    }

    int javaArgumentCount() {
        return readOnlyTypes.size() + normalTypes.size() + ("void".equals(returnType) ? 0 : 1);
    }

    int macroArgumentCount() {
        return normalTypes.size() + 1 + (receiverType.isEmpty() ? 0 : 1);
    }

    String parametersText() {
        String readOnly = readOnlyTypes.isEmpty() ? "" : "<" + String.join(", ", readOnlyTypes) + ">";
        String prefix = receiverType.isEmpty() ? readOnly : receiverType + "." + name +
                (typeParameters.isEmpty() ? "" : "<" + String.join(", ", typeParameters) + ">");
        return prefix + "(" + String.join(", ", normalTypes) + ")";
    }

    private static List<String> parameterNames(String parameters, boolean readOnly) {
        List<String> result = new ArrayList<>();
        for (String parameter : splitTopLevel(parameters)) {
            String name = parameterName(parameter, readOnly);
            if (!name.isEmpty()) result.add(name);
        }
        return List.copyOf(result);
    }

    private static String parameterName(String parameter, boolean readOnly) {
        int assignment = topLevelCharacter(parameter, '=');
        String declaration = (assignment < 0 ? parameter : parameter.substring(0, assignment)).strip();
        int colon = topLevelCharacter(declaration, ':');
        String beforeType = colon < 0 ? declaration : declaration.substring(0, colon).strip();
        while (startsWithWord(beforeType, "var") || startsWithWord(beforeType, "out") || startsWithWord(beforeType, "in")) {
            int space = beforeType.indexOf(' ');
            if (space < 0) break;
            beforeType = beforeType.substring(space + 1).strip();
        }
        int space = beforeType.lastIndexOf(' ');
        return space < 0 ? beforeType : beforeType.substring(space + 1).strip();
    }

    private static List<String> parameterTypes(String parameters, boolean readOnly) {
        List<String> result = new ArrayList<>();
        for (String parameter : splitTopLevel(parameters)) {
            String type = parameterType(parameter, readOnly);
            if (!type.isEmpty()) result.add(type);
        }
        return List.copyOf(result);
    }

    private static String parameterType(String parameter, boolean readOnly) {
        int assignment = topLevelCharacter(parameter, '=');
        String declaration = assignment < 0 ? parameter : parameter.substring(0, assignment);
        int colon = topLevelCharacter(declaration, ':');
        if (colon >= 0) return normalizeType(declaration.substring(colon + 1));
        String withoutModifiers = declaration.strip();
        while (startsWithWord(withoutModifiers, "var")) {
            withoutModifiers = withoutModifiers.substring("var".length()).stripLeading();
        }
        if (readOnly) {
            McfppLexer lexer = new McfppLexer();
            lexer.start(withoutModifiers);
            if (lexer.getTokenType() == McfppTokenTypes.IDENTIFIER && lexer.getTokenEnd() == withoutModifiers.length()) {
                return "type";
            }
        }
        return normalizeType(withoutModifiers);
    }

    static List<String> splitTopLevel(String text) {
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
                case '>' -> { if (index == 0 || text.charAt(index - 1) != '-') angle = Math.max(0, angle - 1); }
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
            else if (value == close && (value != '>' || text.charAt(index - 1) != '-') && --depth == 0) return index;
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
