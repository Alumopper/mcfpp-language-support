package top.mcfpp.intellij.tooling;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.SystemInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class McfppToolchain {
    private static final Logger LOG = Logger.getInstance(McfppToolchain.class);
    private static final int MIN_JAVA_MAJOR = 25;
    private static final Pattern JAVA_VERSION_PATTERN = Pattern.compile("JAVA_VERSION=\"(\\d+)");
    private static final Map<String, Path> EXTRACTED_CACHE = new ConcurrentHashMap<>();
    private static final AtomicBoolean PRUNED_STALE_TOOLS = new AtomicBoolean(false);
    private static volatile JsonObject manifestCache;

    private McfppToolchain() {}

    /** Locate a JDK (>= 25, including javac) for compiler & MNI tasks. */
    public static Path javaHome(Project project) throws IOException {
        return locateJavaHome(project, true);
    }

    /** Locate a Java executable (>= 25; JRE or JDK) for running LSP / CLI / DSB without requiring javac. */
    public static Path javaExecutable(Project project) throws IOException {
        Path home = locateJavaHome(project, false);
        return home.resolve("bin").resolve(javaBinaryName());
    }

    /**
     * Resolve a Java 25+ executable for Datapack Sandbox without cold-starting McfppCommandService.
     */
    public static String dsbJavaExecutable(Project project) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        addCandidate(candidates, McfppSettings.get(project).dpsJavaPath);
        addCandidate(candidates, System.getProperty("mcfpp.dps.java.path"));
        addCandidate(candidates, System.getenv("MCFPP_DPS_JAVA"));
        for (String explicit : candidates) {
            Path path = Path.of(explicit);
            if (Files.isRegularFile(path)) {
                return path.toString();
            }
            if (isSupportedJava(path, false)) {
                return path.resolve("bin").resolve(javaBinaryName()).toString();
            }
        }
        try {
            return javaExecutable(project).toString();
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Datapack Sandbox requires Java 25 or newer. Configure the DSB Java executable in Settings | Tools | MCFPP.",
                    error
            );
        }
    }

    private static Path locateJavaHome(Project project, boolean requireCompiler) throws IOException {
        String explicit = McfppSettings.get(project).javaHome.trim();
        if (!explicit.isEmpty()) {
            Path path = Path.of(explicit);
            if (!isSupportedJava(path, requireCompiler)) {
                throw new IOException(requireCompiler
                        ? "Select a JDK 25+ installation in Settings | Tools | MCFPP."
                        : "Select a Java 25+ runtime installation in Settings | Tools | MCFPP.");
            }
            return path;
        }
        List<String> candidates = new ArrayList<>();
        var sdk = ProjectRootManager.getInstance(project).getProjectSdk();
        if (sdk != null) candidates.add(sdk.getHomePath());
        for (String variable : List.of("MCFPP_JAVA_HOME", "JDK25_HOME", "JAVA_HOME")) {
            candidates.add(System.getenv(variable));
        }
        for (var candidate : ProjectJdkTable.getInstance().getAllJdks()) {
            candidates.add(candidate.getHomePath());
        }
        candidates.add(System.getProperty("java.home"));
        for (String path : candidates) {
            if (path != null && isSupportedJava(Path.of(path), requireCompiler)) {
                return Path.of(path);
            }
        }
        throw new IOException(requireCompiler
                ? "MCFPP requires JDK 25 or newer. Select it in Settings | Tools | MCFPP."
                : "MCFPP requires Java 25 or newer. Select it in Settings | Tools | MCFPP.");
    }

    /** Backwards-compatible check accepting JDK 25 and newer. */
    public static boolean isJdk25(Path path) {
        return isSupportedJava(path, true);
    }

    public static boolean isSupportedJava(Path path, boolean requireCompiler) {
        if (path == null) return false;
        try {
            String requiredBinary = requireCompiler
                    ? (SystemInfo.isWindows ? "javac.exe" : "javac")
                    : javaBinaryName();
            if (!Files.isRegularFile(path.resolve("bin").resolve(requiredBinary))) {
                return false;
            }
            Path release = path.resolve("release");
            if (!Files.isRegularFile(release)) return false;
            Matcher matcher = JAVA_VERSION_PATTERN.matcher(Files.readString(release));
            if (!matcher.find()) return false;
            int major = Integer.parseInt(matcher.group(1));
            return major >= MIN_JAVA_MAJOR;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static String javaBinaryName() {
        return SystemInfo.isWindows ? "java.exe" : "java";
    }

    private static void addCandidate(Set<String> candidates, String candidate) {
        if (candidate != null && !candidate.isBlank()) candidates.add(candidate.trim());
    }

    public static Path compiler(Project project) throws IOException {
        String configured = McfppSettings.get(project).compilerJar.trim();
        Path jar = configured.isEmpty()
                ? extractResource("toolchain/mcfpp-cli.jar", "mcfpp-cli.jar", "compilerSha256", false)
                : Path.of(configured);
        if (!Files.isRegularFile(jar)) throw new IOException("MCFPP compiler JAR does not exist: " + jar);
        return jar;
    }

    public static Path languageServer() throws IOException {
        return extractResource("server/mcfpp-language-server.jar", "mcfpp-language-server.jar", "serverSha256", false);
    }

    public static GeneralCommandLine cli(Project project, Path root, List<String> arguments) throws IOException {
        Path home = locateJavaHome(project, false);
        GeneralCommandLine line = new GeneralCommandLine(
                home.resolve("bin").resolve(javaBinaryName()).toString(),
                "--sun-misc-unsafe-memory-access=allow",
                "-Dfile.encoding=UTF-8",
                "-jar",
                compiler(project).toString()
        ).withCharset(StandardCharsets.UTF_8).withWorkDirectory(root.toString());
        line.withEnvironment("MCFPP_JAVA_HOME", home.toString()).withEnvironment("JAVA_HOME", home.toString());
        line.addParameters(arguments);
        return line;
    }

    public static GeneralCommandLine node(Project project, String... arguments) {
        String path = McfppSettings.get(project).nodePath.trim();
        return new GeneralCommandLine(path.isEmpty() ? (SystemInfo.isWindows ? "node.exe" : "node") : path)
                .withParameters(arguments).withCharset(StandardCharsets.UTF_8)
                .withWorkDirectory(project.getBasePath());
    }

    public static Path formatter() throws IOException {
        String override = System.getProperty("mcfpp.formatter.path", "").trim();
        return override.isEmpty()
                ? extractResource("toolchain/formatter.zip", "formatter", "formatterSha256", true).resolve("service.mjs")
                : Path.of(override);
    }

    public static GeneralCommandLine formatterCommand(Project project, String... arguments) throws IOException {
        List<String> args = new ArrayList<>();
        args.add(formatter().toString());
        args.addAll(List.of(arguments));
        var command = node(project, args.toArray(String[]::new));
        var settings = McfppSettings.get(project);
        if (!settings.linterPath.isBlank()) command.withEnvironment("MCFPP_LINTER_HOME", settings.linterPath);
        if (!settings.prettierPath.isBlank()) command.withEnvironment("MCFPP_PRETTIER_HOME", settings.prettierPath);
        return command;
    }

    private static JsonObject readManifest() {
        JsonObject cached = manifestCache;
        if (cached != null) return cached;
        try (InputStream stream = McfppToolchain.class.getResourceAsStream("/toolchain/toolchain.json")) {
            if (stream == null) return null;
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonObject parsed = JsonParser.parseReader(reader).getAsJsonObject();
                manifestCache = parsed;
                return parsed;
            }
        } catch (IOException | RuntimeException error) {
            LOG.debug("Unable to read bundled toolchain manifest", error);
            return null;
        }
    }

    private static String manifestHash(String manifestKey) {
        if (manifestKey == null) return null;
        JsonObject manifest = readManifest();
        if (manifest == null || !manifest.has(manifestKey) || !manifest.get(manifestKey).isJsonPrimitive()) {
            return null;
        }
        String value = manifest.get(manifestKey).getAsString();
        return value != null && value.length() == 64 ? value : null;
    }

    private static String streamSha256(String resource) throws IOException {
        try (InputStream raw = McfppToolchain.class.getResourceAsStream("/" + resource)) {
            if (raw == null) {
                throw new IOException("The plugin is missing " + resource + ". Reinstall the complete plugin ZIP.");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            try (DigestInputStream stream = new DigestInputStream(raw, digest)) {
                while (stream.read(buffer) >= 0) {
                    // stream hash without loading the full archive into heap
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        }
    }

    private static Path toolsRoot() {
        return Path.of(PathManager.getSystemPath(), "mcfpp", "tools");
    }

    private static void pruneStaleToolDirectoriesOnce() {
        if (!PRUNED_STALE_TOOLS.compareAndSet(false, true)) return;
        JsonObject manifest = readManifest();
        if (manifest == null) return;
        Set<String> activeHashes = new HashSet<>();
        for (String key : List.of("compilerSha256", "formatterSha256", "serverSha256")) {
            String hash = manifestHash(key);
            if (hash != null) activeHashes.add(hash);
        }
        if (activeHashes.isEmpty()) return;
        Path root = toolsRoot();
        if (!Files.isDirectory(root)) return;
        try (var children = Files.list(root)) {
            children.filter(Files::isDirectory).forEach(dir -> {
                String name = dir.getFileName().toString();
                if (name.length() == 64 && !activeHashes.contains(name)) {
                    deleteRecursivelyQuietly(dir);
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup of stale plugin versions.
        }
    }

    private static void deleteRecursivelyQuietly(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Ignore locked files from another running IDE instance.
                }
            });
        } catch (IOException ignored) {
        }
    }

    static synchronized Path extractResource(String resource, String targetFileName, String manifestKey, boolean zip) throws IOException {
        Path cached = EXTRACTED_CACHE.get(resource);
        if (cached != null && (zip ? Files.isDirectory(cached) : Files.isRegularFile(cached))) {
            return cached;
        }
        pruneStaleToolDirectoriesOnce();
        String hash = manifestHash(manifestKey);
        if (hash == null) {
            hash = streamSha256(resource);
        }
        Path directory = toolsRoot().resolve(hash);
        Path target = zip ? directory : directory.resolve(targetFileName);
        Path complete = directory.resolve(".complete");
        if (Files.isRegularFile(complete) && (zip ? Files.isDirectory(target) : Files.isRegularFile(target))) {
            EXTRACTED_CACHE.put(resource, target);
            return target;
        }
        Files.createDirectories(directory);
        try (InputStream stream = McfppToolchain.class.getResourceAsStream("/" + resource)) {
            if (stream == null) {
                throw new IOException("The plugin is missing " + resource + ". Reinstall the complete plugin ZIP.");
            }
            if (zip) {
                try (ZipInputStream input = new ZipInputStream(stream)) {
                    ZipEntry entry;
                    while ((entry = input.getNextEntry()) != null) {
                        Path file = directory.resolve(entry.getName()).normalize();
                        if (!file.startsWith(directory)) throw new IOException("Invalid bundled tool path");
                        if (entry.isDirectory()) {
                            Files.createDirectories(file);
                        } else {
                            Files.createDirectories(file.getParent());
                            Path temp = Files.createTempFile(file.getParent(), ".mcfpp-", ".tmp");
                            try {
                                Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
                                moveAtomically(temp, file);
                            } finally {
                                Files.deleteIfExists(temp);
                            }
                        }
                    }
                }
            } else {
                Path temp = Files.createTempFile(directory, ".mcfpp-", ".tmp");
                try {
                    Files.copy(stream, temp, StandardCopyOption.REPLACE_EXISTING);
                    moveAtomically(temp, target);
                } finally {
                    Files.deleteIfExists(temp);
                }
            }
        }
        Path completeTemp = Files.createTempFile(directory, ".complete-", ".tmp");
        try {
            Files.writeString(completeTemp, hash, StandardCharsets.UTF_8);
            moveAtomically(completeTemp, complete);
        } finally {
            Files.deleteIfExists(completeTemp);
        }
        EXTRACTED_CACHE.put(resource, target);
        return target;
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
