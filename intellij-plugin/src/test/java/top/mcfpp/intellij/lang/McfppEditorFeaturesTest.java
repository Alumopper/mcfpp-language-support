package top.mcfpp.intellij.lang;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.platform.backend.documentation.DocumentationTarget;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.codeInsight.lookup.Lookup;

import java.util.Arrays;
import java.util.List;

public final class McfppEditorFeaturesTest extends BasePlatformTestCase {
    public void testPublicReferenceCompletionAndMigrationDiagnostic() {
        myFixture.configureByText(McfppFileType.INSTANCE, "func main() { var score: ScoreRef; score.<caret> }");
        var items = myFixture.completeBasic();
        assertNotNull(items);
        var names = Arrays.stream(items).map(item -> item.getLookupString()).toList();
        assertTrue(names.containsAll(List.of("set", "tryRead")));
        myFixture.configureByText(McfppFileType.INSTANCE, "func main() { var saved: NBTRef<int>; saved.<caret> }");
        items = myFixture.completeBasic();
        assertNotNull(items);
        assertTrue(Arrays.stream(items).map(item -> item.getLookupString()).toList().containsAll(List.of("set", "tryRead")));
        myFixture.configureByText(McfppFileType.INSTANCE, "func main() { damage(ref local, 1) }");
        assertTrue(myFixture.doHighlighting().stream().anyMatch(info ->
                info.getDescription() != null && info.getDescription().contains("'ref expression' has been removed")));
    }

    public void testCompletionDoesNotOfferLegacyDeclarationForms() {
        for (String source : List.of("func main() { const v<caret> }", "func main() { external v<caret> }",
                "func main() { var value a<caret> }", "func transform<T a<caret>>() {}")) {
            myFixture.configureByText(McfppFileType.INSTANCE, source);
            var items = myFixture.completeBasic();
            String forbidden = source.contains("const ") || source.contains("external ") ? "var" : "as";
            assertTrue(source + " " + (items == null ? "auto-inserted" : Arrays.stream(items).map(item -> item.getLookupString()).toList()), items == null || Arrays.stream(items).noneMatch(item ->
                    item.getLookupString().equals(forbidden)));
        }
    }

    public void testParameterInfoForCallsInBareConditionsAndReturns() {
        for (String statement : List.of("if check(1, <caret>) {}", "while check(1, <caret>) {}",
                "return check(1, <caret>)")) {
            myFixture.configureByText(McfppFileType.INSTANCE,
                    "func check(value: int, flag: bool): bool { return flag }\nfunc main() { " + statement + " }");
            String info = myFixture.getParameterInfoAtCaret();
            assertNotNull(statement, info);
            assertTrue(info, info.contains("<b>flag: bool</b>"));
        }
    }

    public void testAnnotationTypeCompletionRetainsQualifiedTypeCompletion() {
        myFixture.addFileToProject("models.mcfpp", "namespace models\ndata Counter {}\ndata CountDown {}\nfunc compute() {}");
        myFixture.configureByText(McfppFileType.INSTANCE, "func main() { var value: Cou<caret> }");
        var items = myFixture.completeBasic();
        assertNotNull(items);
        assertTrue(Arrays.stream(items).anyMatch(item -> item.getLookupString().equals("Counter")));
        assertFalse(Arrays.stream(items).anyMatch(item -> item.getLookupString().equals("compute")));
        myFixture.configureByText(McfppFileType.INSTANCE, "func main() { var value: models:Cou<caret> }");
        items = myFixture.completeBasic();
        assertTrue(myFixture.getFile().getText().contains("models:Counter") ||
                items != null && Arrays.stream(items).anyMatch(item -> item.getLookupString().equals("Counter")));
    }

    public void testCompletesVersionDirectiveWithCondition() {
        myFixture.configureByText(McfppFileType.INSTANCE, "#i<caret>");
        var items = myFixture.completeBasic();
        if (items != null) {
            var directive = Arrays.stream(items).filter(item -> item.getLookupString().equals("if"))
                    .findFirst().orElseThrow(() -> new AssertionError("#if was not offered"));
            myFixture.getLookup().setCurrentItem(directive);
            myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
        }
        assertEquals("#if MC >= 26.3", myFixture.getEditor().getDocument().getText());
        myFixture.checkResult("#if MC >= 26.3<caret>");
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        IdeaPluginDescriptor descriptor = PluginManagerCore.getPlugin(PluginId.getId("top.mcfpp.language"));
        if (descriptor != null) {
            VfsRootAccess.allowRootAccess(getTestRootDisposable(), descriptor.getPluginPath().toString());
        }
    }

    public void testCreateFunctionIntentionInfersArgumentCount() {
        myFixture.configureByText(McfppFileType.INSTANCE, """
                namespace demo;
                func main() { miss<caret>ing(1, "value"); }
                """);

        IntentionAction action = myFixture.findSingleIntention("Create MCFPP function 'missing'");
        myFixture.launchAction(action);

        assertTrue(myFixture.getFile().getText().contains("func missing(arg1: int, arg2: string)"));
    }

    public void testCreateFunctionIntentionUsesImplicitReadonlyTypeParameters() {
        myFixture.configureByText(McfppFileType.INSTANCE, "func main() { miss<caret>ing<int>(1) }");
        myFixture.launchAction(myFixture.findSingleIntention("Create MCFPP function 'missing'"));
        assertTrue(myFixture.getFile().getText().contains("func missing<T>(arg1: int)"));
    }

    public void testCreateFunctionIntentionPreservesReadOnlyArgumentsAndInfersUsefulTypes() {
        myFixture.configureByText(McfppFileType.INSTANCE, """
                namespace demo;
                func main(player: Player, message: string) {
                    miss<caret>ing<player, 4>(message, true, @a, 2.5f, Player(), [1, 2]);
                }
                """);

        IntentionAction action = myFixture.findSingleIntention("Create MCFPP function 'missing'");
        myFixture.launchAction(action);

        assertTrue(myFixture.getFile().getText().contains("""
                func missing<player: Player, readOnly2: int>(message: string, arg2: bool, arg3: selector, arg4: float, arg5: Player, arg6: list<any>)"""));
    }

    public void testCreateTypeIntentionAddsDataDeclaration() {
        myFixture.configureByText(McfppFileType.INSTANCE, """
                namespace demo;
                func main() { var value: Miss<caret>ing; }
                """);

        IntentionAction action = myFixture.findSingleIntention("Create MCFPP data type 'Missing'");
        myFixture.launchAction(action);

        assertTrue(myFixture.getFile().getText().contains("data Missing {"));
    }

    public void testParameterInfoShowsAndTracksFunctionSignature() {
        myFixture.configureByText(McfppFileType.INSTANCE, """
                func sum(left: int, right: string) : int { return left; }
                func main() { sum(1, <caret>); }
                """);

        String info = myFixture.getParameterInfoAtCaret();

        assertNotNull(info);
        assertTrue(info, info.contains("func sum(left: int, <b>right: string</b>)"));
        assertTrue(info, info.contains(": int"));
    }

    public void testTypingPairsBracesQuotesAndIndentsNewLine() {
        myFixture.configureByText(McfppFileType.INSTANCE, "func main() <caret>");

        myFixture.type('{');
        myFixture.checkResult("func main() {<caret>}");
        myFixture.type('\n');
        myFixture.checkResult("func main() {\n    <caret>\n}");
        myFixture.type("var message: string = \"");
        myFixture.checkResult("func main() {\n    var message: string = \"<caret>\"\n}");
    }

    public void testTypingPairsParenthesesBracketsAndIndentsContinuation() {
        myFixture.configureByText(McfppFileType.INSTANCE, "func calculate<caret>");

        myFixture.type('(');
        myFixture.checkResult("func calculate(<caret>)");
        myFixture.type('[');
        myFixture.checkResult("func calculate([<caret>])");
        myFixture.type(']');
        myFixture.checkResult("func calculate([]<caret>)");
        myFixture.type('\n');

        myFixture.checkResult("func calculate([]\n        <caret>)");
    }

    public void testEnterBeforeClosingBraceAlignsWithItsOpeningLine() {
        myFixture.configureByText(McfppFileType.INSTANCE, "func main() {\n    print(answer())<caret>}");
        myFixture.type('\n');
        myFixture.checkResult("func main() {\n    print(answer())\n<caret>}");
        myFixture.configureByText(McfppFileType.INSTANCE, "func main() {\n    if true {\n        print(\"}\")<caret>}\n}");
        myFixture.type('\n');
        myFixture.checkResult("func main() {\n    if true {\n        print(\"}\")\n    <caret>}\n}");
    }

    public void testStructureViewNestsMembersAndFoldingFindsBodies() {
        myFixture.configureByText(McfppFileType.INSTANCE, """
                namespace demo;
                data Counter {
                    var value: int = 0;
                    func increment(amount: int) : int {
                        return value + amount;
                    }
                }
                func main() {
                    Counter.increment(1);
                }
                """);
        McfppFile file = (McfppFile) myFixture.getFile();
        StructureViewTreeElement root = new McfppStructureViewModel(myFixture.getEditor(), file).getRoot();
        TreeElement[] topLevel = root.getChildren();

        assertEquals(2, topLevel.length);
        TreeElement counter = Arrays.stream(topLevel)
                .filter(element -> ((StructureViewTreeElement) element).getPresentation().getPresentableText().contains("Counter"))
                .findFirst()
                .orElseThrow();
        List<String> memberPresentations = Arrays.stream(counter.getChildren())
                .map(element -> ((StructureViewTreeElement) element).getPresentation().getPresentableText())
                .toList();
        assertTrue(memberPresentations.stream().anyMatch(text -> text.contains("value")));
        assertTrue(memberPresentations.stream().anyMatch(text -> text.contains("increment")));

        FoldingDescriptor[] descriptors = new McfppFoldingBuilder().buildFoldRegions(
                file,
                myFixture.getEditor().getDocument(),
                false
        );
        assertEquals(3, descriptors.length);
    }

    public void testDuplicateTypeIsReportedButOverloadedFunctionsAreAllowed() {
        myFixture.enableInspections(McfppDuplicateDeclarationInspection.class);
        myFixture.configureByText(McfppFileType.INSTANCE, """
                data Duplicate {}
                data Duplicate {}
                func convert(value: int) {}
                func convert(value: string) {}
                """);

        List<HighlightInfo> highlights = myFixture.doHighlighting();

        assertEquals(1, highlights.stream()
                .filter(info -> info.getDescription() != null && info.getDescription().contains("Duplicate data type"))
                .count());
    }

    public void testSemanticAnnotatorHighlightsSymbolsAndQuotedMniSegments() {
        String source = """
                import example.models:Counter;
                @From(\"example.mni.Base\")
                data Child { func update() {} }
                func main(value: Child) { var copy: Child = Child(); Child.update(); }
                """;
        myFixture.configureByText(McfppFileType.INSTANCE, source);

        List<HighlightInfo> highlights = myFixture.doHighlighting();

        assertSemanticHighlight(highlights, source.indexOf("Child {"), McfppSyntaxHighlighter.TYPE_NAME);
        assertSemanticHighlight(highlights, source.indexOf("value"), McfppSyntaxHighlighter.PARAMETER);
        assertSemanticHighlight(highlights, source.indexOf("copy"), McfppSyntaxHighlighter.VARIABLE);
        assertSemanticHighlight(highlights, source.lastIndexOf("Child()"), McfppSyntaxHighlighter.TYPE_NAME);
        assertSemanticHighlight(highlights, source.indexOf("example"), McfppSyntaxHighlighter.NAMESPACE);
        assertSemanticHighlight(highlights, source.indexOf("Base"), McfppSyntaxHighlighter.TYPE_NAME);
        assertSemanticHighlight(highlights, source.indexOf("Counter"), McfppSyntaxHighlighter.TYPE_NAME);
        assertSemanticHighlight(highlights, source.lastIndexOf("Child.update"), McfppSyntaxHighlighter.TYPE_NAME);
        assertSemanticHighlight(highlights, source.lastIndexOf("update"), McfppSyntaxHighlighter.FUNCTION_CALL);
    }

    public void testQuickDocumentationUsesSignatureAndDocComment() {
        myFixture.configureByText(McfppFileType.INSTANCE, """
                namespace demo;
                /// Adds one to the supplied value.
                func increment(value: int) : int { return value + 1; }
                func main() { incre<caret>ment(1); }
                """);

        List<? extends DocumentationTarget> targets = new McfppDocumentationTargetProvider()
                .documentationTargets(myFixture.getFile(), myFixture.getCaretOffset());

        assertEquals(1, targets.size());
        DocumentationTarget target = targets.getFirst();
        assertEquals("func increment(value: int) : int", target.computePresentation().getPresentableText());
        String hint = target.computeDocumentationHint();
        assertNotNull(hint);
        assertTrue(hint.contains("increment(value: int)"));
        assertNotNull(target.computeDocumentation());
    }

    private static void assertSemanticHighlight(
            List<HighlightInfo> highlights,
            int offset,
            com.intellij.openapi.editor.colors.TextAttributesKey key
    ) {
        assertTrue("No " + key.getExternalName() + " semantic highlight at offset " + offset,
                highlights.stream().anyMatch(info ->
                        info.startOffset == offset && info.forcedTextAttributesKey == key));
    }
}
