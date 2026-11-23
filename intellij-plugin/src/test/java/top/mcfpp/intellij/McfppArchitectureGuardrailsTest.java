package top.mcfpp.intellij;

import org.junit.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/** Fast static guardrails enforcing IntelliJ plugin architecture rules from AGENTS.md. */
public class McfppArchitectureGuardrailsTest {
    private static final int MAX_LINES = 700;
    private static final int JAVA_21_MAJOR_VERSION = 65;
    private static final Pattern PROJECT_AS_DISPOSABLE =
            Pattern.compile("Disposer\\.register\\s*\\([^,\\n]*getProject\\s*\\(\\s*\\)");

    private static Path pluginRoot() {
        Path dir = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(dir.resolve("src/main/java"))) return dir;
        if (Files.isDirectory(dir.resolve("intellij-plugin/src/main/java"))) return dir.resolve("intellij-plugin");
        throw new IllegalStateException("Cannot locate intellij-plugin root from " + dir);
    }

    @Test
    public void neverPassesProjectDirectlyAsParentDisposable() throws IOException {
        Path src = pluginRoot().resolve("src/main/java");
        List<String> violations = new ArrayList<>();
        try (var stream = Files.walk(src)) {
            for (Path file : stream.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (PROJECT_AS_DISPOSABLE.matcher(lines.get(i)).find()) {
                        violations.add(src.relativize(file) + ":" + (i + 1) + " -> " + lines.get(i).trim());
                    }
                }
            }
        }
        assertTrue("Never pass Project directly as parent Disposable (use a project-level Disposable service):\n"
                        + String.join("\n", violations),
                violations.isEmpty());
    }

    @Test
    public void saveListenerDoesNotBlockEdtWithFutureGet() throws IOException {
        Path saveListener = pluginRoot().resolve("src/main/java/top/mcfpp/intellij/format/McfppSaveListener.java");
        String content = Files.readString(saveListener);
        assertTrue("McfppSaveListener must remain strictly asynchronous and never block EDT with Future.get(...)",
                !content.contains("future.get(") && !content.contains(".get(SAVE_FORMAT_TIMEOUT"));
    }

    @Test
    public void sourceFilesStayWithin700LineLimit() throws IOException {
        Path src = pluginRoot().resolve("src/main/java");
        List<String> violations = new ArrayList<>();
        try (var stream = Files.walk(src)) {
            for (Path file : stream.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java")).toList()) {
                int count = Files.readAllLines(file).size();
                if (count > MAX_LINES) {
                    violations.add(src.relativize(file) + " has " + count + " lines (limit: " + MAX_LINES + ")");
                }
            }
        }
        assertTrue("File size guardrail violations:\n" + String.join("\n", violations), violations.isEmpty());
    }

    @Test
    public void compiledClassesStayJava21Compatible() throws IOException {
        Path classesDir = pluginRoot().resolve("build/classes/java/main");
        if (!Files.isDirectory(classesDir)) return;
        List<String> violations = new ArrayList<>();
        try (var stream = Files.walk(classesDir)) {
            for (Path file : stream.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".class")).toList()) {
                try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
                    int magic = in.readInt();
                    if (magic == 0xCAFEBABE) {
                        in.readUnsignedShort(); // minor
                        int major = in.readUnsignedShort();
                        if (major > JAVA_21_MAJOR_VERSION) {
                            violations.add(classesDir.relativize(file) + " has classfile major " + major + " (> 65 / Java 21)");
                        }
                    }
                }
            }
        }
        assertTrue("intellij-plugin classes must stay Java 21 compatible:\n" + String.join("\n", violations),
                violations.isEmpty());
    }
}
