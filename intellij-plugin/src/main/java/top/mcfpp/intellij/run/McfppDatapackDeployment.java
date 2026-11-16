package top.mcfpp.intellij.run;

import org.tomlj.Toml;
import top.mcfpp.language.ProjectConfiguration;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.BiConsumer;
import java.util.List;

/** Copies only the built datapack, keeping other packs in the destination directory intact. */
final class McfppDatapackDeployment {
    private final Path project,source,destination;

    private McfppDatapackDeployment(Path project,Path source,Path destination) {
        this.project=project; this.source=source; this.destination=destination;
    }

    static McfppDatapackDeployment forProject(Path project,String directory,List<String> arguments) throws IOException {
        if(directory.isBlank()) throw new IOException("Select a deployment directory.");
        project=project.toAbsolutePath().normalize();
        Path file=project.resolve("mcfpp.toml");
        String text=Files.readString(file);
        var config=ProjectConfiguration.parse(file,text);
        var toml=Toml.parse(text);
        if(Boolean.TRUE.equals(toml.getBoolean("noDatapack"))) throw new IOException("noDatapack is enabled in mcfpp.toml.");
        String name=toml.getString("name");
        if(name==null) name=project.getFileName().toString();
        if(name.isBlank() || name.equals(".") || name.equals("..") || name.matches(".*[/\\\\<>:\"|?*\\p{Cntrl}].*") || name.endsWith(".") || name.endsWith(" "))
            throw new IOException("Invalid datapack name: "+name);
        var fileArguments=toml.getArray("args");
        boolean debug=booleanFlag(arguments,"--debug-info",fileArguments==null ? false :
            booleanFlag(fileArguments.toList().stream().map(Object::toString).toList(),"--debug-info",false));
        Path output=debug ? config.targetPath().resolve("debug") : config.targetPath();
        var deployment=new McfppDatapackDeployment(project,output.resolve(name),project.resolve(directory).normalize().resolve(name));
        deployment.validate();
        return deployment;
    }

    private static boolean booleanFlag(List<String> arguments,String flag,boolean fallback) {
        for(int i=0;i<arguments.size();i++) {
            String argument=arguments.get(i);
            if(argument.startsWith(flag+"=")) return Boolean.parseBoolean(argument.substring(flag.length()+1));
            if(argument.equals(flag)) return i+1>=arguments.size() || !arguments.get(i+1).equalsIgnoreCase("false");
        }
        return fallback;
    }

    private void validate() throws IOException {
        Path from=realLocation(source),to=realLocation(destination),root=project.toRealPath();
        if(from.startsWith(to) || to.startsWith(from) || root.startsWith(to))
            throw new IOException("Deployment must not overlap the built datapack or replace the project: "+destination);
        if(Files.isSymbolicLink(destination) || Files.exists(destination,LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(destination))
            throw new IOException("Deployment path must be a regular directory: "+destination);
        // Refuse to replace an unrelated directory, even when its name matches the project.
        if(Files.exists(destination) && !Files.isRegularFile(destination.resolve("pack.mcmeta")))
            throw new IOException("Existing destination is not a datapack (pack.mcmeta is missing): "+destination);
    }

    private static Path realLocation(Path path) throws IOException {
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)) return path.toRealPath();
        return realLocation(path.getParent()).resolve(path.getFileName());
    }

    int finish(int exitCode,BiConsumer<String,Boolean> output) {
        if(exitCode!=0) return exitCode;
        try {
            copy();
            output.accept("\nMCFPP datapack deployed to: "+destination+"\n",false);
            return 0;
        } catch(Exception e) {
            output.accept("\nMCFPP datapack deployment failed: "+e.getMessage()+"\n",true);
            return 1;
        }
    }

    void copy() throws IOException {
        validate();
        if(!Files.isRegularFile(source.resolve("pack.mcmeta")))
            throw new IOException("Built datapack is missing: "+source);
        Files.createDirectories(destination.getParent());
        Path staging=Files.createTempDirectory(destination.getParent(),".mcfpp-deploy-");
        Path backup=null;
        try {
            copyTree(source,staging);
            // Copy completely before touching the previous deployment.
            validate();
            if(Files.exists(destination)) {
                backup=Files.createTempDirectory(destination.getParent(),".mcfpp-backup-");
                Files.delete(backup);
                Files.move(destination,backup);
            }
            try { Files.move(staging,destination); }
            catch(IOException failure) {
                if(backup!=null) try { Files.move(backup,destination); backup=null; }
                catch(IOException restoreFailure) {
                    throw new IOException("Could not replace datapack; previous version is at "+backup,failure);
                }
                throw failure;
            }
            if(backup!=null) deleteTree(backup);
        } finally { deleteTree(staging); }
    }

    private static void copyTree(Path source,Path target) throws IOException {
        Files.walkFileTree(source,new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs) throws IOException {
                if(!attrs.isRegularFile()) throw new IOException("Cannot deploy linked or special file: "+file);
                Files.copy(file,target.resolve(source.relativize(file)),StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path root) throws IOException {
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root,new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir,IOException error) throws IOException {
                if(error!=null) throw error;
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }
}
