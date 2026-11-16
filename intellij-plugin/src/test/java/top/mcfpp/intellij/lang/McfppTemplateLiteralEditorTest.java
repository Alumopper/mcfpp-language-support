package top.mcfpp.intellij.lang;

import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public final class McfppTemplateLiteralEditorTest extends BasePlatformTestCase {
    @Override protected void setUp() throws Exception {
        super.setUp();
        var plugin = com.intellij.ide.plugins.PluginManagerCore.getPlugin(com.intellij.openapi.extensions.PluginId.getId("top.mcfpp.language"));
        assertNotNull(plugin);
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(getTestRootDisposable(), plugin.getPluginPath().toString());
    }
    private static final String DECLARATIONS = """
            data Base {
                /// Stable identifier.
                var id: int
                var tag: int = 5
            }
            data Inner: Base { var label: string = "ok" }
            data Box<T> { var value: T }
            data Outer {
                var inner: Inner
                var items: list<Inner> = []
                private var secret: int = 2
            }
            func take(value: Outer) {}
            func generic<T>(value: Box<T>) {}
            """;

    private Set<String> complete(String expression) {
        myFixture.configureByText(McfppFileType.INSTANCE, DECLARATIONS + "\nfunc main() { " + expression + " }");
        LookupElement[] variants = myFixture.completeBasic();
        assertNotNull(variants);
        return Arrays.stream(variants).map(LookupElement::getLookupString).collect(Collectors.toSet());
    }

    public void testVariableAndFunctionParameterKeysUseOnlyTheirTemplateFields() {
        assertEquals(Set.of("inner", "items"), complete("var h: Outer = { <caret> }"));
        assertEquals(Set.of("inner", "items"), complete("take({ <caret> })"));
        assertEquals(Set.of("inner", "items"), complete("take(value = { <caret> })"));
        assertEquals(Set.of("inner", "items"), complete("var fn: (Outer) -> void = take\nfn({ <caret> })"));
        assertEquals(Set.of("id", "tag", "label"), complete("var outer: Outer = { inner: { id: 1 } }\nouter.inner = { <caret> }"));
    }

    public void testNestedListAndGenericFieldsExcludeFieldsAlreadyEntered() {
        assertEquals(Set.of("tag", "label"), complete("var h: Outer = { inner: { id: 1, <caret> } }"));
        assertEquals(Set.of("tag", "label"), complete("var h: Outer = { items: [{ id: 1, <caret> }] }"));
        myFixture.configureByText(McfppFileType.INSTANCE, DECLARATIONS + "\nvar h: Box<int> = { <caret> }");
        var variants = myFixture.completeBasic();
        if (variants != null) {
            assertEquals(Set.of("value"), Arrays.stream(variants).map(LookupElement::getLookupString).collect(Collectors.toSet()));
            myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
        }
        assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().contains("{ value: "));
    }

    public void testChainedTemplateMembersUseTheFinalReceiverType() {
        var fields = complete("var outer: Outer = { inner: { id: 1 } }\nouter.inner.<caret>");
        assertTrue(fields.toString(), fields.containsAll(Set.of("id", "tag", "label")));
        assertFalse(fields.toString(), fields.contains("inner") || fields.contains("items") || fields.contains("secret"));
    }

    public void testImportedGenericMemberChainsRetainTheirFieldTypes() {
        myFixture.addFileToProject("models.mcfpp", """
                namespace models
                data Base { var id: int }
                data Profile: Base { var score: int = 0 }
                data Box<T> { var value: T }
                data Holder { var box: Box<Profile> }
                """);
        myFixture.configureByText(McfppFileType.INSTANCE, """
                import models:Holder as Model
                func main() { var model: Model\nmodel.box.value.<caret> }
                """);
        var variants = myFixture.completeBasic();
        assertNotNull(variants);
        assertEquals(Set.of("id", "score"), Arrays.stream(variants).map(LookupElement::getLookupString).collect(Collectors.toSet()));
    }

    public void testChainedInheritedFieldsNavigateToTheirDocumentedDeclaration() {
        myFixture.configureByText(McfppFileType.INSTANCE, DECLARATIONS + "\nfunc main() { var outer: Outer\nouter.inner.i<caret>d }");
        var reference = myFixture.getFile().findReferenceAt(myFixture.getCaretOffset());
        assertNotNull(reference);
        var target = reference.resolve();
        assertNotNull(target);
        var symbol = McfppFileModels.get(target.getContainingFile()).declarationAt(target.getTextOffset());
        assertEquals("Base", symbol.owner());
        assertEquals("Stable identifier.", symbol.documentation());
    }

    public void testSelectingAFieldInsertsTheColonAndKeepsTheValueCaret() {
        complete("var h: Inner = { <caret> }");
        var lookup = myFixture.getLookup();
        lookup.setCurrentItem(Arrays.stream(lookup.getItems().toArray(LookupElement[]::new))
                .filter(it -> it.getLookupString().equals("id")).findFirst().orElseThrow());
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
        myFixture.checkResult(DECLARATIONS + "\nfunc main() { var h: Inner = { id: <caret> } }");
    }

    public void testEditingAnExistingKeyDoesNotInsertASecondColon() {
        complete("var h: Inner = { <caret>: 1 }");
        var lookup = myFixture.getLookup();
        lookup.setCurrentItem(lookup.getItems().stream().filter(it -> it.getLookupString().equals("id")).findFirst().orElseThrow());
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
        assertTrue(myFixture.getFile().getText().contains("{ id: 1 }"));
    }

    public void testFieldKeysResolveToOneDocumentedDeclaration() {
        myFixture.configureByText(McfppFileType.INSTANCE, DECLARATIONS + "\nfunc main() { take({ inner: { i<caret>d: 3 } }) }");
        var targets = new McfppDocumentationTargetProvider().documentationTargets(myFixture.getFile(), myFixture.getCaretOffset());
        assertEquals(1, targets.size());
        assertEquals("<code>var id: int</code>", targets.getFirst().computeDocumentationHint());
        var documentation = (com.intellij.platform.backend.documentation.DocumentationData) targets.getFirst().computeDocumentation();
        assertNotNull(documentation);
        assertTrue(documentation.getHtml().contains("Stable identifier."));
        var reference = myFixture.getFile().findReferenceAt(myFixture.getCaretOffset());
        assertNotNull(reference);
        var resolved = reference.resolve();
        assertNotNull(resolved);
        var symbol = McfppFileModels.get(resolved.getContainingFile()).declarationAt(resolved.getTextOffset());
        assertEquals("Stable identifier.", symbol.documentation());
    }

    public void testImportedAliasesAndInheritedFieldsRetainDocumentation() {
        myFixture.addFileToProject("models.mcfpp", "namespace models\ndata Imported { /// Imported identifier.\nvar id: int\nvar tag: int = 1 }");
        myFixture.configureByText(McfppFileType.INSTANCE, """
                import models:Imported as Model
                typealias Model as Alias
                func consume(value: Alias) {}
                func main() { consume({ i<caret>d: 1 }) }
                """);
        var targets = new McfppDocumentationTargetProvider().documentationTargets(myFixture.getFile(), myFixture.getCaretOffset());
        assertEquals(1, targets.size());
        assertEquals("<code>var id: int</code>", targets.getFirst().computeDocumentationHint());
    }

    public void testBracesAndCommasInStringsAndCommentsDoNotChangeTheTemplateContext() {
        assertEquals(Set.of("id", "tag"), complete("var h: Inner = { label: \"} , {\", // } ,\n <caret> }"));
    }

    public void testQuotedAndPartiallyTypedKeysKeepValidSyntax() {
        for (String key : java.util.List.of("i<caret>d", "\"i<caret>d\"")) {
            myFixture.configureByText(McfppFileType.INSTANCE, DECLARATIONS + "\nvar h: Inner = { " + key + ": 1 }");
            var items = myFixture.completeBasic();
            if (items != null) {
                myFixture.getLookup().setCurrentItem(myFixture.getLookup().getItems().stream()
                        .filter(it -> it.getLookupString().equals("id")).findFirst().orElseThrow());
                myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
            }
            assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().endsWith("{ id: 1 }"));
        }
    }

    public void testImportedFunctionArgumentsUseTheDeclaringFilesImports() {
        myFixture.addFileToProject("models.mcfpp", "namespace models\ndata Area { var start: int\nvar end: int }");
        myFixture.addFileToProject("commands.mcfpp", "namespace commands\nimport models:Area\nfunc fill(area: Area) {}");
        myFixture.configureByText(McfppFileType.INSTANCE, """
                import commands:*
                func main() {
                    fill({ <caret> })
                }
                """);
        var variants = myFixture.completeBasic();
        assertNotNull(variants);
        assertEquals(Set.of("start", "end"), Arrays.stream(variants).map(LookupElement::getLookupString).collect(Collectors.toSet()));
    }
}
