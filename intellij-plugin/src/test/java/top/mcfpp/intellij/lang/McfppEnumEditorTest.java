package top.mcfpp.intellij.lang;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public final class McfppEnumEditorTest extends BasePlatformTestCase {
    @Override protected void setUp() throws Exception {
        super.setUp();
        var plugin = com.intellij.ide.plugins.PluginManagerCore.getPlugin(com.intellij.openapi.extensions.PluginId.getId("top.mcfpp.language"));
        assertNotNull(plugin);
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(getTestRootDisposable(), plugin.getPluginPath().toString());
    }
    @Override protected com.intellij.testFramework.fixtures.TempDirTestFixture createTempDirTestFixture() {
        return new com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl();
    }
    private static final String SOURCE = "enum Mode { idle, active=1b }\nenum Other { active }\n";

    private Set<String> complete(String expression) {
        myFixture.configureByText(McfppFileType.INSTANCE, SOURCE + "func main(mode: Mode) { " + expression + " }");
        var items = myFixture.completeBasic();
        assertNotNull(items);
        return Arrays.stream(items).map(LookupElement::getLookupString).collect(Collectors.toSet());
    }

    public void testEnumValueAndDeclarationCompletion() {
        for (String receiver : java.util.List.of("mode", "Mode.active")) {
            var names = complete("var p = " + receiver + ".<caret>");
            assertContainsElements(names, "value", "identifier", "data");
            assertFalse(names.contains("idle"));
        }
        var names = complete("var p = Mode.<caret>");
        assertContainsElements(names, "idle", "active");
        assertFalse(names.contains("identifier"));
        assertContainsElements(complete("var inferred = Mode.active\nvar name = inferred.<caret>"), "identifier", "data");
        assertContainsElements(complete("var selected: Mode = <caret>"), "idle", "active");
        assertContainsElements(complete("nbtStorageRef<Mode>(\"demo:data\", \"input\").tryRead { m -> var n = m.<caret> }"),
                "value", "identifier", "data");
    }

    public void testShorthandNavigatesToExpectedEnum() {
        myFixture.configureByText(McfppFileType.INSTANCE, SOURCE + "func main() { var mode: Mode = ac<caret>tive }");
        var reference = myFixture.getFile().findReferenceAt(myFixture.getCaretOffset());
        assertNotNull(reference);
        var target = reference.resolve();
        assertNotNull(target);
        assertEquals(SOURCE.indexOf("active"), target.getTextOffset());
    }
}
