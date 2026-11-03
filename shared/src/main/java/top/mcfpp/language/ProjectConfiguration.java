package top.mcfpp.language;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** The source of project paths and namespaces for both native IDEA features and LSP. */
public record ProjectConfiguration(Path file, Path root, Path sourcePath, Path dataPath, Path targetPath,
        String namespace, String version, boolean mniEnabled, boolean ignoreStdLib,
        List<Path> includes, List<Path> jars, Path assetsPath, Map<String, String> entrypoints, List<String> resourceOverrides) {
    public static Path find(Path location) {
        Path cursor = location.toAbsolutePath().normalize();
        if (!Files.isDirectory(cursor)) cursor = cursor.getParent();
        while (cursor != null) {
            for (String name : List.of("mcfpp.toml", "mcfpp.json"))
                if (Files.isRegularFile(cursor.resolve(name))) return cursor.resolve(name);
            cursor = cursor.getParent();
        }
        return null;
    }

    public static ProjectConfiguration read(Path file) throws IOException {
        return parse(file, Files.readString(file));
    }

    public static ProjectConfiguration parse(Path file, String text) {
        Map<String, Object> values;
        boolean toml = file.getFileName().toString().endsWith(".toml");
        if (toml) {
            var parsed = Toml.parse(text);
            if (parsed.hasErrors()) throw new IllegalArgumentException(parsed.errors().toString());
            if (!Long.valueOf(1).equals(parsed.getLong("schema"))) throw new IllegalArgumentException("mcfpp.toml: schema must be 1");
            values = table(parsed);
        } else values = json(JsonParser.parseString(text));
        Path root = file.toAbsolutePath().normalize().getParent();
        Path base = root.resolve(string(values, "root", "")).normalize();
        var mni = values.get("mni") instanceof Map<?, ?> map ? map : Map.of();
        List<String> args = strings(values.getOrDefault("args", values.get("compileArgs")));
        return new ProjectConfiguration(file.toAbsolutePath().normalize(), root,
                base.resolve(string(values, "sourcePath", toml ? "src/main/mcfpp" : ".").replace("/**", "")).normalize(),
                base.resolve(string(values, "dataPath", "src/main/resources/data")).normalize(),
                base.resolve(string(values, "targetPath", "build")).normalize(),
                string(values, "namespace", root.getFileName().toString()),
                string(values, "version", VersionPreprocessor.DEFAULT_VERSION), Boolean.TRUE.equals(mni.get("enabled")),
                Boolean.FALSE.equals(values.get("stdLib")) || args.contains("-ignoreStdLib") || args.contains("--ignoreStdLib"),
                paths(root, merge(values.get("include"), values.get("includes"))),
                paths(root, merge(values.get("jar"), values.get("jars"))),
                base.resolve(string(values, "assetsPath", "src/main/resources/assets")).normalize(),
                entries(values.get("entrypoints")), strings(values.get("resourceOverrides")));
    }

    public String namespaceFor(Path source) {
        Path tests=root.resolve("src/test/mcfpp");
        if(source.toAbsolutePath().normalize().startsWith(tests)) return namespaceFor(tests, namespace, source);
        return namespaceFor(sourcePath, namespace, source);
    }
    public static String namespaceFor(Path sourcePath, String namespace, Path source) {
        sourcePath = sourcePath.toAbsolutePath().normalize();
        Path parent = source.toAbsolutePath().normalize().getParent();
        if (parent == null || !parent.startsWith(sourcePath)) return namespace;
        String suffix = sourcePath.relativize(parent).toString().replace('\\', '.').replace('/', '.');
        StringBuilder normalized = new StringBuilder();
        for (int i = 0; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            if (Character.isLowerCase(c) || Character.isDigit(c) || "_-.".indexOf(c) >= 0) normalized.append(c);
            else if (Character.isUpperCase(c)) { if (i > 0) normalized.append('_'); normalized.append(Character.toLowerCase(c)); }
            else normalized.append('u').append(Integer.toHexString(c));
        }
        suffix = normalized.toString();
        return suffix.isEmpty() ? namespace : namespace + "." + suffix;
    }

    private static String string(Map<String, Object> map, String key, String fallback) {
        return map.get(key) instanceof String value ? value : fallback;
    }
    private static Map<String, String> entries(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) map.forEach((k, v) -> { if (k instanceof String key && v instanceof String target) result.put(key, target); });
        return Collections.unmodifiableMap(result);
    }
    private static List<String> strings(Object value) {
        if (value instanceof String s) return List.of(s);
        return value instanceof List<?> list ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList() : List.of();
    }
    private static List<Path> paths(Path root, Object value) {
        return strings(value).stream().map(root::resolve).map(Path::normalize).toList();
    }
    private static List<String> merge(Object primary, Object alias) {
        return java.util.stream.Stream.concat(strings(primary).stream(), strings(alias).stream()).distinct().toList();
    }
    private static Map<String, Object> table(TomlTable table) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : table.keySet()) result.put(key, convert(table.get(List.of(key))));
        return result;
    }
    private static Object convert(Object value) {
        if (value instanceof TomlTable t) return table(t);
        if (value instanceof TomlArray a) return a.toList().stream().map(ProjectConfiguration::convert).toList();
        return value;
    }
    private static Map<String, Object> json(JsonElement element) {
        Map<String, Object> result = new LinkedHashMap<>();
        element.getAsJsonObject().entrySet().forEach(e -> result.put(e.getKey(), jsonValue(e.getValue())));
        return result;
    }
    private static Object jsonValue(JsonElement e) {
        if (e.isJsonNull()) return null;
        if (e.isJsonObject()) return json(e);
        if (e.isJsonArray()) { List<Object> list = new ArrayList<>(); e.getAsJsonArray().forEach(v -> list.add(jsonValue(v))); return list; }
        return e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : e.getAsString();
    }
}
