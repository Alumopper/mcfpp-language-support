package top.mcfpp.intellij.command;

import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class McfppDatapackSandboxResource {
    private static final Logger LOG = Logger.getInstance(McfppDatapackSandboxResource.class);
    private static final PluginId PLUGIN_ID = PluginId.getId("top.mcfpp.language");
    private static final String RESOURCE_PATH = "/dps/datapack-sandbox-cli.jar";

    private static Path extractedPath;

    private McfppDatapackSandboxResource() {
    }

    static synchronized Path extract() {
        if (extractedPath != null && Files.isRegularFile(extractedPath)) return extractedPath;

        IdeaPluginDescriptor descriptor = PluginManagerCore.getPlugin(PLUGIN_ID);
        String version = descriptor == null ? "development" : descriptor.getVersion();
        Path directory = Path.of(System.getProperty("java.io.tmpdir"), "mcfpp-intellij", version, "dps");
        try (InputStream resource = McfppDatapackSandboxResource.class.getResourceAsStream(RESOURCE_PATH)) {
            if (resource == null) {
                throw new IllegalStateException("Bundled Datapack Sandbox service is missing: " + RESOURCE_PATH);
            }
            ResourceDigest digest = digest(resource);
            Path target = directory.resolve("datapack-sandbox-" + digest.sha256().substring(0, 16) + ".jar");
            if (!Files.isRegularFile(target) || Files.size(target) != digest.size()) {
                Files.createDirectories(directory);
                Path temporary = Files.createTempFile(directory, "datapack-sandbox-", ".jar");
                try (InputStream copySource = McfppDatapackSandboxResource.class.getResourceAsStream(RESOURCE_PATH)) {
                    if (copySource == null) throw new IllegalStateException("Bundled Datapack Sandbox service disappeared");
                    Files.copy(copySource, temporary, StandardCopyOption.REPLACE_EXISTING);
                    moveAtomically(temporary, target);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            extractedPath = target;
            return target;
        } catch (IOException exception) {
            LOG.warn("Unable to extract the bundled Datapack Sandbox command service", exception);
            throw new IllegalStateException("Unable to prepare Minecraft command support", exception);
        }
    }

    private static ResourceDigest digest(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            long size = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                digest.update(buffer, 0, read);
                size += read;
            }
            return new ResourceDigest(HexFormat.of().formatHex(digest.digest()), size);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record ResourceDigest(String sha256, long size) {
    }
}
