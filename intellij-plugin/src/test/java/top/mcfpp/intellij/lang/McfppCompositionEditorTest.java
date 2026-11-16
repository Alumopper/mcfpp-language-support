package top.mcfpp.intellij.lang;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public final class McfppCompositionEditorTest extends BasePlatformTestCase {
    @Override protected void setUp() throws Exception {
        super.setUp();
        var plugin = com.intellij.ide.plugins.PluginManagerCore.getPlugin(com.intellij.openapi.extensions.PluginId.getId("top.mcfpp.language"));
        assertNotNull(plugin);
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(getTestRootDisposable(), plugin.getPluginPath().toString());
    }
    private Set<String> complete(String source) {
        myFixture.configureByText(McfppFileType.INSTANCE, source);
        var items = myFixture.completeBasic();
        assertNotNull(items);
        return Arrays.stream(items).map(LookupElement::getLookupString).collect(Collectors.toSet());
    }
    public void testFactoriesOwnParametersAndReturnFields() {
        String source = "data Point { var x: int; var y: int; new(x: int, y: int) { return { <caret> } } }";
        assertEquals(Set.of("x", "y"), complete(source));
        var model = McfppFileModels.get(myFixture.getFile());
        var factory = model.symbols().stream().filter(it -> it.kind() == McfppSymbolKind.CONSTRUCTOR).findFirst().orElseThrow();
        assertEquals("new", factory.name());
        assertEquals("Point", factory.owner());
        assertEquals(2, McfppCallableInfo.from(factory).parameters().size());
    }
    public void testFactoryMemberCompletionAndNavigation() {
        String declaration = "data Point { var x: int; new(x: int) { return { x } } }\n";
        assertContainsElements(complete(declaration + "func main() { Point.<caret> }"), "new");
        myFixture.configureByText(McfppFileType.INSTANCE, declaration + "func main() { Point.ne<caret>w(1) }");
        var reference = myFixture.getFile().findReferenceAt(myFixture.getCaretOffset());
        assertNotNull(reference);
        assertNotNull(reference.resolve());
    }
    public void testIntersectionUnionAndObjectMembers() {
        String declarations = "data A { var common: int; var left: int }\ndata B { var common: int; var right: string }\n";
        assertEquals(Set.of("common", "left", "right"), complete(declarations + "func main(v: (A & B)) { v.<caret> }"));
        assertEquals(Set.of("common"), complete(declarations + "func main(v: (A | B)) { v.<caret> }"));
        assertEquals(Set.of("x", "y"), complete("func main(v: { x: int, y: string }) { v.<caret> }"));
    }
    public void testSafeAccessAndAssertionKeepReceiver() {
        String declarations = "data Child { var value: int; var name: string }\ndata Holder { var child: Child? }\n";
        assertEquals(Set.of("value", "name"), complete(declarations + "func main(h: Holder) { h.child?.<caret> }"));
        assertEquals(Set.of("value", "name"), complete(declarations + "func main(h: Holder) { h.child!!.<caret> }"));
    }
}
