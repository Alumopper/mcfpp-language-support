package top.mcfpp.intellij.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.mcfpp.intellij.tooling.McfppRenderAssets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

final class McfppRenderAssetsTest {
    @TempDir Path root;

    @Test void missingResourcesAndServerJarsNeedAClientSource() throws Exception {
        assertNotNull(McfppRenderAssets.problem(root,""));
        assertNotNull(McfppRenderAssets.problem(root,"missing.jar"));
        Path server=root.resolve("server.jar");
        try(var zip=new ZipOutputStream(Files.newOutputStream(server))) {
            zip.putNextEntry(new ZipEntry("server.properties")); zip.closeEntry();
        }
        assertNotNull(McfppRenderAssets.problem(root,server.toString()));
    }

    @Test void readsClientResourcesFromAJarAndResolvesRelativePaths() throws Exception {
        Path client=root.resolve("client game.jar");
        try(var zip=new ZipOutputStream(Files.newOutputStream(client))) {
            for(String entry:new String[]{"assets/minecraft/font/default.json","assets/minecraft/textures/block/stone.png"}) {
                zip.putNextEntry(new ZipEntry(entry)); zip.write(new byte[]{1}); zip.closeEntry();
            }
        }
        assertNull(McfppRenderAssets.problem(root,client.getFileName().toString()));
        assertNull(McfppRenderAssets.problem(root,client.toString()));
    }

    @Test void acceptsBothResourceRootAndAssetsDirectory() throws Exception {
        Path assets=root.resolve("assets");
        Files.createDirectories(assets.resolve("minecraft/font"));
        Files.createDirectories(assets.resolve("minecraft/textures"));
        Files.writeString(assets.resolve("minecraft/font/default.json"),"{}");
        assertNull(McfppRenderAssets.problem(root,root.toString()));
        assertNull(McfppRenderAssets.problem(root,assets.toString()));
    }

    @Test void unreadableArchivesGiveAnActionableError() throws Exception {
        Path broken=root.resolve("broken.jar"); Files.writeString(broken,"not a ZIP");
        assertTrue(McfppRenderAssets.problem(root,broken.toString()).startsWith("Cannot read Minecraft client resources:"));
    }
}
