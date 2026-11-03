package top.mcfpp.intellij.project;

import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.LibraryOrderEntry;
import com.intellij.openapi.roots.DependencyScope;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.IndexingTestUtil;
import top.mcfpp.intellij.tooling.McfppToolchain;
import top.mcfpp.intellij.mni.McfppLibraryRootsProvider;
import top.mcfpp.intellij.mni.McfppJavaFixtureTestCase;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class McfppCompilerSourcesIntegrationTest extends McfppJavaFixtureTestCase {
    public void testMniCompilerLibraryNavigatesToAttachedImplementation() throws Exception {
        Path compiler = McfppToolchain.compiler(getProject());
        VfsRootAccess.allowRootAccess(getTestRootDisposable(), compiler.toString());
        assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(compiler));
        VirtualFile source = McfppLibraryRootsProvider.bundledCompilerSourcesRoot();
        assertNotNull(source);
        VfsRootAccess.allowRootAccess(getTestRootDisposable(), source.getPath());
        var addLibrary = McfppProjectService.class.getDeclaredMethod("addLibrary", ModifiableRootModel.class,
            String.class, DependencyScope.class, Set.class, Path.class, List.class);
        addLibrary.setAccessible(true);
        WriteAction.run(() -> {
            var roots = ModuleRootManager.getInstance(getModule()).getModifiableModel();
            try {
                addLibrary.invoke(null, roots, compiler.toString(), DependencyScope.COMPILE,
                    new HashSet<String>(), compiler, List.of(source));
                roots.commit();
            } finally { if (!roots.isDisposed()) roots.dispose(); }
        });

        boolean attached = Arrays.stream(ModuleManager.getInstance(getProject()).getModules())
            .flatMap(module -> Arrays.stream(ModuleRootManager.getInstance(module).getOrderEntries()))
            .filter(entry -> entry instanceof LibraryOrderEntry)
            .map(entry -> ((LibraryOrderEntry) entry).getLibrary())
            .anyMatch(library -> library != null && library.getFiles(OrderRootType.SOURCES).length > 0);
        assertTrue("The compiler dependency needs source roots", attached);
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        var implementation = JavaPsiFacade.getInstance(getProject())
            .findClass("top.mcfpp.mni.MCIntData", GlobalSearchScope.allScope(getProject()));
        assertNotNull(implementation);
        var navigation = implementation.getNavigationElement();
        assertEquals("MCIntData.java", navigation.getContainingFile().getName());
        assertTrue(navigation.getContainingFile().getText().contains("class MCIntData"));
    }
}
