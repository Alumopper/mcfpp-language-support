package top.mcfpp.intellij.tooling;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.SystemInfo;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipInputStream;

public final class McfppToolchain {
    private McfppToolchain() {}
    public static Path javaHome(Project project) throws IOException {
        String explicit = McfppSettings.get(project).javaHome.trim();
        if (!explicit.isEmpty()) {
            Path path = Path.of(explicit);
            if (!isJdk21(path)) throw new IOException("Select a JDK 21 installation in Settings | Tools | MCFPP.");
            return path;
        }
        List<String> candidates = new ArrayList<>();
        var sdk = ProjectRootManager.getInstance(project).getProjectSdk();
        if (sdk != null) candidates.add(sdk.getHomePath());
        for (String variable : List.of("MCFPP_JAVA_HOME", "JDK21_HOME", "JAVA_HOME")) candidates.add(System.getenv(variable));
        for (var candidate : ProjectJdkTable.getInstance().getAllJdks()) candidates.add(candidate.getHomePath());
        candidates.add(System.getProperty("java.home"));
        for (String path : candidates) if (path != null && isJdk21(Path.of(path))) return Path.of(path);
        throw new IOException("MCFPP requires JDK 21. Select it in Settings | Tools | MCFPP.");
    }
    public static boolean isJdk21(Path path) {
        try {
            return Files.isRegularFile(path.resolve("bin/" + (SystemInfo.isWindows ? "javac.exe" : "javac"))) &&
                Files.readString(path.resolve("release")).matches("(?s).*JAVA_VERSION=\"21[.\"][^\n]*.*");
        } catch (IOException | RuntimeException ignored) { return false; }
    }
    public static Path compiler(Project project) throws IOException {
        String configured = McfppSettings.get(project).compilerJar.trim();
        Path jar = configured.isEmpty() ? extract("toolchain/mcfpp-cli.jar", false) : Path.of(configured);
        if (!Files.isRegularFile(jar)) throw new IOException("MCFPP compiler JAR does not exist: " + jar);
        return jar;
    }
    public static GeneralCommandLine cli(Project project, Path root, List<String> arguments) throws IOException {
        Path home = javaHome(project);
        GeneralCommandLine line = new GeneralCommandLine(home.resolve("bin/" + (SystemInfo.isWindows ? "java.exe" : "java")).toString(),
            "-Dfile.encoding=UTF-8", "-jar", compiler(project).toString()).withCharset(java.nio.charset.StandardCharsets.UTF_8).withWorkDirectory(root.toString());
        line.withEnvironment("MCFPP_JAVA_HOME", home.toString()).withEnvironment("JAVA_HOME", home.toString());
        line.addParameters(arguments);
        return line;
    }
    public static GeneralCommandLine node(Project project, String... arguments) {
        String path = McfppSettings.get(project).nodePath.trim();
        return new GeneralCommandLine(path.isEmpty() ? (SystemInfo.isWindows ? "node.exe" : "node") : path)
            .withParameters(arguments).withCharset(java.nio.charset.StandardCharsets.UTF_8)
            .withWorkDirectory(project.getBasePath());
    }
    public static Path formatter() throws IOException {
        String override = System.getProperty("mcfpp.formatter.path", "").trim();
        return override.isEmpty() ? extract("toolchain/formatter.zip", true).resolve("service.mjs") : Path.of(override);
    }
    public static GeneralCommandLine formatterCommand(Project project, String... arguments) throws IOException {
        java.util.List<String> args = new java.util.ArrayList<>();
        args.add(formatter().toString()); args.addAll(java.util.List.of(arguments));
        var command = node(project, args.toArray(String[]::new));
        var settings = McfppSettings.get(project);
        if (!settings.linterPath.isBlank()) command.withEnvironment("MCFPP_LINTER_HOME", settings.linterPath);
        if (!settings.prettierPath.isBlank()) command.withEnvironment("MCFPP_PRETTIER_HOME", settings.prettierPath);
        return command;
    }
    private static synchronized Path extract(String resource, boolean zip) throws IOException {
        try (InputStream stream = McfppToolchain.class.getResourceAsStream("/" + resource)) {
            if (stream == null) throw new IOException("The plugin is missing " + resource + ". Reinstall the complete plugin ZIP.");
            byte[] bytes = stream.readAllBytes();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            Path directory = Path.of(PathManager.getSystemPath(), "mcfpp", "tools", hash);
            Path target = zip ? directory : directory.resolve("mcfpp-cli.jar");
            Path complete = directory.resolve(".complete");
            if (Files.isRegularFile(complete)) return target;
            Files.createDirectories(directory);
            if (zip) {
                try (var input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                    java.util.zip.ZipEntry entry;
                    while ((entry = input.getNextEntry()) != null) {
                        Path file = directory.resolve(entry.getName()).normalize();
                        if (!file.startsWith(directory)) throw new IOException("Invalid bundled tool path");
                        if (entry.isDirectory()) Files.createDirectories(file);
                        else { Files.createDirectories(file.getParent()); Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING); }
                    }
                }
            } else Files.write(target, bytes);
            Files.writeString(complete, hash);
            return target;
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }
}

