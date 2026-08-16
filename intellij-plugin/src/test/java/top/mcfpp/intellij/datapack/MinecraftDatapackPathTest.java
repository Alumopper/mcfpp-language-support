package top.mcfpp.intellij.datapack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MinecraftDatapackPathTest {
    @Test
    void recognizesModernAndLegacyDatapackFolders() {
        assertEquals(MinecraftDatapackPath.Kind.RECIPE,
                MinecraftDatapackPath.kindForPath("C:/pack/data/demo/recipe/tools/hammer.json"));
        assertEquals(MinecraftDatapackPath.Kind.RECIPE,
                MinecraftDatapackPath.kindForPath("C:/pack/data/demo/recipes/hammer.json"));
        assertEquals(MinecraftDatapackPath.Kind.LOOT_TABLE,
                MinecraftDatapackPath.kindForPath("C:/pack/data/demo/loot_table/chests/reward.json"));
        assertEquals(MinecraftDatapackPath.Kind.TAG,
                MinecraftDatapackPath.kindForPath("C:/pack/data/demo/tags/function/load.json"));
        assertEquals(MinecraftDatapackPath.Kind.REGISTRY,
                MinecraftDatapackPath.kindForPath("C:/pack/data/demo/damage_type/magic.json"));
    }

    @Test
    void recognizesPackMetadataButRejectsUnrelatedJson() {
        assertEquals(MinecraftDatapackPath.Kind.PACK,
                MinecraftDatapackPath.kindForPath("C:\\pack\\pack.mcmeta"));
        assertNull(MinecraftDatapackPath.kindForPath("C:/project/package.json"));
        assertNull(MinecraftDatapackPath.kindForPath("C:/pack/data/demo/function/start.mcfunction"));
    }
}
