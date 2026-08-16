package top.mcfpp.intellij.lang;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import top.mcfpp.intellij.command.MinecraftCommandTypedHandler;

import java.util.Arrays;
import java.util.List;

public final class MinecraftFunctionIntegrationTest extends BasePlatformTestCase {
    public void testSelectorPunctuationRequestsAutomaticCompletion() {
        myFixture.configureByText(MinecraftFunctionFileType.INSTANCE, "kill @<caret>");

        TypedHandlerDelegate.Result result = new MinecraftCommandTypedHandler().checkAutoPopup(
                '@', getProject(), myFixture.getEditor(), myFixture.getFile());

        assertEquals(TypedHandlerDelegate.Result.STOP, result);
    }

    public void testTargetSelectorsAndSelectorFieldsComeFromDatapackSandbox() {
        myFixture.configureByText(MinecraftFunctionFileType.INSTANCE, "kill @<caret>");

        LookupElement[] targets = myFixture.completeBasic();

        assertNotNull(targets);
        assertTrue(Arrays.stream(targets).anyMatch(item -> item.getLookupString().equals("@a")));
        assertTrue(Arrays.stream(targets).anyMatch(item -> item.getLookupString().equals("@e")));
        LookupElement selector = Arrays.stream(targets)
                .filter(item -> item.getLookupString().equals("@e"))
                .findFirst().orElseThrow();
        LookupElementPresentation presentation = new LookupElementPresentation();
        selector.renderElement(presentation);
        assertEquals("目标选择器", presentation.getTypeText());
        assertNotNull(presentation.getIcon());

        myFixture.configureByText(MinecraftFunctionFileType.INSTANCE, "execute as @e[<caret>");
        LookupElement[] fields = myFixture.completeBasic();

        assertNotNull(fields);
        assertTrue(Arrays.stream(fields).anyMatch(item -> item.getLookupString().equals("type=")));
        assertTrue(Arrays.stream(fields).anyMatch(item -> item.getLookupString().equals("distance=")));
        LookupElement typeField = Arrays.stream(fields)
                .filter(item -> item.getLookupString().equals("type="))
                .findFirst().orElseThrow();
        LookupElementPresentation fieldPresentation = new LookupElementPresentation();
        typeField.renderElement(fieldPresentation);
        assertEquals("选择器参数", fieldPresentation.getTypeText());
    }

    public void testCompletionAppliesDpsReplacementRange() {
        myFixture.configureByText(
                MinecraftFunctionFileType.INSTANCE,
                "execute as @e[type=minecraft:zo<caret>mbie] run say ready"
        );

        LookupElement[] items = myFixture.completeBasic();
        LookupElement zombie = Arrays.stream(items)
                .filter(item -> item.getLookupString().equals("minecraft:zombie"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("minecraft:zombie was not offered"));
        myFixture.getLookup().setCurrentItem(zombie);
        myFixture.finishLookup('\t');

        myFixture.checkResult("execute as @e[type=minecraft:zombie<caret>] run say ready");
    }

    public void testCommandStructureUsesDistinctHighlightKeys() {
        String source = "$execute as @e[type=minecraft:zombie,distance=..8] positioned ~ ~1 ^-2 run give @s minecraft:stone $(item)";
        List<McfppSemanticTokens.EmbeddedRange> highlights = McfppCommandSyntax.highlightMcfunction(source);

        assertHighlight(highlights, source, "execute", McfppSyntaxHighlighter.COMMAND_ROOT);
        assertHighlight(highlights, source, "@e", McfppSyntaxHighlighter.TARGET_SELECTOR);
        assertHighlight(highlights, source, "type", McfppSyntaxHighlighter.COMMAND_SELECTOR_KEY);
        assertHighlight(highlights, source, "~", McfppSyntaxHighlighter.COMMAND_COORDINATE);
        assertHighlight(highlights, source, "minecraft:zombie", McfppSyntaxHighlighter.COMMAND_RESOURCE);
        assertHighlight(highlights, source, "minecraft:stone", McfppSyntaxHighlighter.COMMAND_RESOURCE);
        assertHighlight(highlights, source, "item", McfppSyntaxHighlighter.VARIABLE);
    }

    public void testStaticSyntaxErrorIsReported() {
        myFixture.configureByText(MinecraftFunctionFileType.INSTANCE, "scoreboard players set");

        List<HighlightInfo> highlights = myFixture.doHighlighting();

        assertTrue(highlights.stream().anyMatch(info -> info.getDescription() != null &&
                info.getDescription().contains("scoreboard players set <target> <objective> <value>")));
    }

    private static void assertHighlight(
            List<McfppSemanticTokens.EmbeddedRange> highlights,
            String source,
            String expectedText,
            TextAttributesKey expectedKey
    ) {
        assertTrue("No " + expectedKey.getExternalName() + " highlight for " + expectedText,
                highlights.stream().anyMatch(info ->
                        source.substring(info.startOffset(), info.endOffset()).equals(expectedText) &&
                        expectedKey.equals(info.attributesKey())));
    }
}
