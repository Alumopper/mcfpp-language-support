package top.mcfpp.intellij.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/** Checks that a configured source actually provides Minecraft client assets. */
public final class McfppRenderAssets {
    private McfppRenderAssets() {}

    public static String problem(Path root,String configuredPath) {
        if(configuredPath==null || configuredPath.isBlank()) return "Choose a Minecraft client JAR to load vanilla models, textures and fonts.";
        try {
            Path path=Path.of(configuredPath.trim());
            if(!path.isAbsolute() && root!=null) path=root.resolve(path);
            if(Files.isDirectory(path)) {
                Path assets=path.getFileName().toString().equals("assets") ? path : path.resolve("assets");
                if(Files.isRegularFile(assets.resolve("minecraft/font/default.json")) && Files.isDirectory(assets.resolve("minecraft/textures"))) return null;
            } else if(Files.isRegularFile(path)) {
                try(var zip=new ZipFile(path.toFile())) {
                    if(zip.getEntry("assets/minecraft/font/default.json")!=null && zip.stream().anyMatch(entry ->
                        !entry.isDirectory() && entry.getName().startsWith("assets/minecraft/textures/"))) return null;
                }
            }
            return "The selected source has no Minecraft client resources. Select a client game JAR containing assets/minecraft (a server JAR does not contain these resources).";
        } catch(IOException | RuntimeException error) {
            return "Cannot read Minecraft client resources: "+error.getMessage();
        }
    }
}
