package top.mcfpp.intellij.lang;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.navigation.NavigationItem;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.IndexingTestUtil;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public final class McfppSymbolIntegrationTest extends BasePlatformTestCase {
    public void testResolvesImportedTypeAcrossFiles() {
        myFixture.addFileToProject("src/models.mcfpp", """
                namespace complete.models;
                data Counter { func increment() -> int { return 1; } }
                """);
        myFixture.configureByText(McfppFileType.INSTANCE, """
                namespace complete.app;
                import complete.models:Counter;
                func main() { var value as Cou<caret>nter = Counter(); }
                """);
        DumbService.getInstance(getProject()).waitForSmartMode();

        PsiElement resolved = resolveAtCaret();

        assertInstanceOf(resolved, McfppNamedElement.class);
        assertEquals("Counter", ((McfppNamedElement) resolved).getName());
        assertEquals("models.mcfpp", resolved.getContainingFile().getName());
    }

    public void testResolvesWildcardNamespaceAliasAndMemberFunction() {
        myFixture.addFileToProject("src/math.mcfpp", """
                namespace complete.math;
                func doubled(value as int) -> int { return value * 2; }
                """);
        myFixture.configureByText(McfppFileType.INSTANCE, """
                namespace complete.app;
                import complete.math:* as math;
                func main() { math:doub<caret>led(2); }
                """);
        DumbService.getInstance(getProject()).waitForSmartMode();

        PsiElement resolved = resolveAtCaret();

        assertInstanceOf(resolved, McfppNamedElement.class);
        assertEquals("doubled", ((McfppNamedElement) resolved).getName());
        assertEquals("math.mcfpp", resolved.getContainingFile().getName());
    }

    public void testInfersStandardLibraryNamespaceFromProjectConfiguration() {
        myFixture.addFileToProject("mcfpp.json", """
                {"sourcePath":"src/main/mcfpp","namespace":"mcfpp"}
                """);
        var standardLibraryFile = myFixture.addFileToProject(
                "src/main/mcfpp/minecraft/entity/Player.mcfpp",
                "data PlayerData { score as int; }"
        );
        assertEquals("mcfpp.minecraft.entity", McfppFileModels.get(standardLibraryFile).namespace());
        myFixture.configureByText(McfppFileType.INSTANCE, """
                namespace demo;
                import mcfpp.minecraft.entity:PlayerData;
                func main() { var player as Player<caret>Data = PlayerData(); }
                """);
        DumbService.getInstance(getProject()).waitForSmartMode();

        PsiElement resolved = resolveAtCaret();

        assertEquals("PlayerData", ((McfppNamedElement) resolved).getName());
        assertEquals("Player.mcfpp", resolved.getContainingFile().getName());
    }

    public void testImplicitStandardLibraryFunctionNavigatesWithoutImport() {
        myFixture.addFileToProject("stdlib/mcfpp.json", """
                {"sourcePath":"src/main/mcfpp","namespace":"mcfpp"}
                """);
        myFixture.addFileToProject(
                "stdlib/src/main/mcfpp/lang/sys.mcfpp",
                "func print(int) = top.mcfpp.mni.System.print;"
        );
        myFixture.configureByText(McfppFileType.INSTANCE, """
                namespace demo;
                func main() { pri<caret>nt(1); }
                """);
        DumbService.getInstance(getProject()).waitForSmartMode();
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());

        PsiElement resolved = resolveAtCaret();

        assertEquals("print", ((McfppNamedElement) resolved).getName());
        assertEquals("sys.mcfpp", resolved.getContainingFile().getName());
        assertEquals("mcfpp.lang", McfppFileModels.get(resolved.getContainingFile()).namespace());
        assertNull("Implicit standard-library declarations must not produce redundant imports",
                McfppImportManager.uniqueCandidate((McfppFile) myFixture.getFile(), "print"));
        assertContainsElements(
                com.intellij.util.indexing.FileBasedIndex.getInstance().getAllKeys(McfppSymbolIndex.NAME, getProject()),
                McfppSymbolIndex.namespaceKey("mcfpp.lang", "print")
        );

        myFixture.configureByText(McfppFileType.INSTANCE, "func main() { pri<caret> }");
        LookupElement[] variants = myFixture.completeBasic();
        assertNotNull(variants);
        LookupElement print = Arrays.stream(variants)
                .filter(item -> item.getLookupString().equals("print"))
                .findFirst()
                .orElseThrow();
        myFixture.getLookup().setCurrentItem(print);
        myFixture.finishLookup('\n');
        myFixture.checkResult("func main() { print(<caret>) }");
        assertFalse(myFixture.getFile().getText().contains("import mcfpp.lang:print"));
    }

    public void testPackagedStandardLibraryCompletesAndRealCtrlBNavigatesToSource() {
        myFixture.configureByText(McfppFileType.INSTANCE, """
                namespace demo;
                func main(value as int) { pri<caret>nt(value); }
                """);
        DumbService.getInstance(getProject()).waitForSmartMode();
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());

        PsiElement resolved = resolveAtCaret();
        assertEquals("print", ((McfppNamedElement) resolved).getName());
        assertEquals("sys.mcfpp", resolved.getContainingFile().getName());
        assertTrue(resolved.getContainingFile().getVirtualFile().getPath().contains("/stdlib/src/main/mcfpp/"));

        myFixture.performEditorAction(IdeActions.ACTION_GOTO_DECLARATION);
        assertTrue("The real Ctrl+B action must open the packaged standard-library source",
                Arrays.stream(FileEditorManager.getInstance(getProject()).getSelectedFiles())
                        .anyMatch(file -> "sys.mcfpp".equals(file.getName())));

        myFixture.configureByText(McfppFileType.INSTANCE, "func main() { pri<caret> }");
        LookupElement[] variants = myFixture.completeBasic();
        assertNotNull(variants);
        assertTrue(Arrays.stream(variants).anyMatch(item -> "print".equals(item.getLookupString())));
    }

    public void testResolvesParametersFieldsAndQualifiedMembersLocally() {
        myFixture.configureByText(McfppFileType.INSTANCE, """
                namespace demo;
                data Counter {
                    var value as int = 0;
                    func increment(amount as int) -> int { return value + amount; }
                    func increase() {}
                }
                func main() { Counter.incre<caret>ment(2); }
                """);

        PsiElement resolved = resolveAtCaret();

        assertInstanceOf(resolved, McfppNamedElement.class);
        assertEquals("increment", ((McfppNamedElement) resolved).getName());
    }

    public void testCompletionIncludesLocalAndIndexedProjectSymbols() {
        myFixture.addFileToProject("src/models.mcfpp", """
                namespace complete.models;
                data Counter {}
                data CountDown {}
                """);
        myFixture.configureByText(McfppFileType.INSTANCE, """
                namespace complete.app;
                func main(localValue as int) {
                    var localResult as int = 0;
                    Cou<caret>
                }
                """);
        DumbService.getInstance(getProject()).waitForSmartMode();

        LookupElement[] variants = myFixture.completeBasic();
        assertNotNull(variants);
        Set<String> names = Arrays.stream(variants).map(LookupElement::getLookupString).collect(Collectors.toSet());
        assertContainsElements(names, "Counter", "CountDown");

        myFixture.configureByText(McfppFileType.INSTANCE, """
                func main(localValue as int) {
                    var localResult as int = 0;
                    local<caret>
                }
                """);
        variants = myFixture.completeBasic();
        assertNotNull(variants);
        names = Arrays.stream(variants).map(LookupElement::getLookupString).collect(Collectors.toSet());
        assertContainsElements(names, "localValue", "localResult");
    }

    public void testMemberCompletionUsesDeclaredVariableTypeAndNavigationResolvesIt() {
        myFixture.addFileToProject("src/models.mcfpp", """
                namespace complete.models;
                data Counter {
                    var value as int = 0;
                    func increment(amount as int) -> int { return value + amount; }
                }
                func unrelated() {}
                """);
        myFixture.configureByText(McfppFileType.INSTANCE, """
                namespace complete.app;
                import complete.models:Counter;
                func main() {
                    var counter as Counter = Counter();
                    counter.<caret>
                }
                """);
        DumbService.getInstance(getProject()).waitForSmartMode();

        LookupElement[] variants = myFixture.completeBasic();
        assertNotNull(variants);
        Set<String> names = Arrays.stream(variants).map(LookupElement::getLookupString).collect(Collectors.toSet());
        assertContainsElements(names, "increment");
        assertFalse(names.contains("unrelated"));
        assertFalse(names.contains("return"));

        myFixture.configureByText(McfppFileType.INSTANCE, """
                namespace complete.app;
                import complete.models:Counter;
                func main() {
                    var counter as Counter = Counter();
                    counter.incre<caret>ment(1);
                }
                """);
        PsiElement resolved = resolveAtCaret();
        assertEquals("increment", ((McfppNamedElement) resolved).getName());
        assertEquals("models.mcfpp", resolved.getContainingFile().getName());
    }

    public void testCompletionAutoImportsUniqueProjectType() {
        myFixture.addFileToProject("src/models.mcfpp", """
                namespace complete.models;
                data Counter {}
                data CountDown {}
                """);
        myFixture.configureByText(McfppFileType.INSTANCE, """
                namespace complete.app;
                func main() { var counter as Coun<caret>; }
                """);
        DumbService.getInstance(getProject()).waitForSmartMode();

        LookupElement[] variants = myFixture.completeBasic();
        LookupElement counter = Arrays.stream(variants)
                .filter(item -> item.getLookupString().equals("Counter"))
                .findFirst()
                .orElseThrow();
        myFixture.getLookup().setCurrentItem(counter);
        myFixture.finishLookup('\n');

        assertTrue(myFixture.getFile().getText().contains("import complete.models:Counter;"));
        assertTrue(myFixture.getFile().getText().contains("var counter as Counter;"));
    }

    public void testImportIntentionAddsUniqueProjectSymbol() {
        myFixture.addFileToProject("src/models.mcfpp", """
                namespace complete.models;
                data Counter {}
                """);
        myFixture.configureByText(McfppFileType.INSTANCE, """
                namespace complete.app;
                func main() { var counter as Cou<caret>nter; }
                """);
        DumbService.getInstance(getProject()).waitForSmartMode();

        var action = myFixture.findSingleIntention("Import 'complete.models:Counter'");
        myFixture.launchAction(action);

        assertTrue(myFixture.getFile().getText().contains("import complete.models:Counter;"));
    }

    public void testNamespaceAliasCompletionOnlyOffersNamespaceSymbols() {
        myFixture.addFileToProject("src/math.mcfpp", """
                namespace complete.math;
                func doubled(value as int) -> int { return value * 2; }
                func tripled(value as int) -> int { return value * 3; }
                """);
        myFixture.addFileToProject("src/other.mcfpp", """
                namespace complete.other;
                func unrelated() {}
                """);
        myFixture.configureByText(McfppFileType.INSTANCE, """
                namespace complete.app;
                import complete.math:* as math;
                func main() { math:<caret> }
                """);
        DumbService.getInstance(getProject()).waitForSmartMode();

        LookupElement[] variants = myFixture.completeBasic();
        assertNotNull(variants);
        Set<String> names = Arrays.stream(variants).map(LookupElement::getLookupString).collect(Collectors.toSet());
        assertContainsElements(names, "doubled", "tripled");
        assertFalse(names.contains("unrelated"));
        assertFalse(names.contains("return"));
    }

    public void testFunctionCompletionAddsParenthesesAndPlacesCaretInside() {
        myFixture.configureByText(McfppFileType.INSTANCE, """
                func sum(left as int, right as int) -> int { return left + right; }
                func main() { su<caret> }
                """);

        LookupElement[] variants = myFixture.completeBasic();
        LookupElement sum = Arrays.stream(variants)
                .filter(item -> item.getLookupString().equals("sum"))
                .findFirst()
                .orElseThrow();
        myFixture.getLookup().setCurrentItem(sum);
        myFixture.finishLookup('\n');

        myFixture.checkResult("""
                func sum(left as int, right as int) -> int { return left + right; }
                func main() { sum(<caret>) }
                """);
    }

    public void testNavigateClassContainsOnlyMcfppTypes() {
        myFixture.addFileToProject("src/models.mcfpp", """
                namespace complete.models;
                data Counter {}
                enum Direction { NORTH, SOUTH }
                func createCounter() -> Counter { return Counter(); }
                """);
        myFixture.configureByText(McfppFileType.INSTANCE, "namespace complete.app; Coun<caret>");
        DumbService.getInstance(getProject()).waitForSmartMode();
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        myFixture.completeBasic();

        Set<String> indexedKeys = Set.copyOf(com.intellij.util.indexing.FileBasedIndex.getInstance()
                .getAllKeys(McfppSymbolIndex.NAME, getProject()));
        assertContainsElements(indexedKeys, "Counter", "Direction", "createCounter");
        McfppGoToTypeContributor types = new McfppGoToTypeContributor();
        Set<String> names = Set.of(types.getNames(getProject(), false));
        assertContainsElements(names, "Counter", "Direction");
        assertFalse(names.contains("createCounter"));

        NavigationItem[] items = types.getItemsByName("Counter", "Counter", getProject(), false);
        assertEquals(1, items.length);
        assertEquals("Counter", items[0].getName());

        Set<String> allSymbols = Set.of(new McfppGoToSymbolContributor().getNames(getProject(), false));
        assertContainsElements(allSymbols, "Counter", "Direction", "createCounter");
        assertFalse(allSymbols.stream().anyMatch(McfppSymbolIndex::isInternalKey));
    }

    public void testCrossFileRenameUpdatesDeclarationAndUsage() {
        var usageFile = myFixture.addFileToProject("src/main.mcfpp", """
                namespace complete.app;
                import complete.models:Counter;
                func main() { var value as Counter = Counter(); }
                """);
        var usageVirtualFile = usageFile.getVirtualFile();
        myFixture.configureByText(McfppFileType.INSTANCE, """
                namespace complete.models;
                data Cou<caret>nter {}
                """);
        DumbService.getInstance(getProject()).waitForSmartMode();

        PsiElement declarationLeaf = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
        McfppNamedElement declaration = PsiTreeUtil.getParentOfType(
                declarationLeaf, McfppNamedElement.class, false);
        assertNotNull(declaration);
        var currentUsageFile = PsiManager.getInstance(getProject()).findFile(usageVirtualFile);
        assertNotNull(currentUsageFile);
        int searchOffset = 0;
        int directlyResolved = 0;
        while ((searchOffset = currentUsageFile.getText().indexOf("Counter", searchOffset)) >= 0) {
            PsiElement usage = currentUsageFile.findElementAt(searchOffset);
            assertNotNull(usage);
            PsiReference[] references = usage.getReferences();
            assertEquals(1, references.length);
            assertTrue(references[0].isReferenceTo(declaration));
            directlyResolved++;
            searchOffset += "Counter".length();
        }
        assertEquals(3, directlyResolved);
        assertEquals(3, ReferencesSearch.search(declaration).findAll().size());

        myFixture.renameElementAtCaret("ScoreCounter");

        var updatedUsageFile = PsiManager.getInstance(getProject()).findFile(usageVirtualFile);
        assertNotNull(updatedUsageFile);
        assertTrue(myFixture.getFile().getText().contains("data ScoreCounter"));
        assertTrue(updatedUsageFile.getText().contains("import complete.models:ScoreCounter"));
        assertTrue(updatedUsageFile.getText().contains("as ScoreCounter = ScoreCounter()"));
    }

    public void testParameterRenameIsLimitedToItsFunction() {
        myFixture.configureByText(McfppFileType.INSTANCE, """
                func first(val<caret>ue as int) -> int { return value; }
                func second(value as int) -> int { return value; }
                """);

        myFixture.renameElementAtCaret("amount");

        assertEquals("""
                func first(amount as int) -> int { return amount; }
                func second(value as int) -> int { return value; }
                """, myFixture.getFile().getText());
    }

    public void testReadOnlyParametersAndForeachVariablesResolveCompleteAndRenameByScope() {
        myFixture.configureByText(McfppFileType.INSTANCE, """
                func visit<player as Player>(values as list<Player>) {
                    for (entry : values) {
                        player.notify(entry);
                        <caret>
                    }
                }
                """);

        LookupElement[] variants = myFixture.completeBasic();
        assertNotNull(variants);
        Set<String> names = Arrays.stream(variants)
                .map(LookupElement::getLookupString)
                .collect(Collectors.toSet());
        assertContainsElements(names, "entry", "player", "values");

        myFixture.configureByText(McfppFileType.INSTANCE, """
                func visit<pla<caret>yer as Player>(values as list<Player>) {
                    for (entry : values) { player.notify(entry); }
                }
                """);
        myFixture.renameElementAtCaret("recipient");
        assertTrue(myFixture.getFile().getText().contains("<recipient as Player>"));
        assertTrue(myFixture.getFile().getText().contains("recipient.notify(entry)"));

        myFixture.configureByText(McfppFileType.INSTANCE, """
                func visit(values as list<Player>) {
                    for (ent<caret>ry : values) { notify(entry); }
                    notify(entry);
                }
                """);
        myFixture.renameElementAtCaret("member");
        assertEquals("""
                func visit(values as list<Player>) {
                    for (member : values) { notify(member); }
                    notify(entry);
                }
                """, myFixture.getFile().getText());
    }

    private PsiElement resolveAtCaret() {
        PsiReference reference = myFixture.getReferenceAtCaretPosition();
        assertNotNull("No MCFPP reference at caret", reference);
        PsiElement resolved = reference.resolve();
        String candidates = reference instanceof PsiPolyVariantReference polyVariant
                ? Arrays.toString(polyVariant.multiResolve(false))
                : "not a poly-variant reference";
        assertNotNull("MCFPP reference did not resolve; candidates: " + candidates, resolved);
        return resolved;
    }
}
