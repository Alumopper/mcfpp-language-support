package top.mcfpp.intellij.command;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import top.mcfpp.intellij.lang.McfppFileType;

import java.util.Arrays;
import java.util.List;

public final class McfppCommandIntegrationTest extends BasePlatformTestCase {
    public void testInactiveBranchesDoNotProduceRawCommandDiagnostics() {
        myFixture.configureByText(McfppFileType.INSTANCE, """
                func main() {
                #if MC >= 26.3
                    /compute default float
                #else
                    /say legacy
                #endif
                }
                """);
        var input = new McfppCommandExternalAnnotator().collectInformation(myFixture.getFile());
        assertNotNull(input);
        assertEquals(1, input.commands().size());
        assertEquals("say legacy", input.commands().getFirst().text());
    }

    public void testMinecraft263ProvidesNativeFloatCommandCompletionAndValidation() {
        String previous = System.getProperty("mcfpp.minecraft.version");
        System.setProperty("mcfpp.minecraft.version", "26.3");
        McfppCommandService service = new McfppCommandService(getProject());
        try {
            assertTrue("The bundled command service must support the compiler's 26.3 target", service.warmUp());
            assertTrue(service.complete("comp", 4).suggestions().stream()
                    .anyMatch(suggestion -> suggestion.value().equals("compute")));
            assertFalse(service.check("data modify storage demo:test result set compute default float " +
                    "{type:\"minecraft:add\",inputs:[1.25,2.5]}").syntaxError());
            assertTrue(service.check("compute default float").syntaxError());
        } finally {
            service.dispose();
            if (previous == null) System.clearProperty("mcfpp.minecraft.version");
            else System.setProperty("mcfpp.minecraft.version", previous);
        }
    }

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
