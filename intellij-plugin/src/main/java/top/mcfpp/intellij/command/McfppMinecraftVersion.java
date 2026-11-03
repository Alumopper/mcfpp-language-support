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
        Path configuration = top.mcfpp.language.ProjectConfiguration.find(Path.of(basePath));
        if (configuration == null) return FALLBACK_VERSION;
        try {
            return top.mcfpp.language.ProjectConfiguration.read(configuration).version();
        } catch (IOException | RuntimeException exception) {
            LOG.warn("Unable to read the Minecraft version from " + configuration, exception);
        }
        return FALLBACK_VERSION;
    }
}
