package top.mcfpp.intellij.lang;

import com.intellij.lang.Language;
import org.jetbrains.annotations.NotNull;

public final class MinecraftFunctionLanguage extends Language {
    public static final MinecraftFunctionLanguage INSTANCE = new MinecraftFunctionLanguage();

    private MinecraftFunctionLanguage() {
        super("MinecraftFunction");
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Minecraft Function";
    }
}
