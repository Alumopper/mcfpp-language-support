package top.mcfpp.intellij.mni;

import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

/** Use the test JVM's JDK; these PSI fixtures do not require downloading annotation libraries. */
public abstract class McfppJavaFixtureTestCase extends LightJavaCodeInsightFixtureTestCase {
    private static final LightProjectDescriptor DESCRIPTOR = new DefaultLightProjectDescriptor(
            () -> JavaSdk.getInstance().createJdk("MCFPP test JDK", System.getProperty("java.home"), false));
    @Override protected LightProjectDescriptor getProjectDescriptor() { return DESCRIPTOR; }

    @Override protected void setUp() throws Exception {
        super.setUp();
        for(var root:McfppLibraryRootsProvider.mcfppSourceRoots(getProject())) {
            if(root.getFileSystem() instanceof com.intellij.openapi.vfs.JarFileSystem) {
                var archive=com.intellij.openapi.vfs.JarFileSystem.getInstance().getVirtualFileForJar(root);
                com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(getTestRootDisposable(),archive.getPath());
            }
        }
    }
}
