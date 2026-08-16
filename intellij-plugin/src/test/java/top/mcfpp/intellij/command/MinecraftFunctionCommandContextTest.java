package top.mcfpp.intellij.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MinecraftFunctionCommandContextTest {
    @Test
    void mapsIndentedCommandAndCaretToDpsBuffer() {
        String source = "  execute as @e[type=minecraft:zo] run say ready";
        int caret = source.indexOf("zo") + 2;

        MinecraftFunctionCommandContext context = MinecraftFunctionCommandContext.at(source, caret);

        assertEquals("execute as @e[type=minecraft:zo] run say ready", context.buffer());
        assertEquals(caret - 2, context.cursor());
        assertEquals(2, context.documentBaseOffset());
    }

    @Test
    void ignoresCommentsAndMacroPlaceholderBodies() {
        assertNull(MinecraftFunctionCommandContext.at("  # execute as @e", 17));
        String macro = "$say hello $(name)";
        assertNull(MinecraftFunctionCommandContext.at(macro, macro.indexOf("name") + 2));
    }

    @Test
    void extractsOnlyCommandsThatCanBeCheckedStatically() {
        String source = "# comment\nsay ready\n$say $(message)\n  kill @s\n";

        List<MinecraftFunctionCommandContext.StaticCommand> commands =
                MinecraftFunctionCommandContext.staticCommands(source);

        assertEquals(List.of("say ready", "kill @s"), commands.stream().map(
                MinecraftFunctionCommandContext.StaticCommand::text).toList());
    }
}
