package top.mcfpp.intellij.lang;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lightweight argument-name and type inference used by quick declaration actions. */
final class McfppArgumentInference {
    private static final Pattern NUMBER = Pattern.compile(
            "[+-]?(?:0[xX][0-9a-fA-F]+|(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)[bBsSlLfFdD]?"
    );
    private static final Pattern CAST = Pattern.compile(
            "(?s).*\\bas\\s+([A-Za-z_$][A-Za-z0-9_$]*(?:[.:][A-Za-z_$][A-Za-z0-9_$]*)*)\\s*$"
    );
    private static final Set<String> RESERVED = Set.of(
            "as", "break", "const", "constructor", "continue", "data", "do", "else", "enum", "execute",
            "false", "for", "from", "func", "global", "if", "import", "namespace", "null", "object",
            "return", "store", "super", "this", "true", "try", "typealias", "var", "while"
    );

    private McfppArgumentInference() {
    }

    static @NotNull List<Parameter> infer(
            @NotNull McfppFile file,
            @NotNull List<McfppCallContext.Argument> arguments,
        @NotNull String fallbackPrefix,
        @NotNull Set<String> usedNames
    ) {
        McfppFileModel model = McfppFileModels.get(file);
        return java.util.stream.IntStream.range(0, arguments.size())
                .mapToObj(index -> {
                    McfppCallContext.Argument argument = arguments.get(index);
                    String preferred = preferredName(argument.text());
                    String fallback = fallbackPrefix + (index + 1);
                    String name = uniqueName(preferred == null ? fallback : preferred, usedNames);
                    return new Parameter(name, inferType(argument, model));
                })
                .toList();
    }

    static @NotNull List<String> inferTypes(
            @NotNull McfppFile file,
            @NotNull List<McfppCallContext.Argument> arguments
    ) {
        McfppFileModel model = McfppFileModels.get(file);
        return arguments.stream().map(argument -> inferType(argument, model)).toList();
    }

    private static String inferType(McfppCallContext.Argument argument, McfppFileModel model) {
        String text = argument.text().strip();
        if (text.isEmpty() || text.equals("null") || text.equals("*")) return "any";
        if (isQuoted(text)) return "string";
        if (text.equals("true") || text.equals("false")) return "bool";
        if (text.startsWith("@") && text.length() >= 2 && "arpse".indexOf(text.charAt(1)) >= 0) {
            return "selector";
        }
        if (NUMBER.matcher(text).matches()) return numberType(text);
        if (text.startsWith("[B;") || text.startsWith("[b;")) return "ByteArray";
        if (text.startsWith("[I;") || text.startsWith("[i;")) return "IntArray";
        if (text.startsWith("[L;") || text.startsWith("[l;")) return "LongArray";
        if (text.startsWith("[")) return "list<any>";
        if (text.startsWith("{")) return "nbt";

        Matcher cast = CAST.matcher(text);
        if (cast.matches()) return simpleTypeName(cast.group(1));
        if (McfppNames.isIdentifier(text)) {
            for (McfppSymbol symbol : model.visibleLocalSymbols(text, argument.startOffset())) {
                String declaredType = McfppTypes.declaredType(symbol);
                if (declaredType != null) return declaredType;
            }
        }
        String constructedType = constructedType(text);
        return constructedType == null ? "any" : constructedType;
    }

    private static boolean isQuoted(String text) {
        return text.length() >= 2 && (text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"' ||
                text.charAt(0) == '\'' && text.charAt(text.length() - 1) == '\'');
    }

    private static String numberType(String text) {
        char suffix = Character.toLowerCase(text.charAt(text.length() - 1));
        return switch (suffix) {
            case 'b' -> "byte";
            case 's' -> "short";
            case 'l' -> "long";
            case 'f' -> "float";
            case 'd' -> "double";
            default -> text.indexOf('.') >= 0 || text.indexOf('e') >= 0 || text.indexOf('E') >= 0
                    ? "double"
                    : "int";
        };
    }

    private static String constructedType(String text) {
        int open = text.indexOf('(');
        if (open <= 0 || !text.endsWith(")")) return null;
        String callee = text.substring(0, open).strip();
        int readOnly = callee.indexOf('<');
        if (readOnly >= 0) callee = callee.substring(0, readOnly).strip();
        String type = simpleTypeName(callee);
        return !type.isEmpty() && Character.isUpperCase(type.codePointAt(0)) && McfppNames.isIdentifier(type)
                ? type
                : null;
    }

    private static String simpleTypeName(String qualifiedName) {
        int colon = qualifiedName.lastIndexOf(':');
        int dot = qualifiedName.lastIndexOf('.');
        return qualifiedName.substring(Math.max(colon, dot) + 1);
    }

    private static String preferredName(String text) {
        String value = text.strip();
        return McfppNames.isIdentifier(value) && !RESERVED.contains(value) ? value : null;
    }

    private static String uniqueName(String preferred, Set<String> names) {
        if (names.add(preferred)) return preferred;
        int suffix = 2;
        while (!names.add(preferred + suffix)) suffix++;
        return preferred + suffix;
    }

    record Parameter(@NotNull String name, @NotNull String type) {
        String declaration() {
            return name + " as " + type;
        }
    }
}
