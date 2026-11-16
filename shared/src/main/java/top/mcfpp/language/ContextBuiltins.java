package top.mcfpp.language;

import java.util.*;

/** Nominal, scoped context interfaces shared by both editor front ends. */
public final class ContextBuiltins {
    private ContextBuiltins() {}
    public static final List<String> TYPES = List.of("Context", "DimensionContext", "PosContext", "RotationContext",
        "AnchorContext", "EntityContext", "ExecutionContext", "EntityExecutionContext", "RelationContext",
        "ContextPosition", "ContextRotation", "ContextDimension", "ContextAnchor");
    private static final Map<String, List<String>> PARENTS = Map.of(
        "DimensionContext", List.of("Context"), "PosContext", List.of("DimensionContext"),
        "RotationContext", List.of("Context"), "AnchorContext", List.of("Context"), "EntityContext", List.of("Context"),
        "ExecutionContext", List.of("PosContext", "RotationContext", "AnchorContext"),
        "EntityExecutionContext", List.of("ExecutionContext", "EntityContext"), "RelationContext", List.of("EntityExecutionContext"));
    public static final List<String> RELATIONS = List.of("onPassengers", "onVehicle", "onOrigin", "onOwner",
        "onController", "onLeasher", "onTarget", "onAttacker");
    public static final List<String> ENTRIES = List.of("withCurrentCtx", "withPosCtx", "withEntityCtx", "withPositionedCtx",
        "withRotationCtx", "withFacingCtx", "withDimensionCtx", "withAlignedCtx", "withAnchorCtx", "withHeightmapCtx",
        "withSummonedCtx", "forEach", "forEachAt");
    private static final Map<String, List<EntityBuiltins.Member>> MEMBERS = new LinkedHashMap<>();
    static {
        MEMBERS.put("DimensionContext", List.of(field("dimension", "ContextDimension")));
        MEMBERS.put("PosContext", List.of(field("pos", "ContextPosition")));
        MEMBERS.put("RotationContext", List.of(field("rotation", "ContextRotation")));
        MEMBERS.put("AnchorContext", List.of(field("anchor", "ContextAnchor")));
        MEMBERS.put("EntityContext", List.of(field("executor", "entity")));
        List<EntityBuiltins.Member> execution = new ArrayList<>();
        execution.add(method("local", "ContextPosition", "x: double, y: double, z: double", "A position bound to this context's executor, rotation and anchor."));
        for (String name : RELATIONS) execution.add(entry(name, "", "RelationContext"));
        MEMBERS.put("ExecutionContext", List.copyOf(execution));
        MEMBERS.put("ContextPosition", List.of(method("offset", "ContextPosition", "x: double, y: double, z: double",
            "Adds a world-coordinate offset while retaining this position's source identity and dimension.")));
    }
    private static EntityBuiltins.Member field(String name, String type) {
        return new EntityBuiltins.Member(name, type, null, false, name + ": " + type,
            "Read-only borrowed context component. Its identity is preserved before a conflicting context switch; it cannot escape the synchronous scope.");
    }
    private static EntityBuiltins.Member method(String name, String result, String parameters, String doc) {
        return new EntityBuiltins.Member(name, result, null, true, "func " + name + "(" + parameters + "): " + result, doc);
    }
    private static EntityBuiltins.Member entry(String name, String operands, String callback) {
        return method(name, "void", (operands.isEmpty() ? "" : operands + ", ") + "callback: (" + callback + ") -> void",
            "Enters a synchronous execution context. The callback parameter provides lexical context dependencies; the caller's context resumes after the callback.");
    }
    public static List<EntityBuiltins.Member> entityMembers() {
        return List.of(entry("withEntityCtx", "", "EntityExecutionContext"), entry("withPosCtx", "", "ExecutionContext"),
            entry("withPositionedCtx", "", "ExecutionContext"), entry("withRotationCtx", "", "ExecutionContext"),
            entry("withFacingCtx", "anchor: Anchor", "ExecutionContext"),
            entry("forEach", "", "EntityExecutionContext"), entry("forEachAt", "", "EntityExecutionContext"));
    }
    public static List<EntityBuiltins.Member> globals() {
        return List.of(entry("withCurrentCtx", "", "ExecutionContext"), entry("withPosCtx", "position: pos3", "ExecutionContext"),
            entry("withPosCtx", "position: ContextPosition", "ExecutionContext"), entry("withRotationCtx", "rotation: Rotation", "ExecutionContext"),
            entry("withRotationCtx", "rotation: ContextRotation", "ExecutionContext"), entry("withFacingCtx", "position: pos3", "ExecutionContext"),
            entry("withDimensionCtx", "dimension: Dimension", "ExecutionContext"), entry("withDimensionCtx", "dimension: ContextDimension", "ExecutionContext"),
            entry("withAlignedCtx", "axes: Axes", "ExecutionContext"), entry("withAnchorCtx", "anchor: Anchor", "ExecutionContext"),
            entry("withAnchorCtx", "anchor: ContextAnchor", "ExecutionContext"), entry("withHeightmapCtx", "heightmap: Heightmap", "ExecutionContext"),
            entry("withSummonedCtx", "entityType: EntityType", "EntityExecutionContext"));
    }
    public static boolean isType(String type) { return TYPES.contains(EntityBuiltins.simple(type)); }
    public static boolean isEntry(String name) { return ENTRIES.contains(name) || RELATIONS.contains(name); }
    public static List<String> parents(String type) { return PARENTS.getOrDefault(EntityBuiltins.simple(type), List.of()); }
    public static List<String> owners(String type) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(EntityBuiltins.simple(type));
        while (!pending.isEmpty()) { String current = pending.removeFirst(); if (result.add(current)) pending.addAll(parents(current)); }
        return List.copyOf(result);
    }
    public static boolean isSubtype(String actual, String expected) { return owners(actual).contains(EntityBuiltins.simple(expected)); }
    public static List<EntityBuiltins.Member> members(String type) {
        LinkedHashMap<String, EntityBuiltins.Member> result = new LinkedHashMap<>();
        for (String owner : owners(type)) for (var member : MEMBERS.getOrDefault(owner, List.of())) result.putIfAbsent(member.name(), member);
        // An entity-only view can also be a relation origin, without exposing spatial components.
        if (isSubtype(type, "EntityContext")) for (String name : RELATIONS) result.putIfAbsent(name, entry(name, "", "RelationContext"));
        return List.copyOf(result.values());
    }
    public static Map<String, List<EntityBuiltins.Member>> declaredMembers() { return Collections.unmodifiableMap(MEMBERS); }
    public static String callbackType(String name, String ambient) {
        if (RELATIONS.contains(name)) return "RelationContext";
        if (name.equals("forEach") || name.equals("forEachAt")) return "EntityExecutionContext";
        if (name.equals("withEntityCtx") || name.equals("withSummonedCtx")) return "EntityExecutionContext";
        if (isSubtype(ambient, "RelationContext")) return "RelationContext";
        return isSubtype(ambient, "EntityContext") ? "EntityExecutionContext" : "ExecutionContext";
    }
    /** Locate the direct context call for a trailing or parenthesized inline callback. */
    public static String entryBefore(String source, int offset) {
        StringBuilder code = new StringBuilder(source.substring(0, Math.min(offset, source.length())));
        char string = 0; boolean escapedString = false, lineComment = false, blockComment = false;
        for (int i = 0; i < code.length(); i++) {
            char ch = source.charAt(i), next = i + 1 < code.length() ? source.charAt(i + 1) : 0;
            if (lineComment && ch == '\n') lineComment = false;
            if (blockComment && ch == '*' && next == '/') { code.setCharAt(i++, ' '); code.setCharAt(i, ' '); blockComment = false; continue; }
            if (string == 0 && !lineComment && !blockComment && ch == '/' && (next == '/' || next == '*')) {
                lineComment = next == '/'; blockComment = next == '*';
                code.setCharAt(i++, ' '); code.setCharAt(i, ' '); continue;
            }
            if (string == 0 && !lineComment && !blockComment && (ch == '"' || ch == '\'')) { string = ch; code.setCharAt(i, ' '); continue; }
            if (string != 0) {
                if (ch == string && !escapedString) string = 0;
                escapedString = ch == '\\' && !escapedString;
                if (ch != '\n') code.setCharAt(i, ' ');
            } else if ((lineComment || blockComment) && ch != '\n') code.setCharAt(i, ' ');
        }
        String prefix = code.toString();
        var matcher = java.util.regex.Pattern.compile("\\b(" + String.join("|", ENTRIES) + "|" + String.join("|", RELATIONS) + ")\\b").matcher(prefix);
        String result = null;
        while (matcher.find()) {
            String tail = prefix.substring(matcher.end()).strip();
            if (tail.isEmpty()) { result = matcher.group(); continue; }
            if (!tail.startsWith("(")) continue;
            int depth = 0; char quote = 0; boolean escaped = false, valid = true;
            for (int i = 0; i < tail.length(); i++) {
                char ch = tail.charAt(i);
                if (quote != 0) {
                    if (ch == quote && !escaped) quote = 0;
                    escaped = ch == '\\' && !escaped;
                } else if (ch == '"' || ch == '\'') quote = ch;
                else if (ch == '(') depth++;
                else if (ch == ')' && --depth == 0 && !tail.substring(i + 1).isBlank()) { valid = false; break; }
                else if (ch == '{' || ch == '}' || ch == ';') { valid = false; break; }
            }
            if (valid && quote == 0 && (depth == 0 || depth == 1 && (tail.endsWith(",") || tail.equals("(")))) result = matcher.group();
        }
        return result;
    }
    public static String source() {
        StringBuilder text = new StringBuilder();
        for (String type : TYPES) {
            text.append("/// Scoped compiler context; nominal inheritance, synchronous lifetime.\ndata ").append(type);
            if (!parents(type).isEmpty()) text.append(": ").append(String.join(", ", parents(type)));
            text.append(" {\n");
            for (var member : MEMBERS.getOrDefault(type, List.of())) {
                text.append("    /// ").append(member.documentation()).append('\n').append("    ");
                text.append(member.function() ? member.signature() + " {}" : "var " + member.name() + ": " + member.type() + ";").append('\n');
            }
            if (type.equals("EntityContext")) for (String name : RELATIONS) text.append("    ").append(entry(name, "", "RelationContext").signature()).append(" {}\n");
            text.append("}\n");
        }
        for (var entry : globals()) text.append("/// ").append(entry.documentation()).append('\n').append(entry.signature()).append(" {}\n");
        return text.toString();
    }
}
