package top.mcfpp.intellij.datapack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** Resolves vanilla datapack JSON resource kinds from both pre-1.21 and current folder names. */
public final class MinecraftDatapackPath {
    private MinecraftDatapackPath() {
    }

    public static @Nullable Kind kindForPath(@NotNull String path) {
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        int lastSlash = normalized.lastIndexOf('/');
        String fileName = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
        if (fileName.equals("pack.mcmeta")) return Kind.PACK;
        if (!fileName.endsWith(".json")) return null;

        String[] segments = normalized.split("/");
        for (int index = segments.length - 3; index >= 0; index--) {
            if (!segments[index].equals("assets") || index + 2 >= segments.length) continue;
            if (index + 2 == segments.length - 1 && segments[index + 2].equals("sounds.json")) return Kind.SOUNDS;
            return switch (segments[index + 2]) {
                case "models" -> Kind.MODEL;
                case "items" -> Kind.ITEM_MODEL;
                case "blockstates" -> Kind.BLOCKSTATE;
                case "font" -> Kind.FONT;
                default -> null;
            };
        }
        for (int index = segments.length - 4; index >= 0; index--) {
            if (!segments[index].equals("data") || index + 3 >= segments.length) continue;
            String folder = segments[index + 2];
            return switch (folder) {
                case "advancement", "advancements" -> Kind.ADVANCEMENT;
                case "recipe", "recipes" -> Kind.RECIPE;
                case "predicate", "predicates" -> Kind.PREDICATE;
                case "loot_table", "loot_tables" -> Kind.LOOT_TABLE;
                case "item_modifier", "item_modifiers" -> Kind.ITEM_MODIFIER;
                case "tags" -> Kind.TAG;
                default -> Kind.REGISTRY;
            };
        }
        return null;
    }

    public enum Kind {
        PACK,
        TAG,
        ADVANCEMENT,
        RECIPE,
        PREDICATE,
        LOOT_TABLE,
        ITEM_MODIFIER,
        REGISTRY, MODEL, ITEM_MODEL, BLOCKSTATE, FONT, SOUNDS
    }
}
