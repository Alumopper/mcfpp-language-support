package top.mcfpp.intellij.lang;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public final class McfppContextEditorTest extends BasePlatformTestCase {
    @Override protected void setUp() throws Exception {
        super.setUp();
        for (var root : top.mcfpp.intellij.mni.McfppLibraryRootsProvider.mcfppSourceRoots(getProject()))
            if (root.getFileSystem() instanceof com.intellij.openapi.vfs.JarFileSystem)
                com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(getTestRootDisposable(),
                    com.intellij.openapi.vfs.JarFileSystem.getInstance().getVirtualFileForJar(root).getPath());
    }
    private Set<String> complete(String source) {
        myFixture.configureByText(McfppFileType.INSTANCE, source);
        var variants = myFixture.completeBasic();
        assertNotNull(variants);
        return Arrays.stream(variants).map(it -> it.getLookupString()).collect(Collectors.toSet());
    }
    public void testContextTypesAreSuggestedInDependencyHeaders() {
        assertContainsElements(complete("context(space: <caret>) func probe() {}"), "PosContext", "ExecutionContext", "EntityContext", "RelationContext");
    }
    public void testCallbackTypesAndInheritedComponentsComplete() {
        assertContainsElements(complete("""
            func main() {
                @s.withEntityCtx { actor ->
                    @s.withPosCtx { space ->
                        space.<caret>
                    }
                }
            }
            """), "pos", "executor", "rotation", "onPassengers");
        var narrow = complete("context(space: PosContext) func probe() { space.<caret> }");
        assertContainsElements(narrow, "pos", "dimension");
        assertFalse(narrow.toString(), narrow.contains("executor"));
    }
    public void testDependencyParameterAndBuiltinTypeNavigate() {
        myFixture.configureByText(McfppFileType.INSTANCE, "context(space: PosContext) func probe() { var pos = spa<caret>ce.pos }");
        var reference = myFixture.getFile().findReferenceAt(myFixture.getCaretOffset());
        assertNotNull(reference);
        var target = reference.resolve();
        assertNotNull(target);
        assertEquals(8, target.getTextOffset());
        myFixture.configureByText(McfppFileType.INSTANCE, "context(space: Pos<caret>Context) func probe() {}");
        assertNotNull(myFixture.getFile().findReferenceAt(myFixture.getCaretOffset()).resolve());
    }
    public void testContextHeadersDoNotBecomeOrdinaryCallParameters() {
        var model = McfppFileModel.parse("context(space: PosContext) func probe(value: int) { var pos = space.pos }");
        var function = model.symbols().stream().filter(it -> it.name().equals("probe")).findFirst().orElseThrow();
        assertTrue(function.signature(), function.signature().startsWith("context("));
        assertEquals(java.util.List.of("int"), McfppTypes.parameterTypes(function));
        assertEquals("PosContext", McfppTypes.declaredType(model.visibleLocalSymbols("space", function.endOffset() - 1).getFirst()));
    }
    public void testEntryCompletionInsertsAnExplicitCallback() {
        myFixture.configureByText(McfppFileType.INSTANCE, "func main() { var target: entity; target.withEntityC<caret> }");
        myFixture.completeBasic();
        assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().contains("withEntityCtx { ctx ->"));
        assertFalse(myFixture.getFile().getText().contains("withEntityCtx()"));
    }
    public void testNamedContextArgumentResolvesToTheCalleeDependency() {
        myFixture.configureByText(McfppFileType.INSTANCE, """
            context(space: PosContext) func probe() {}
            func main() {
                withCurrentCtx { outer -> probe(spa<caret>ce = outer) }
            }
            """);
        var reference = myFixture.getFile().findReferenceAt(myFixture.getCaretOffset());
        assertNotNull(reference);
        assertNotNull(reference.resolve());
        assertEquals(8, reference.resolve().getTextOffset());
    }
}
