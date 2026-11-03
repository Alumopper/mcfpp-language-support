package top.mcfpp.intellij.lang;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import top.mcfpp.intellij.mni.McfppLibraryRootsProvider;

public final class McfppAnnotationNavigationTest extends BasePlatformTestCase {
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        var plugin = com.intellij.ide.plugins.PluginManagerCore.getPlugin(
                com.intellij.openapi.extensions.PluginId.getId("top.mcfpp.language"));
        assertNotNull(plugin);
        VfsRootAccess.allowRootAccess(getTestRootDisposable(), plugin.getPluginPath().toString());
    }

    public void testTestAnnotationsNavigateToTheirImplementations() {
        for (String name : java.util.List.of("Test", "BeforeEach", "AfterEach", "Disabled")) {
            assertNavigation(name, "TestAnnotations.kt");
        }
    }

    public void testCompilerAnnotationsNavigateToTheirImplementations() {
        assertNavigation("From", "From.java");
        assertNavigation("Name", "Name.java");
        assertNavigation("Load", "Load.java");
    }

    private void assertNavigation(String name, String fileName) {
        var bundled = McfppLibraryRootsProvider.bundledCompilerSourcesRoot();
        assertNotNull(bundled);
        VfsRootAccess.allowRootAccess(getTestRootDisposable(), bundled.getPath().split("!/")[0]);
        myFixture.configureByText(McfppFileType.INSTANCE, "@" + name.charAt(0) + "<caret>" + name.substring(1) + "(\"value\")\nfunc test(){}\n");
        var reference = myFixture.getFile().findReferenceAt(myFixture.getCaretOffset());
        assertNotNull("No annotation reference for " + name, reference);
        var target = reference.resolve();
        assertNotNull("No annotation declaration for " + name, target);
        assertEquals(fileName, target.getContainingFile().getName());
        assertEquals(name, target.getText());
        myFixture.performEditorAction(com.intellij.openapi.actionSystem.IdeActions.ACTION_GOTO_DECLARATION);
        assertEquals(fileName, com.intellij.openapi.fileEditor.FileEditorManager.getInstance(getProject())
                .getSelectedFiles()[0].getName());
    }

    public void testLambdaVariableCallAndFunctionReferenceResolve() {
        myFixture.configureByText(McfppFileType.INSTANCE, """
                func foooo(value: int): int { return value; }
                func main() {
                    var f = { a: int -> 1 }
                    print(<caret>f(1))
                    f = foooo
                }
                """);
        var reference = myFixture.getFile().findReferenceAt(myFixture.getCaretOffset());
        assertNotNull(reference);
        var declaration = reference.resolve();
        assertNotNull(declaration);
        assertEquals("f", ((McfppNamedElement) declaration).getName());
        var source = myFixture.getFile().getText();
        var assigned = myFixture.getFile().findReferenceAt(source.lastIndexOf("foooo") + 2);
        assertNotNull(assigned);
        assertNotNull(assigned.resolve());
        assertEquals("foooo", ((McfppNamedElement) assigned.resolve()).getName());
    }

    public void testLambdaDocumentationIncludesTheInitializer() {
        myFixture.configureByText(McfppFileType.INSTANCE, """
                func main() {
                    var f = { a: int -> 1 }
                    print(<caret>f(1))
                }
                """);
        var targets = new McfppDocumentationTargetProvider().documentationTargets(
                myFixture.getFile(), myFixture.getCaretOffset());
        assertEquals(1, targets.size());
        assertEquals("<code>var f = { a: int -&gt; 1 }</code>", targets.getFirst().computeDocumentationHint());
    }
}
