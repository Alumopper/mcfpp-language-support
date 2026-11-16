package top.mcfpp.language;

import java.util.*;
import java.util.regex.Pattern;

/** Contextual compound literals, including incomplete source while the user types. Offsets are UTF-16. */
public final class TemplateLiterals {
    public record Field(String name, String type, boolean hasDefault, boolean accessible) {}
    public record Entry(String name, int keyStart, int keyEnd, int colon, int valueStart, int valueEnd) {}
    public record KeySite(int start, int end, boolean hasColon, String name) {}
    public record Problem(int start, int end, String message) {}
    public interface Resolver {
        /** Null means this type is not a data template; an empty list means an empty data template. */
        List<Field> fields(String type, int offset);
        List<String> argumentTypes(int expressionStart);
        default List<String> returnTypes(int expressionStart) { return List.of(); }
        String valueType(String expression, int offset);
        default String assignmentType(String expression, int offset) { return valueType(expression, offset); }
        boolean isAssignable(String expected, String actual);
        default String constructionError(String type, int offset) { return null; }
    }
    public static final class Literal {
        private final int open;
        private int close;
        private final char kind;
        private final Literal parent;
        private final List<Integer> commas = new ArrayList<>();
        private Literal(int open, int end, char kind, Literal parent) {
            this.open = open; this.close = end; this.kind = kind; this.parent = parent;
        }
        public int open() { return open; }
        public int close() { return close; }
        public char kind() { return kind; }
    }
    private static final Pattern ANNOTATION = Pattern.compile(
            "(?s)\\b(?:var|const|external)\\s+[\\p{L}_$][\\p{L}\\p{N}_$]*\\s*:\\s*([^=;]+)$");
    private final String text;
    private final String code;
    private final Resolver resolver;
    private final List<Literal> containers = new ArrayList<>();
    private final Map<Integer, List<String>> expected = new HashMap<>();

    public TemplateLiterals(String text, Resolver resolver) {
        this.text = text; this.resolver = resolver;
        char[] masked = text.toCharArray();
        Deque<Literal> stack = new ArrayDeque<>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                char quote = c;
                masked[i] = ' ';
                while (++i < text.length()) {
                    char next = text.charAt(i);
                    masked[i] = ' ';
                    if (next == '\\' && i + 1 < text.length()) masked[++i] = ' ';
                    else if (next == quote) break;
                }
                continue;
            }
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                while (i < text.length() && text.charAt(i) != '\n') masked[i++] = ' ';
                i--; continue;
            }
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                masked[i++] = ' '; masked[i] = ' ';
                while (++i < text.length()) {
                    if (text.charAt(i) == '*' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                        masked[i] = ' '; masked[++i] = ' '; break;
                    }
                    if (text.charAt(i) != '\n') masked[i] = ' ';
                }
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                Literal frame = new Literal(i, text.length(), c, stack.peek());
                containers.add(frame); stack.push(frame);
            } else if (c == ')' || c == ']' || c == '}') {
                char open = c == ')' ? '(' : c == ']' ? '[' : '{';
                if (!stack.isEmpty() && stack.peek().kind == open) stack.pop().close = i;
            } else if (c == ',' && !stack.isEmpty()) stack.peek().commas.add(i);
        }
        this.code = new String(masked);
    }

    public List<Literal> literals() { return containers.stream().filter(it -> it.kind == '{').toList(); }
    public Literal at(int offset) {
        return containers.stream().filter(it -> it.open < offset && offset <= it.close)
                .max(Comparator.comparingInt(it -> it.open)).filter(it -> it.kind == '{').orElse(null);
    }
    public Literal startingAt(int offset) {
        return containers.stream().filter(it -> it.open == offset).findFirst().orElse(null);
    }
    public List<String> types(Literal literal) {
        if (expected.containsKey(literal.open)) return expected.get(literal.open);
        // Break cycles in malformed declarations or self-referential model callbacks.
        expected.put(literal.open, List.of());
        List<String> types = inferTypes(literal).stream().filter(Objects::nonNull).distinct().toList();
        expected.put(literal.open, types);
        return types;
    }
    public List<String> templateTypes(Literal literal) {
        var targets = types(literal);
        var templates = targets.stream().filter(type -> resolver.fields(type, literal.open) != null).toList();
        boolean rawAlternative = targets.stream().anyMatch(type -> type.equals("nbt") || type.equals("any") || type.startsWith("dict<"));
        if (rawAlternative && templates.stream().noneMatch(type -> problems(literal, type, true).isEmpty())) return List.of();
        return templates;
    }
    private List<String> inferTypes(Literal literal) {
        Literal parent = literal.parent;
        if (parent != null && parent.kind == '{') {
            for (Entry entry : entries(parent)) {
                if (entry.valueStart == literal.open) {
                    return templateTypes(parent).stream().flatMap(type -> resolver.fields(type, parent.open).stream())
                            .filter(field -> field.name.equals(entry.name)).map(Field::type).toList();
                }
            }
        }
        if (parent != null && parent.kind == '[') {
            List<String> elements = types(parent).stream().map(TemplateLiterals::elementType).filter(Objects::nonNull).toList();
            if (!elements.isEmpty()) return elements;
        }
        int start = parent == null ? 0 : parent.open + 1;
        if (parent != null) for (int comma : parent.commas) if (comma < literal.open) start = comma + 1;
        String prefix = code.substring(start, literal.open);
        if (prefix.stripTrailing().endsWith("return")) return resolver.returnTypes(literal.open);
        int assignment = prefix.lastIndexOf('=');
        if (assignment >= 0 && (assignment == 0 || "=!<>".indexOf(prefix.charAt(assignment - 1)) < 0)) {
            String left = prefix.substring(0, assignment).stripTrailing();
            var annotation = ANNOTATION.matcher(left);
            if (annotation.find()) return List.of(annotation.group(1).strip());
            if (parent != null && parent.kind == '(') return resolver.argumentTypes(literal.open);
            int boundary = Math.max(Math.max(left.lastIndexOf(';'), left.lastIndexOf('\n')), left.lastIndexOf('}'));
            String valueType = resolver.assignmentType(left.substring(boundary + 1).strip(), literal.open);
            if (valueType != null) return List.of(valueType);
        }
        return resolver.argumentTypes(literal.open);
    }

    public List<Entry> entries(Literal literal) {
        List<Entry> entries = new ArrayList<>();
        int start = literal.open + 1;
        for (int end : parts(literal)) {
            int keyStart = skipTrivia(start, end);
            if (keyStart < end) {
                int keyEnd = keyStart;
                String name;
                char first = text.charAt(keyStart);
                if (first == '"' || first == '\'') {
                    keyEnd++;
                    while (keyEnd < end && text.charAt(keyEnd) != first) {
                        if (text.charAt(keyEnd) == '\\' && keyEnd + 1 < end) keyEnd++;
                        keyEnd++;
                    }
                    name = text.substring(keyStart + 1, keyEnd);
                    if (keyEnd < end) keyEnd++;
                } else {
                    while (keyEnd < end && Character.isUnicodeIdentifierPart(text.charAt(keyEnd))) keyEnd++;
                    name = text.substring(keyStart, keyEnd);
                }
                int colon = skipTrivia(keyEnd, end);
                if (keyEnd > keyStart && colon < end && text.charAt(colon) == ':') {
                    entries.add(new Entry(name, keyStart, keyEnd, colon, skipTrivia(colon + 1, end), trimEnd(end)));
                } else if (keyEnd > keyStart && colon == end && first != '"' && first != '\'') {
                    entries.add(new Entry(name, keyStart, keyEnd, -1, keyStart, keyEnd));
                }
            }
            start = end + 1;
        }
        return entries;
    }
    private List<Integer> parts(Literal literal) {
        List<Integer> ends = new ArrayList<>(literal.commas); ends.add(literal.close); return ends;
    }
    public KeySite keySite(Literal literal, int offset) {
        if (at(offset) != literal) return null;
        int start = literal.open + 1, end = literal.close;
        for (int comma : literal.commas) {
            if (comma < offset) start = comma + 1;
            else { end = comma; break; }
        }
        start = skipTrivia(start, end);
        if (start > offset) {
            if (!text.substring(offset, start).isBlank()) return null;
            start = offset;
        }
        int keyEnd = start;
        boolean quoted = start < end && (text.charAt(start) == '"' || text.charAt(start) == '\'');
        if (quoted) {
            char quote = text.charAt(start); keyEnd++;
            while (keyEnd < end && text.charAt(keyEnd) != quote) keyEnd++;
            if (keyEnd < end) keyEnd++;
        } else while (keyEnd < end && Character.isUnicodeIdentifierPart(text.charAt(keyEnd))) keyEnd++;
        int colon = skipTrivia(keyEnd, end);
        if (offset > keyEnd || start < offset && !quoted && keyEnd == start) return null;
        String name = text.substring(start, keyEnd);
        if (quoted) name = name.substring(1).replaceAll("[\"']$", "");
        return new KeySite(start, keyEnd, colon < end && text.charAt(colon) == ':', name);
    }
    public List<Field> completions(Literal literal, KeySite site) {
        Set<String> used = new HashSet<>();
        for (Entry entry : entries(literal)) if (entry.keyStart != site.start) used.add(entry.name);
        Map<String, Field> fields = new LinkedHashMap<>();
        for (String type : bestTypes(literal)) for (Field field : resolver.fields(type, literal.open))
            if (field.accessible && !used.contains(field.name)) fields.putIfAbsent(field.name, field);
        return List.copyOf(fields.values());
    }
    public List<String> bestTypes(Literal literal) {
        var candidates = templateTypes(literal);
        int best = candidates.stream().mapToInt(type -> problems(literal, type, false).size()).min().orElse(0);
        return candidates.stream().filter(type -> problems(literal, type, false).size() == best).toList();
    }
    public List<Problem> diagnostics() {
        List<Problem> problems = new ArrayList<>();
        for (Literal literal : literals()) {
            List<Problem> best = null;
            for (String type : templateTypes(literal)) {
                List<Problem> candidate = problems(literal, type, true);
                if (best == null || candidate.size() < best.size()) best = candidate;
            }
            if (best != null) problems.addAll(best);
        }
        return problems;
    }
    private List<Problem> problems(Literal literal, String type, boolean missing) {
        List<Problem> problems = new ArrayList<>();
        Map<String, Field> fields = new LinkedHashMap<>();
        for (Field field : resolver.fields(type, literal.open)) fields.putIfAbsent(field.name, field);
        String construction = resolver.constructionError(type, literal.open);
        if (construction != null) problems.add(new Problem(literal.open, literal.open + 1, construction));
        Set<String> used = new HashSet<>();
        for (Entry entry : entries(literal)) {
            if (!used.add(entry.name)) problems.add(new Problem(entry.keyStart, entry.keyEnd,
                    "Duplicate field `" + entry.name + "` in `" + type + "`"));
            Field field = fields.get(entry.name);
            if (field == null) continue;
            if (!field.accessible) problems.add(new Problem(entry.keyStart, entry.keyEnd,
                    "Field `" + entry.name + "` is not accessible in `" + type + "`"));
            else if (entry.valueStart < entry.valueEnd) {
                String value = text.substring(entry.valueStart, entry.valueEnd).strip();
                Literal child = startingAt(entry.valueStart);
                boolean contextual = child != null && ((child.kind == '{' && resolver.fields(field.type, literal.open) != null) ||
                        (child.kind == '[' && elementType(field.type) != null));
                String actual = contextual ? field.type : resolver.valueType(value, entry.valueStart);
                if (actual != null && !resolver.isAssignable(field.type, actual)) problems.add(new Problem(
                        entry.valueStart, entry.valueEnd, "Field `" + entry.name + "` type mismatch: expected `" + field.type + "`, found `" + actual + "`"));
            }
        }
        if (missing && literal.close < text.length()) for (Field field : fields.values())
            if (!field.type.endsWith("?") && !used.contains(field.name)) problems.add(
                    new Problem(literal.open, literal.open + 1, "Missing required field `" + field.name + "` in `" + type + "`"));
        return problems;
    }
    public static String elementType(String type) {
        type = type.strip().replaceFirst("\\?$", "");
        String base = type.substring(0, type.indexOf('<') < 0 ? type.length() : type.indexOf('<')).strip();
        return base.substring(base.lastIndexOf(':') + 1).equals("list") && type.stripTrailing().endsWith(">")
                ? type.substring(type.indexOf('<') + 1, type.lastIndexOf('>')).strip() : null;
    }
    private int skipTrivia(int start, int end) {
        while (start < end && Character.isWhitespace(code.charAt(start)) &&
                (Character.isWhitespace(text.charAt(start)) || text.startsWith("//", start) || text.startsWith("/*", start))) {
            if (text.startsWith("//", start)) {
                while (start < end && text.charAt(start) != '\n') start++;
            } else if (text.startsWith("/*", start)) {
                int close = text.indexOf("*/", start + 2); start = close < 0 ? end : Math.min(end, close + 2);
            } else start++;
        }
        return start;
    }
    private int trimEnd(int end) { while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) end--; return end; }
}
