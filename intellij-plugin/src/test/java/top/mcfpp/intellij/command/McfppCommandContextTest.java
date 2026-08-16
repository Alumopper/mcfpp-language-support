package top.mcfpp.intellij.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class McfppCommandContextTest {
    @Test
    void extractsIndentedRawCommandPrefixAndMapsOffsets() {
        String source = "func main() {\n    /execute as @e[type=minecraft:zo\n}";
        int caret = source.indexOf("\n}");

        McfppCommandContext context = McfppCommandContext.at(source, caret);

        assertEquals("execute as @e[type=minecraft:zo", context.buffer());
        assertEquals(source.indexOf("execute"), context.documentBaseOffset());
        assertEquals(source.indexOf("minecraft:zo"), context.documentOffset(19));
    }

    @Test
    void keepsTheCommandSuffixWhenCompletingInTheMiddle() {
        String source = "    /execute as @e[type=minecraft:zombie] run say ready";
        int caret = source.indexOf("zombie") + 2;

        McfppCommandContext context = McfppCommandContext.at(source, caret);

        assertEquals("execute as @e[type=minecraft:zombie] run say ready", context.buffer());
        assertEquals(caret - source.indexOf("execute"), context.cursor());
        assertEquals(caret, context.documentOffset(context.cursor()));
    }

    @Test
    void doesNotTreatDivisionOrInterpolationBodyAsACommandCompletionContext() {
        String division = "func main() { var half = total / 2<caret> }";
        assertNull(McfppCommandContext.at(division, division.indexOf("<caret>")));

        String interpolation = "    /say ${player.na} ready";
        assertNull(McfppCommandContext.at(interpolation, interpolation.indexOf("na") + 2));
    }

    @Test
    void validationCollectsOnlyStaticRawCommands() {
        String source = """
                func main() {
                    /say static
                    /say ${message}
                    var result = left / right
                }
                """;

        var commands = McfppCommandContext.staticCommands(source);

        assertEquals(1, commands.size());
        assertEquals("say static", commands.getFirst().text());
        assertEquals("say static", source.substring(
                commands.getFirst().range().getStartOffset(), commands.getFirst().range().getEndOffset()));
    }
}
