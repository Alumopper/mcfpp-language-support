package top.mcfpp.intellij.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class McfppDatapackDeploymentTest {
    @TempDir Path root;

    private Path project(String extra) throws Exception {
        Path project=Files.createDirectories(root.resolve("project"));
        Files.writeString(project.resolve("mcfpp.toml"),"schema = 1\nname = \"demo\"\n"+extra);
        return project;
    }
    private void file(Path path,String text) throws Exception {
        Files.createDirectories(path.getParent()); Files.writeString(path,text);
    }
    private McfppDatapackDeployment deploy(Path project,String directory) throws Exception {
        return McfppDatapackDeployment.forProject(project,directory,List.of());
    }
    @Test void copiesConfiguredOutputAndReplacesStaleFilesWithoutTouchingOtherPacks() throws Exception {
        Path project=project("root = \"nested\"\ntargetPath = \"out\"\n");
        Path pack=project.resolve("nested/out/demo");
        file(pack.resolve("pack.mcmeta"),"{}");
        file(pack.resolve("data/demo/function/main.mcfunction"),"say new");
        file(project.resolve("nested/out/demo-resources/pack.mcmeta"),"resource pack");
        Path destination=root.resolve("存档 with spaces/datapacks");
        file(destination.resolve("other/pack.mcmeta"),"other pack");
        var deployment=deploy(project,destination.toString());
        deployment.copy();
        file(destination.resolve("demo/data/demo/function/old.mcfunction"),"say old");
        deployment.copy();
        assertEquals("say new",Files.readString(destination.resolve("demo/data/demo/function/main.mcfunction")));
        assertFalse(Files.exists(destination.resolve("demo/data/demo/function/old.mcfunction")));
        assertEquals("other pack",Files.readString(destination.resolve("other/pack.mcmeta")));
        assertFalse(Files.exists(destination.resolve("demo-resources")));
        try(var children=Files.list(destination)) { assertEquals(2,children.count()); }
    }
    @Test void failedBuildSkipsDeploymentAndMissingOutputFailsWithoutChangingOldPack() throws Exception {
        Path project=project("");
        file(root.resolve("world/demo/pack.mcmeta"),"old");
        var deployment=deploy(project,"../world");
        var messages=new StringBuilder();
        assertEquals(7,deployment.finish(7,(message,error) -> fail("Failed builds must not deploy")));
        assertEquals(1,deployment.finish(0,(message,error) -> { assertTrue(error); messages.append(message); }));
        assertTrue(messages.toString().contains("Built datapack is missing"));
        assertEquals("old",Files.readString(root.resolve("world/demo/pack.mcmeta")));
        file(project.resolve("build/demo/pack.mcmeta"),"new");
        assertEquals(0,deployment.finish(0,(message,error) -> { assertFalse(error); messages.append(message); }));
        assertTrue(messages.toString().contains("deployed to:"));
    }
    @Test void rejectsOverlapsAndUnrelatedDirectoriesBeforeWriting() throws Exception {
        Path project=project("");
        file(project.resolve("build/demo/pack.mcmeta"),"original");
        assertThrows(Exception.class,() -> deploy(project,"build"));
        assertThrows(Exception.class,() -> deploy(project,"build/demo/nested"));
        file(root.resolve("world/demo/keep.txt"),"keep");
        assertThrows(Exception.class,() -> deploy(project,"../world"));
        assertEquals("keep",Files.readString(root.resolve("world/demo/keep.txt")));
        assertThrows(Exception.class,() -> deploy(project,""));
    }
    @Test void usesDebugOutputAndCliOverride() throws Exception {
        Path project=project("args = [\"--debug-info\"]\n");
        file(project.resolve("build/debug/demo/pack.mcmeta"),"debug");
        file(project.resolve("build/demo/pack.mcmeta"),"normal");
        deploy(project,"../world").copy();
        assertEquals("debug",Files.readString(root.resolve("world/demo/pack.mcmeta")));
        McfppDatapackDeployment.forProject(project,"../world",List.of("--debug-info=false")).copy();
        assertEquals("normal",Files.readString(root.resolve("world/demo/pack.mcmeta")));
    }
}
