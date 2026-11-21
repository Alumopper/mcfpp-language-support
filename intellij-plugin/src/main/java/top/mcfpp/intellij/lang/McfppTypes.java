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
        int assignment = signature.indexOf('=');
        String initializer = assignment >= 0 ? signature.substring(assignment + 1).strip() : "";
        if (assignment >= 0) signature = signature.substring(0, assignment).stripTrailing();
        int annotation = signature.indexOf(':');
        if (annotation < 0) {
            if ("<lambda>".equals(symbol.owner())) return null;
            String builtin = top.mcfpp.language.EntityBuiltins.factoryType(initializer);
            if (builtin != null) return builtin;
            String factory = initializer.substring(0, initializer.contains("(") ? initializer.indexOf('(') : initializer.length()).strip();
            int generic = factory.indexOf('<');
            String name = generic >= 0 ? factory.substring(0, generic) : factory;
            name = name.substring(name.lastIndexOf(':') + 1).strip();
            if (initializer.contains("(") && name.equals("scoreRef")) return "ScoreRef";
            if (initializer.contains("(") && factory.endsWith(">") &&
                    List.of("nbtStorageRef", "nbtEntityRef", "nbtBlockRef").contains(name)) return "NBTRef" + factory.substring(generic);
            return symbol.kind() == McfppSymbolKind.PARAMETER &&
                    signature.matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\s*=.*)?") ? "type" : null;
        }
        int cursor = annotation + 1;
        while (cursor < signature.length() && Character.isWhitespace(signature.charAt(cursor))) cursor++;
        if (cursor < signature.length() && (signature.charAt(cursor) == '(' || signature.charAt(cursor) == '{' || signature.startsWith("context(", cursor))) return signature.substring(cursor).strip();
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
        // EntityRef<T> keeps its type argument; it selects the template member set.
        if (cursor < signature.length() && signature.charAt(cursor) == '<') {
            int depth = 0;
            for (int index = cursor; index < signature.length(); index++) {
                char value = signature.charAt(index);
                if (value == '<') depth++;
                else if (value == '>' && --depth == 0) {
                    type = signature.substring(start, index + 1);
                    break;
                }
            }
        }
        int colon = type.lastIndexOf(':');
        return colon < 0 ? type : type.substring(colon + 1);
    }

    static @Nullable String receiverType(String expression, McfppFileModel model, int offset) {
        expression = expression.strip();
        if (expression.startsWith("{") && expression.endsWith("}")) return "nbt";
        String literalType = stringLiteralType(expression);
        if (literalType != null) return literalType;
        String mergeReceiver = top.mcfpp.language.MemberExpressions.calledMemberReceiver(expression, "merge");
        if (mergeReceiver != null && "nbt".equals(receiverType(mergeReceiver, model, offset))) return "nbt";
        if (expression.strip().startsWith("@")) return "entity";
        String[] parts = expression.strip().split("\\s*\\.\\s*");
        String type = parts[0];
        var locals = model.visibleLocalSymbols(type, offset);
        if (!locals.isEmpty()) {
            type = declaredType(locals.getFirst());
            if (type == null) {
                String signature = locals.getFirst().signature();
                int assignment = signature.indexOf('=');
                if (assignment >= 0) {
                    String value = signature.substring(assignment + 1).strip();
                    String factoryOwner = top.mcfpp.language.MemberExpressions.calledMemberReceiver(value, "new");
                    String stringType = stringLiteralType(value);
                    if (factoryOwner != null) type = factoryOwner;
                    else if (stringType != null) type = stringType;
                    else if (!value.equals(expression) && "nbt".equals(receiverType(value, model, locals.getFirst().nameOffset()))) type = "nbt";
                    else if (value.startsWith("@")) type = "entity";
                    else if (value.matches("[\\w.]+") && !value.equals(expression))
                        type = receiverType(value, model, locals.getFirst().nameOffset());
                    else {
                        var summon = java.util.regex.Pattern.compile(
                                "([\\w.]+)\\s*\\.\\s*summon\\s*\\(").matcher(value);
                        if (summon.find()) {
                            String owner = receiverType(summon.group(1), model, locals.getFirst().nameOffset());
                            if (owner != null) type = "EntityRef<" + owner.substring(owner.lastIndexOf(':') + 1) + ">";
                        } else {
                            var constructor = java.util.regex.Pattern.compile(
                                    "(?:[\\w.]*:)?([A-Za-z_$][\\w$]*)\\s*(?:<[^<>]*>)?\\s*\\(").matcher(value);
                            if (constructor.find() && model.symbols().stream().anyMatch(it -> it.kind().isType() && it.name().equals(constructor.group(1)))) {
                                type = constructor.group(1);
                            }
                        }
                    }
                }
            }
            if (type == null) return null;
        }
        for (int i = 1; i < parts.length; i++) {
            if ("summon".equals(parts[i]) && isEntityTemplate(model, type)) {
                type = "EntityRef<" + type.substring(type.lastIndexOf(':') + 1) + ">";
                continue;
            }
            var member = top.mcfpp.language.EntityBuiltins.member(type, parts[i]);
            if (member == null) return null;
            type = member.type();
        }
        return type;
    }

    private static @Nullable String stringLiteralType(String expression) {
        if (expression.length() < 2 || expression.charAt(0) != '"' && expression.charAt(0) != '\'') return null;
        McfppLexer lexer = new McfppLexer();
        lexer.start(expression);
        if (lexer.getTokenType() != McfppTokenTypes.STRING) return null;
        int end = lexer.getTokenEnd();
        lexer.advance();
        if (lexer.getTokenType() == McfppTokenTypes.INTERPOLATION_DELIMITER) return "text";
        return end == expression.length() ? "string" : null;
    }

    /** Whether the model declares {@code type} with the compiler's `entity` template keyword. */
    static boolean isEntityTemplate(McfppFileModel model, String type) {
        String simple = type.substring(type.lastIndexOf(':') + 1).strip();
        return model.symbols().stream().anyMatch(symbol ->
                symbol.kind() == McfppSymbolKind.TYPE && symbol.name().equals(simple) &&
                        symbol.signature().stripLeading().matches("(?s)^(?:(?:final|abstract)\\s+)?entity\\b.*"));
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
        int annotation = parameter.indexOf(':');
        String type = annotation < 0 ? parameter : parameter.substring(annotation + 1).strip();
        if (type.contains("->")) return type;
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
