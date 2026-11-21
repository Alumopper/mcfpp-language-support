package top.mcfpp.intellij.run;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicBoolean;

/** A leased temporary pack; explicit exports and the last log outlive the viewport. */
final class McfppRenderWorkspace implements AutoCloseable {
    final Path directory,exports,lastLog;
    private final Path root;
    private final FileChannel lease;
    private final FileLock lock;
    private final AtomicBoolean closed=new AtomicBoolean();

    private McfppRenderWorkspace(Path root,Path directory,FileChannel lease,FileLock lock) {
        this.root=root; this.directory=directory; this.lease=lease; this.lock=lock;
        exports=root.resolve("exports"); lastLog=root.resolve("dsb.log");
    }

    static McfppRenderWorkspace create(Path output) throws IOException {
        Path root=Files.createDirectories(output.resolve("render")).toRealPath();
        prune(root);
        Path directory=Files.createTempDirectory(root,"session-");
        FileChannel channel=null;
        try {
            channel=FileChannel.open(directory.resolve(".session.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            FileLock lock=channel.lock();
            Files.writeString(directory.resolve(".mcfpp-session"),"1\n");
            return new McfppRenderWorkspace(root,directory,channel,lock);
        } catch(IOException | RuntimeException failure) {
            if(channel!=null) channel.close();
            delete(root,directory);
            throw failure;
        }
    }

    private static void prune(Path root) throws IOException {
        try(var children=Files.list(root)) {
            for(Path directory:children.toList()) {
                if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS) || !directory.getFileName().toString().matches("session-[0-9]+")) continue;
                boolean owned=Files.isRegularFile(directory.resolve(".mcfpp-session"),LinkOption.NOFOLLOW_LINKS);
                boolean legacy=Files.isRegularFile(directory.resolve("pack/pack.mcmeta"),LinkOption.NOFOLLOW_LINKS) &&
                    Files.isRegularFile(directory.resolve("pack/data/mcfpp_render/function/entry.mcfunction"),LinkOption.NOFOLLOW_LINKS);
                if(!owned && !legacy) continue;
                try(var channel=FileChannel.open(directory.resolve(".session.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
                    try(var lock=channel.tryLock()) { if(lock==null) continue; preserveExports(root,directory); }
                } catch(OverlappingFileLockException | NoSuchFileException activeOrRemoved) { continue; }
                delete(root,directory);
            }
        }
    }

    private static void preserveExports(Path root,Path directory) throws IOException {
        try(var children=Files.list(directory)) {
            for(Path file:children.toList()) if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) && file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
                Path exports=Files.createDirectories(root.resolve("exports"));
                Files.move(file,exports.resolve(directory.getFileName()+"-"+file.getFileName()));
            }
        }
    }

    private static void delete(Path root,Path directory) throws IOException {
        Path target=directory.toAbsolutePath().normalize();
        if(!root.equals(target.getParent()) || !target.getFileName().toString().matches("session-[0-9]+"))
            throw new IOException("Render session is outside its temporary directory: "+target);
        if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(target,new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path path,IOException failure) throws IOException {
                if(failure!=null) throw failure;
                Files.delete(path); return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override public void close() throws IOException {
        if(!closed.compareAndSet(false,true)) return;
        try {
            preserveExports(root,directory);
            Path log=directory.resolve("dsb.log");
            if(Files.isRegularFile(log,LinkOption.NOFOLLOW_LINKS)) Files.copy(log,lastLog,StandardCopyOption.REPLACE_EXISTING);
        } finally {
            try { lock.release(); } finally { lease.close(); delete(root,directory); }
        }
    }
}
