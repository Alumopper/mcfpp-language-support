package top.mcfpp.intellij.mni;

import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.roots.SyntheticLibrary;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.Collection;
import java.nio.file.Files;
import java.nio.file.Path;

public final class McfppLibraryRootsIntegrationTest extends BasePlatformTestCase {
    public void testAttachedSourcesAreVisibleAndPresentedAsExternalLibrary() throws Exception {
        Path temporaryDirectory = Files.createTempDirectory("mcfpp-library-roots-");
        Path compilerPath = temporaryDirectory.resolve("compiler-checkout");
        Path javaPath = Files.createDirectories(compilerPath.resolve("src/main/java"));
        Path kotlinPath = Files.createDirectories(compilerPath.resolve("src/main/kotlin"));
        Path mcfppPath = Files.createDirectories(compilerPath.resolve("src/main/mcfpp"));
        VirtualFile compilerRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(compilerPath);
        VirtualFile javaRoot = LocalFileSystem.getInstance().findFileByNioFile(javaPath);
        VirtualFile kotlinRoot = LocalFileSystem.getInstance().findFileByNioFile(kotlinPath);
        VirtualFile mcfppRoot = LocalFileSystem.getInstance().findFileByNioFile(mcfppPath);
        assertNotNull(compilerRoot);
        assertNotNull(javaRoot);
        assertNotNull(kotlinRoot);
        assertNotNull(mcfppRoot);
        String previous = System.getProperty("mcfpp.sources.path");
        try {
            System.setProperty("mcfpp.sources.path", compilerRoot.getPath());

            Collection<SyntheticLibrary> libraries = new McfppLibraryRootsProvider()
                    .getAdditionalProjectLibraries(getProject());

            assertSize(1, libraries);
            SyntheticLibrary library = libraries.iterator().next();
            assertTrue(library.isShowInExternalLibrariesNode());
            assertInstanceOf(library, ItemPresentation.class);
            assertEquals(
                    "MCFPP standard library and MNI sources",
                    ((ItemPresentation) library).getPresentableText()
            );
            assertContainsElements(library.getSourceRoots(), javaRoot, kotlinRoot, mcfppRoot);
        } finally {
            if (previous == null) System.clearProperty("mcfpp.sources.path");
            else System.setProperty("mcfpp.sources.path", previous);
            FileUtil.delete(temporaryDirectory.toFile());
        }
    }
}
