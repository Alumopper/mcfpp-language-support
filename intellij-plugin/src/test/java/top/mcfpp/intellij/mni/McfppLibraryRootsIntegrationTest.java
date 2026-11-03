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
    public void testTestingApiResolvesWhenLocalCompilerSourcesReplaceBundledStdlib() throws Exception {
        Path compiler = Files.createTempDirectory("mcfpp-test-api-roots-");
        Path sources = Files.createDirectories(compiler.resolve("src/main/mcfpp"));
        Files.writeString(compiler.resolve("mcfpp.json"), "{\"namespace\":\"mcfpp\"}");
        Files.writeString(sources.resolve("sys.mcfpp"), "namespace mcfpp.sys\nfunc print(value: int) {}\n");
        assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sources));
        var plugin = com.intellij.ide.plugins.PluginManagerCore.getPlugin(com.intellij.openapi.extensions.PluginId.getId("top.mcfpp.language"));
        assertNotNull(plugin);
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(getTestRootDisposable(), plugin.getPluginPath().toString());
        String previous = System.getProperty("mcfpp.sources.path");
        try {
            System.setProperty("mcfpp.sources.path", compiler.toString());
            var bundledApi = com.intellij.openapi.vfs.VfsUtil.findFileByURL(
                    McfppLibraryRootsProvider.class.getResource("/stdlib/src/main/mcfpp/test/assertions.mcfpp"));
            assertNotNull(bundledApi);
            assertTrue("Direct library indexing must retain the bundled test API alongside local compiler sources",
                    McfppLibraryRootsProvider.mcfppSourceRoots(getProject()).stream().anyMatch(root ->
                            com.intellij.openapi.vfs.VfsUtilCore.isAncestor(root, bundledApi, false)));
            var file = myFixture.configureByText(top.mcfpp.intellij.lang.McfppFileType.INSTANCE,
                    "namespace tests\nimport mcfpp.test:*\nfunc comparison() { assertNotEquals(1, 2); }\n");
            var reference = file.findReferenceAt(file.getText().indexOf("assertNotEquals"));
            assertNotNull(reference);
            var declaration = reference.resolve();
            assertNotNull("The bundled test API must remain visible alongside a local compiler checkout", declaration);
            assertEquals("assertions.mcfpp", declaration.getContainingFile().getName());
            assertTrue(McfppLibraryRootsProvider.compilerSourceRoots(getProject()).stream()
                    .noneMatch(root -> root.findChild("assertions.mcfpp") != null));
        } finally {
            if (previous == null) System.clearProperty("mcfpp.sources.path");
            else System.setProperty("mcfpp.sources.path", previous);
            FileUtil.delete(compiler.toFile());
        }
    }

    public void testBundledCompilerSourcesIncludeJavaAndKotlinImplementation() {
        VirtualFile root = McfppLibraryRootsProvider.bundledCompilerSourcesRoot();
        assertNotNull("Compiler sources must be available without a local checkout", root);
        assertNotNull(root.findFileByRelativePath("top/mcfpp/mni/MCIntData.java"));
        assertNotNull(root.findFileByRelativePath("top/mcfpp/core/lang/MCInt.kt"));
    }

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
