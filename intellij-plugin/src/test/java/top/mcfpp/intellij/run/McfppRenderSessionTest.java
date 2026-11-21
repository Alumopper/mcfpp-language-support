package top.mcfpp.intellij.run;

import com.google.gson.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.List;
import static org.junit.Assert.*;

public class McfppRenderSessionTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();

    private Path fixture(boolean test) throws Exception {
        Path root=temporary.newFolder("project with spaces").toPath();
        Files.writeString(root.resolve("mcfpp.toml"),"schema = 1\nname = \"demo\"\nnamespace = \"demo\"\n");
        Path output=root.resolve("build");
        Path pack=output.resolve(test ? "tests/mcfpp/compiled/preview/demo" : "preview/demo");
        Files.createDirectories(pack.resolve("data/demo/function"));
        Files.writeString(pack.resolve("pack.mcmeta"),"{}");
        Files.writeString(pack.resolve("data/demo/function/main.mcfunction"),"say original\n");
        var metadata=new JsonObject(); metadata.addProperty("schema",1); metadata.addProperty("version","1.21.8"); metadata.addProperty("pack",pack.toString());
        var functions=new JsonObject(); var main=new JsonObject(); main.addProperty("name","main"); main.addProperty("entry",true);
        functions.add("demo:main_0",main); metadata.add("functions",functions);
        Files.writeString(McfppRenderSession.metadata(output,test),metadata.toString());
        if(test) Files.writeString(output.resolve("tests/mcfpp/discovery.json"),"{\"schema\":1,\"tests\":[{\"id\":\"demo:sample\",\"function\":\"demo:sample_0\",\"before\":[\"demo:setup\"],\"after\":[\"demo:cleanup\"]}]}");
        return root;
    }

    @Test public void functionUsesCompiledIdAndOwnPackCopy() throws Exception {
        Path root=fixture(false),output=root.resolve("build");
        try(var session=McfppRenderSession.prepare(root,output,false,"demo:main")) {
        String wrapper=Files.readString(session.pack.resolve("data/mcfpp_render/function/entry.mcfunction"));
        assertTrue(wrapper,wrapper.contains("function demo:main_0\n"));
        assertTrue(wrapper,wrapper.contains("stack_frame prepend value {}"));
        Files.writeString(output.resolve("preview/demo/data/demo/function/main.mcfunction"),"say rebuilt");
        assertEquals("say original\n",Files.readString(session.pack.resolve("data/demo/function/main.mcfunction")));
        var args=session.viewportArguments(root,"","");
        assertEquals("viewport",args.getFirst()); assertFalse(args.contains("--autoplay"));
        assertEquals("mcfpp_render:entry",args.get(args.indexOf("--function")+1));
        }
        try(var children=Files.list(output.resolve("render"))) { assertFalse(children.anyMatch(path->path.getFileName().toString().startsWith("session-"))); }
    }

    @Test public void testReplayIncludesSetupAndPreservesStateBeforeCleanup() throws Exception {
        Path root=fixture(true);
        try(var session=McfppRenderSession.prepare(root,root.resolve("build"),true,"demo:sample")) {
        String wrapper=Files.readString(session.pack.resolve("data/mcfpp_render/function/entry.mcfunction"));
        assertTrue(wrapper,wrapper.indexOf("function demo:setup")<wrapper.indexOf("function demo:sample_0"));
        assertFalse(wrapper,wrapper.contains("demo:cleanup"));
        assertTrue(wrapper,wrapper.contains("scoreboard players set #failures mcfpp_test 0"));
        assertEquals("MCFPPTest",session.player);
        }
    }

    @Test public void disabledTestDoesNotCreateReplay() throws Exception {
        Path root=fixture(true),output=root.resolve("build");
        Path discovery=output.resolve("tests/mcfpp/discovery.json");
        var json=McfppRenderSession.read(discovery); json.getAsJsonArray("tests").get(0).getAsJsonObject().addProperty("disabled","later");
        Files.writeString(discovery,json.toString());
        assertNull(McfppRenderSession.prepare(root,output,true,"demo:sample"));
        assertFalse(Files.exists(output.resolve("render")));
    }

    @Test public void generatedAndConfiguredResourcePacksKeepLayerOrder() throws Exception {
        Path root=fixture(false),resources=root.resolve("build/demo-resources");
        Files.createDirectories(resources); Files.writeString(resources.resolve("pack.mcmeta"),"{}");
        Files.createDirectories(root.resolve("client assets")); Files.createDirectories(root.resolve("extra pack"));
        try(var session=McfppRenderSession.prepare(root,root.resolve("build"),false,"demo:main")) {
        var args=session.viewportArguments(root,"client assets","\nextra pack\n");
        assertEquals(root.resolve("client assets").toString(),args.get(args.indexOf("--minecraft-assets")+1));
        assertEquals(session.directory.resolve("resources").toString(),args.get(args.indexOf("--resource-pack")+1));
        assertEquals(root.resolve("extra pack").toString(),args.getLast());
        assertTrue(Files.isRegularFile(session.directory.resolve("resources/pack.mcmeta")));
        }
    }

    @Test public void unknownOrParameterizedEntryIsRejected() throws Exception {
        Path root=fixture(false);
        try { McfppRenderSession.prepare(root,root.resolve("build"),false,"demo:missing"); fail("Missing function was accepted"); }
        catch(java.io.IOException error) { assertTrue(error.getMessage().contains("top-level function without parameters")); }
    }
}
