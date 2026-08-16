package top.mcfpp.intellij.mni;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McfppLibraryRootsProviderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void discoversSiblingCompilerAndStandardLibrarySources() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("lsp/examples/project"));
        Path javaSources = Files.createDirectories(temporaryDirectory.resolve("MCFPP/src/main/java"));
        Path kotlinSources = Files.createDirectories(temporaryDirectory.resolve("MCFPP/src/main/kotlin"));
        Path mcfppSources = Files.createDirectories(temporaryDirectory.resolve("MCFPP/src/main/mcfpp"));

        List<Path> roots = McfppLibraryRootsProvider.discoverSourceRoots(project.toString(), null, null);

        assertEquals(List.of(javaSources, kotlinSources, mcfppSources), roots);
    }

    @Test
    void acceptsAnExplicitSourceRoot() throws Exception {
        Path sources = Files.createDirectories(temporaryDirectory.resolve("custom-sources"));

        List<Path> roots = McfppLibraryRootsProvider.discoverSourceRoots(null, sources.toString(), null);

        assertEquals(List.of(sources), roots);
    }
}
