package top.mcfpp.intellij.run;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import static org.junit.Assert.*;

public class McfppRenderWorkspaceTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();

    @Test public void closeDeletesTemporaryPackAndRetainsOnlyLastLogAndExplicitExports() throws Exception {
        Path output=temporary.newFolder().toPath(),directory;
        try(var workspace=McfppRenderWorkspace.create(output)) {
            directory=workspace.directory;
            Files.createDirectories(directory.resolve("pack/data"));
            Files.writeString(directory.resolve("pack/data/test"),"temporary");
            Files.writeString(directory.resolve("dsb.log"),"first");
            Files.createDirectories(workspace.exports);
            Files.writeString(workspace.exports.resolve("chosen.png"),"export");
        }
        assertFalse(Files.exists(directory));
        assertEquals("first",Files.readString(output.resolve("render/dsb.log")));
        try(var workspace=McfppRenderWorkspace.create(output)) { Files.writeString(workspace.directory.resolve("dsb.log"),"last"); }
        assertEquals("last",Files.readString(output.resolve("render/dsb.log")));
        assertEquals("export",Files.readString(output.resolve("render/exports/chosen.png")));
    }

    @Test public void startupReclaimsAbandonedLegacyPacksButKeepsActiveAndUnrelatedDirectories() throws Exception {
        Path output=temporary.newFolder().toPath();
        try(var active=McfppRenderWorkspace.create(output)) {
            Path abandoned=Files.createDirectories(output.resolve("render/session-123"));
            Files.createDirectories(abandoned.resolve("pack/data/mcfpp_render/function"));
            Files.writeString(abandoned.resolve("pack/pack.mcmeta"),"{}");
            Files.writeString(abandoned.resolve("pack/data/mcfpp_render/function/entry.mcfunction"),"say preview");
            Files.writeString(abandoned.resolve("saved.png"),"legacy export");
            Path unrelated=Files.createDirectories(output.resolve("render/session-456"));
            Files.writeString(unrelated.resolve("mine"),"keep");
            try(var next=McfppRenderWorkspace.create(output)) {
                assertTrue(Files.isDirectory(active.directory));
                assertTrue(Files.isDirectory(next.directory));
                assertFalse(Files.exists(abandoned));
                assertEquals("keep",Files.readString(unrelated.resolve("mine")));
                assertEquals("legacy export",Files.readString(output.resolve("render/exports/session-123-saved.png")));
            }
        }
    }
}
