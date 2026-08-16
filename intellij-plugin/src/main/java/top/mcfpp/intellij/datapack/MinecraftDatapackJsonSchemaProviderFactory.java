package top.mcfpp.intellij.datapack;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider;
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory;
import com.jetbrains.jsonSchema.extension.SchemaType;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Supplies path-sensitive bundled schemas for vanilla datapack JSON resources. */
public final class MinecraftDatapackJsonSchemaProviderFactory implements JsonSchemaProviderFactory {
    private static final List<JsonSchemaFileProvider> PROVIDERS = List.of(
            provider(MinecraftDatapackPath.Kind.PACK, "Minecraft pack.mcmeta", "/schemas/minecraft/pack.schema.json"),
            provider(MinecraftDatapackPath.Kind.TAG, "Minecraft tag", "/schemas/minecraft/tag.schema.json"),
            provider(MinecraftDatapackPath.Kind.ADVANCEMENT, "Minecraft advancement", "/schemas/minecraft/advancement.schema.json"),
            provider(MinecraftDatapackPath.Kind.RECIPE, "Minecraft recipe", "/schemas/minecraft/recipe.schema.json"),
            provider(MinecraftDatapackPath.Kind.PREDICATE, "Minecraft predicate", "/schemas/minecraft/predicate.schema.json"),
            provider(MinecraftDatapackPath.Kind.LOOT_TABLE, "Minecraft loot table", "/schemas/minecraft/loot-table.schema.json"),
            provider(MinecraftDatapackPath.Kind.ITEM_MODIFIER, "Minecraft item modifier", "/schemas/minecraft/item-modifier.schema.json"),
            provider(MinecraftDatapackPath.Kind.REGISTRY, "Minecraft datapack registry", "/schemas/minecraft/registry.schema.json")
    );

    @Override
    public @NotNull List<JsonSchemaFileProvider> getProviders(@NotNull Project project) {
        return PROVIDERS;
    }

    private static JsonSchemaFileProvider provider(MinecraftDatapackPath.Kind kind, String name, String resource) {
        return new BundledProvider(kind, name, resource);
    }

    private record BundledProvider(
            MinecraftDatapackPath.Kind kind,
            String name,
            String resource
    ) implements JsonSchemaFileProvider {
        @Override
        public boolean isAvailable(@NotNull VirtualFile file) {
            return MinecraftDatapackPath.kindForPath(file.getPath()) == kind;
        }

        @Override
        public @NotNull String getName() {
            return name;
        }

        @Override
        public @NotNull VirtualFile getSchemaFile() {
            VirtualFile file = JsonSchemaProviderFactory.getResourceFile(
                    MinecraftDatapackJsonSchemaProviderFactory.class,
                    resource
            );
            if (file == null) throw new IllegalStateException("Missing bundled Minecraft schema " + resource);
            return file;
        }

        @Override
        public @NotNull SchemaType getSchemaType() {
            return SchemaType.embeddedSchema;
        }
    }
}
