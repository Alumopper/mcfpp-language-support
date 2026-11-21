package top.mcfpp.intellij.tooling;

import com.intellij.openapi.util.SystemInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McfppToolchainAuditTest {
    @Test
    void acceptsJava25AndNewerAndDistinguishesJreFromJdk(@TempDir Path temp) throws Exception {
        String javaBin = SystemInfo.isWindows ? "java.exe" : "java";
        String javacBin = SystemInfo.isWindows ? "javac.exe" : "javac";

        Path jre25 = temp.resolve("jre25");
        Files.createDirectories(jre25.resolve("bin"));
        Files.writeString(jre25.resolve("bin").resolve(javaBin), "");
        Files.writeString(jre25.resolve("release"), "JAVA_VERSION=\"25.0.4\"\n");

        assertTrue(McfppToolchain.isSupportedJava(jre25, false));
        assertFalse(McfppToolchain.isSupportedJava(jre25, true));

        Path jdk26 = temp.resolve("jdk26");
        Files.createDirectories(jdk26.resolve("bin"));
        Files.writeString(jdk26.resolve("bin").resolve(javaBin), "");
        Files.writeString(jdk26.resolve("bin").resolve(javacBin), "");
        Files.writeString(jdk26.resolve("release"), "JAVA_VERSION=\"26.0.2\"\n");

        assertTrue(McfppToolchain.isSupportedJava(jdk26, false));
        assertTrue(McfppToolchain.isSupportedJava(jdk26, true));
        assertTrue(McfppToolchain.isJdk25(jdk26));

        Path jdk21 = temp.resolve("jdk21");
        Files.createDirectories(jdk21.resolve("bin"));
        Files.writeString(jdk21.resolve("bin").resolve(javaBin), "");
        Files.writeString(jdk21.resolve("bin").resolve(javacBin), "");
        Files.writeString(jdk21.resolve("release"), "JAVA_VERSION=\"21.0.5\"\n");

        assertFalse(McfppToolchain.isSupportedJava(jdk21, false));
        assertFalse(McfppToolchain.isSupportedJava(jdk21, true));
    }
}
