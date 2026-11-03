package top.mcfpp.intellij.run;

import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import top.mcfpp.intellij.lang.McfppFileModels;

public final class McfppSourceTestIntegrationTest extends BasePlatformTestCase {
    @Override protected com.intellij.testFramework.fixtures.TempDirTestFixture createTempDirTestFixture() {
        return new com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl();
    }

    public void testGutterFindsAnnotatedTestAndInfersTestNamespace() {
        myFixture.addFileToProject("mcfpp.toml","schema = 1\nname = \"tests\"\nnamespace = \"example\"\n");
        String source="// @Test in a comment is not a test\nfunc helper() {}\n@Test\nfunc addition() {}\n";
        var file=myFixture.addFileToProject("src/test/mcfpp/numbers/math_test.mcfpp",source);
        var marker=new McfppTestLineMarker();
        assertNull(marker.getInfo(file.findElementAt(source.indexOf("helper"))));
        assertNotNull(marker.getInfo(file.findElementAt(source.indexOf("addition"))));
        assertEquals("example.numbers",McfppFileModels.get(file).namespace());
    }

    public void testResultLocationsResolveOriginalSourceWithEncodedPaths() {
        var file=myFixture.addFileToProject("src/test/mcfpp/with space/算术.mcfpp","@Test\nfunc addition() {}\n");
        String path=file.getVirtualFile().toNioPath().toUri().getRawPath()+":2";
        var locations=new McfppTestLocator().getLocation("mcfpp",path,getProject(),GlobalSearchScope.projectScope(getProject()));
        assertEquals(1,locations.size());
        assertEquals(file,locations.getFirst().getPsiElement().getContainingFile());
        assertTrue(new McfppTestLocator().getLocation("other",path,getProject(),GlobalSearchScope.projectScope(getProject())).isEmpty());
    }
}
