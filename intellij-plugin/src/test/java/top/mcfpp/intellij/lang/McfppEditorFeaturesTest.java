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

import java.util.Arrays;
import java.util.List;

public final class McfppEditorFeaturesTest extends BasePlatformTestCase {
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

        assertTrue(myFixture.getFile().getText().contains("func missing(arg1 as int, arg2 as string)"));
    }

    public void testCreateFunctionIntentionPreservesReadOnlyArgumentsAndInfersUsefulTypes() {
        myFixture.configureByText(McfppFileType.INSTANCE, """
                namespace demo;
                func main(player as Player, message as string) {
                    miss<caret>ing<player, 4>(message, true, @a, 2.5f, Player(), [1, 2]);
                }
                """);

        IntentionAction action = myFixture.findSingleIntention("Create MCFPP function 'missing'");
        myFixture.launchAction(action);

        assertTrue(myFixture.getFile().getText().contains("""
                func missing<player as Player, readOnly2 as int>(message as string, arg2 as bool, arg3 as selector, arg4 as float, arg5 as Player, arg6 as list<any>)"""));
    }

    public void testCreateTypeIntentionAddsDataDeclaration() {
        myFixture.configureByText(McfppFileType.INSTANCE, """
                namespace demo;
                func main() { var value as Miss<caret>ing; }
                """);

        IntentionAction action = myFixture.findSingleIntention("Create MCFPP data type 'Missing'");
        myFixture.launchAction(action);

        assertTrue(myFixture.getFile().getText().contains("data Missing {"));
    }

    public void testParameterInfoShowsAndTracksFunctionSignature() {
        myFixture.configureByText(McfppFileType.INSTANCE, """
                func sum(left as int, right as string) -> int { return left; }
                func main() { sum(1, <caret>); }
                """);

        String info = myFixture.getParameterInfoAtCaret();

        assertNotNull(info);
        assertTrue(info, info.contains("func sum(left as int, <b>right as string</b>)"));
        assertTrue(info, info.contains("-&gt; int"));
    }

    public void testTypingPairsBracesQuotesAndIndentsNewLine() {
        myFixture.configureByText(McfppFileType.INSTANCE, "func main() <caret>");

        myFixture.type('{');
        myFixture.checkResult("func main() {<caret>}");
        myFixture.type('\n');
        myFixture.checkResult("func main() {\n    <caret>\n}");
        myFixture.type("var message as string = \"");
        myFixture.checkResult("func main() {\n    var message as string = \"<caret>\"\n}");
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

    public void testStructureViewNestsMembersAndFoldingFindsBodies() {
        myFixture.configureByText(McfppFileType.INSTANCE, """
                namespace demo;
                data Counter {
                    var value as int = 0;
                    func increment(amount as int) -> int {
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
                func convert(value as int) {}
                func convert(value as string) {}
                """);

        List<HighlightInfo> highlights = myFixture.doHighlighting();

        assertEquals(1, highlights.stream()
                .filter(info -> info.getDescription() != null && info.getDescription().contains("Duplicate data type"))
                .count());
    }

    public void testSemanticAnnotatorHighlightsSymbolsAndQuotedMniSegments() {
        String source = """
                import example.models:Counter;
                @From<\"example.mni.Base\">
                data Child { func update() {} }
                func main(value as Child) { var copy as Child = Child(); Child.update(); }
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
                #{Adds one to the supplied value.}#
                func increment(value as int) -> int { return value + 1; }
                func main() { incre<caret>ment(1); }
                """);

        List<? extends DocumentationTarget> targets = new McfppDocumentationTargetProvider()
                .documentationTargets(myFixture.getFile(), myFixture.getCaretOffset());

        assertEquals(1, targets.size());
        DocumentationTarget target = targets.getFirst();
        assertEquals("func increment(value as int) -> int", target.computePresentation().getPresentableText());
        String hint = target.computeDocumentationHint();
        assertNotNull(hint);
        assertTrue(hint.contains("increment(value as int)"));
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
