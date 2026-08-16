package top.mcfpp.intellij.command;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import top.mcfpp.intellij.lang.McfppFileType;

import java.util.Arrays;
import java.util.List;

public final class McfppCommandIntegrationTest extends BasePlatformTestCase {
    public void testBundledServiceProvidesStructuredMinecraftCompletionsAndChecks() {
        McfppCommandService service = McfppCommandService.getInstance(getProject());

        assertTrue("The bundled Datapack Sandbox service did not start with Java 25", service.warmUp());
        String source = "execute as @e[type=minecraft:zo";
        McfppCommandService.CompletionResult completion = service.complete(source, source.length());

        McfppCommandService.CompletionSuggestion zombie = completion.suggestions().stream()
                .filter(suggestion -> suggestion.value().equals("minecraft:zombie"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No minecraft:zombie completion: " + completion.suggestions()));
        assertEquals(source.indexOf("minecraft:zo"), zombie.start());
        assertEquals(source.length(), zombie.end());
        assertTrue(service.check("scoreboard players set").syntaxError());
        assertFalse(service.check("execute as @e[type=minecraft:zombie] run say ready").syntaxError());
    }

    public void testIdeaCompletionAppliesDatapackSandboxReplacementRange() {
        myFixture.configureByText(
                McfppFileType.INSTANCE,
                "func main() {\n    /execute as @e[type=minecraft:zo<caret>\n}"
        );

        LookupElement[] items = myFixture.completeBasic();
        assertNotNull(items);
        LookupElement zombie = Arrays.stream(items)
                .filter(item -> item.getLookupString().equals("minecraft:zombie"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("minecraft:zombie was not offered"));
        myFixture.getLookup().setCurrentItem(zombie);
        myFixture.finishLookup('\t');

        myFixture.checkResult("func main() {\n    /execute as @e[type=minecraft:zombie<caret>\n}");
    }

    public void testIdeaCompletionReplacesTheWholeTokenWhenCaretIsInTheMiddle() {
        myFixture.configureByText(
                McfppFileType.INSTANCE,
                "func main() {\n    /execute as @e[type=minecraft:zo<caret>mbie] run say ready\n}"
        );

        LookupElement[] items = myFixture.completeBasic();
        assertNotNull(items);
        LookupElement zombie = Arrays.stream(items)
                .filter(item -> item.getLookupString().equals("minecraft:zombie"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("minecraft:zombie was not offered"));
        myFixture.getLookup().setCurrentItem(zombie);
        myFixture.finishLookup('\t');

        myFixture.checkResult(
                "func main() {\n    /execute as @e[type=minecraft:zombie<caret>] run say ready\n}"
        );
    }

    public void testStaticRawCommandSyntaxErrorIsReportedInEditor() {
        myFixture.configureByText(McfppFileType.INSTANCE, "func main() {\n    /scoreboard players set\n}");

        List<HighlightInfo> highlights = myFixture.doHighlighting();

        assertTrue(highlights.stream().anyMatch(info -> info.getDescription() != null &&
                info.getDescription().contains("scoreboard players set <target> <objective> <value>")));
    }
}
