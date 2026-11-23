package top.mcfpp.intellij.run;

import com.google.gson.*;
import com.intellij.util.execution.ParametersListUtil;
import top.mcfpp.language.ProjectConfiguration;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** A private pack copy and entry point for one interactive replay. */
final class McfppRenderSession implements AutoCloseable {
    final Path directory,pack;
    final String version,player;
    final List<Path> resourcePacks;
    private final McfppRenderWorkspace workspace;

    private McfppRenderSession(McfppRenderWorkspace workspace,Path pack,String version,String player,List<Path> resourcePacks) {
        this.workspace=workspace; this.directory=workspace.directory; this.pack=pack; this.version=version; this.player=player; this.resourcePacks=resourcePacks;
    }

    static String target(McfppRunConfiguration configuration) {
        if(configuration.task==McfppTask.BUILD) {
            if(configuration.renderFunction.isBlank()) throw new IllegalArgumentException("Select a render function (namespace:function).");
            return configuration.renderFunction.trim();
        }
        var args=ParametersListUtil.parse(configuration.arguments);
        String id=null;
        for(int i=0;i<args.size();i++) {
            if(args.get(i).equals("--tests") && i+1<args.size()) id=args.get(++i);
            else if(args.get(i).startsWith("--tests=")) id=args.get(i).substring(8);
        }
        if(id==null || id.isBlank() || id.contains("*")) throw new IllegalArgumentException("Select one MCFPP test to render using its gutter action or --tests namespace:function.");
        return id;
    }

    static Path metadata(Path output,boolean test) {
        return output.resolve(test ? "tests/mcfpp/compiled/preview/mcfpp.render.json" : "preview/mcfpp.render.json");
    }

    static JsonObject read(Path path) throws IOException {
        if(!Files.isRegularFile(path)) throw new IOException("MCFPP render preparation did not produce "+path);
        try { return JsonParser.parseString(Files.readString(path)).getAsJsonObject(); }
        catch(RuntimeException error) { throw new IOException("Invalid MCFPP render metadata: "+path,error); }
    }

    static McfppRenderSession prepare(Path root,Path output,boolean test,String target) throws IOException {
        var metadata=read(metadata(output,test));
        if(metadata.get("schema").getAsInt()!=1) throw new IOException("Unsupported MCFPP render metadata schema.");
        var sequence=new ArrayList<String>();
        if(test) {
            var selected=selectedTest(output,target);
            if(selected.has("disabled") && !selected.get("disabled").isJsonNull()) return null;
            for(var hook:selected.getAsJsonArray("before")) sequence.add(hook.getAsString());
            sequence.add(selected.get("function").getAsString());
        } else {
            sequence.add(resolveEntry(metadata,target));
        }
        Path source=Path.of(metadata.get("pack").getAsString());
        if(!Files.isRegularFile(source.resolve("pack.mcmeta"))) throw new IOException("Built render datapack is missing: "+source);
        var workspace=McfppRenderWorkspace.create(output);
        try {
        Path directory=workspace.directory;
        Path pack=directory.resolve("pack");
        copyTree(source,pack);
        Path wrapper=pack.resolve("data/mcfpp_render/function/entry.mcfunction");
        if(Files.exists(wrapper)) throw new IOException("Reserved render function already exists: mcfpp_render:entry");
        Files.createDirectories(wrapper.getParent());
        var text=new StringBuilder();
        if(test) text.append("tp MCFPPTest 0 64 0\nscoreboard objectives add mcfpp_test dummy\nscoreboard players set #failures mcfpp_test 0\n");
        for(String function:sequence) {
            text.append("data modify storage mcfpp:system stack_frame prepend value {}\nfunction ").append(function)
                .append("\ndata remove storage mcfpp:system stack_frame[0]\n");
        }
        Files.writeString(wrapper,text);
        var packs=new ArrayList<Path>();
        String name=org.tomlj.Toml.parse(Files.readString(root.resolve("mcfpp.toml"))).getString("name");
        if(name==null) name=root.getFileName().toString();
        Path resources=source.getParent().resolve(name+"-resources");
        if(!Files.isDirectory(resources)) resources=output.resolve(name+"-resources");
        if(Files.isRegularFile(resources.resolve("pack.mcmeta"))) {
            Path copy=directory.resolve("resources"); copyTree(resources,copy); packs.add(copy);
        }
        return new McfppRenderSession(workspace,pack,metadata.get("version").getAsString(),test ? "MCFPPTest" : "MCFPPPreview",packs);
        } catch(IOException | RuntimeException failure) {
            try { workspace.close(); } catch(IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    static String resolveEntry(JsonObject metadata,String target) throws IOException {
        var functions=metadata.getAsJsonObject("functions");
        var matches=functions.entrySet().stream().filter(entry -> {
            String name=entry.getValue().getAsJsonObject().get("name").getAsString();
            return target.isBlank() ? name.equals("main") : entry.getKey().equals(target) ||
                (entry.getKey().substring(0,entry.getKey().indexOf(':'))+":"+name).equals(target);
        }).toList();
        if(matches.size()!=1 || !matches.getFirst().getValue().getAsJsonObject().get("entry").getAsBoolean())
            throw new IOException("Select a top-level function without parameters: "+(target.isBlank() ? "main" : target));
        return matches.getFirst().getKey();
    }

    static JsonObject selectedTest(Path output,String target) throws IOException {
        for(var test:read(output.resolve("tests/mcfpp/discovery.json")).getAsJsonArray("tests"))
            if(test.getAsJsonObject().get("id").getAsString().equals(target)) return test.getAsJsonObject();
        throw new IOException("MCFPP test was not found: "+target);
    }

    List<String> viewportArguments(Path root,String assets,String extraPacks) throws IOException {
        var args=new ArrayList<>(List.of("viewport","--version",version,"--pack",pack.toString(),
            "--function","mcfpp_render:entry","--input-player",player,"--export-directory",workspace.exports.toString()));
        if(!assets.isBlank()) { args.add("--minecraft-assets"); args.add(existingPath(root,assets).toString()); }
        for(Path path:resourcePacks) { args.add("--resource-pack"); args.add(path.toString()); }
        for(String path:extraPacks.lines().map(String::trim).filter(s -> !s.isEmpty()).toList()) {
            args.add("--resource-pack"); args.add(existingPath(root,path).toString());
        }
        return args;
    }

    Path lastLog() { return workspace.lastLog; }
    @Override public void close() throws IOException { workspace.close(); }

    private static Path existingPath(Path root,String path) throws IOException {
        Path resolved=root.resolve(path).toAbsolutePath().normalize();
        if(!Files.exists(resolved)) throw new IOException("Render asset path does not exist: "+resolved+". Configure it in Settings | Tools | MCFPP.");
        return resolved;
    }

    private static void copyTree(Path source,Path target) throws IOException {
        Files.walkFileTree(source,new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs) throws IOException {
                if(Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                Files.createDirectories(target.resolve(source.relativize(dir))); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs) throws IOException {
                if(!attrs.isRegularFile()) throw new IOException("Cannot preview a linked or special file: "+file);
                Files.copy(file,target.resolve(source.relativize(file))); return FileVisitResult.CONTINUE;
            }
        });
    }
}
