package top.mcfpp.language;

import java.util.ArrayList;
import java.util.List;

/** Balanced type-expression helpers shared by both editor models. */
public final class StructuralTypes {
    private StructuralTypes() {}
    public record Field(String name, String type) {}

    public static List<String> split(String text, char separator) {
        List<String> result = new ArrayList<>();
        int depth = 0, start = 0;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') { quote = c; continue; }
            if (c == '<' || c == '(' || c == '[' || c == '{') depth++;
            else if ((c == '>' && (i == 0 || text.charAt(i - 1) != '-')) || c == ')' || c == ']' || c == '}') depth--;
            else if (c == separator && depth == 0) { result.add(text.substring(start, i).strip()); start = i + 1; }
        }
        if (start < text.length()) result.add(text.substring(start).strip());
        return result;
    }

    public static List<String> components(String type, char separator) {
        String text = type.strip().replaceFirst("\\?$", "");
        if (text.startsWith("(") && text.endsWith(")")) text = text.substring(1, text.length() - 1).strip();
        List<String> parts = split(text, separator);
        return parts.size() > 1 ? parts : List.of();
    }

    /** Null indicates a named type or a function, rather than an object contract. */
    public static List<Field> fields(String type) {
        String text = type.strip();
        if (!text.startsWith("{") || !text.endsWith("}")) return null;
        List<Field> fields = new ArrayList<>();
        for (String part : split(text.substring(1, text.length() - 1), ',')) {
            int colon = part.indexOf(':');
            if (colon <= 0) return null;
            fields.add(new Field(part.substring(0, colon).strip(), part.substring(colon + 1).strip()));
        }
        return fields;
    }
}
