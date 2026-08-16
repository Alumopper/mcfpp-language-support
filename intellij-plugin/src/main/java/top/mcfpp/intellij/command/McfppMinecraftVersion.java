package top.mcfpp.intellij.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class McfppMinecraftVersion {
    private static final Logger LOG = Logger.getInstance(McfppMinecraftVersion.class);
    private static final String FALLBACK_VERSION = "1.21.8";

    private McfppMinecraftVersion() {
    }

    static String resolve(Project project) {
        String override = System.getProperty("mcfpp.minecraft.version", "").trim();
        if (!override.isEmpty()) return override;
        String basePath = project.getBasePath();
        if (basePath == null) return FALLBACK_VERSION;
        Path configuration = Path.of(basePath, "mcfpp.json");
        if (!Files.isRegularFile(configuration)) return FALLBACK_VERSION;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(configuration));
            if (root.isJsonObject() && root.getAsJsonObject().has("version")) {
                String version = root.getAsJsonObject().get("version").getAsString().trim();
                if (!version.isEmpty()) return version;
            }
        } catch (IOException | RuntimeException exception) {
            LOG.warn("Unable to read the Minecraft version from " + configuration, exception);
        }
        return FALLBACK_VERSION;
    }
}
