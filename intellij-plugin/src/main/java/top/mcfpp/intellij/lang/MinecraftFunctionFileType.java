package top.mcfpp.intellij.lang;

import com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

public final class MinecraftFunctionFileType extends LanguageFileType {
    public static final MinecraftFunctionFileType INSTANCE = new MinecraftFunctionFileType();

    private MinecraftFunctionFileType() {
        super(MinecraftFunctionLanguage.INSTANCE);
    }

    @Override
    public @NonNls @NotNull String getName() {
        return "Minecraft Function";
    }

    @Override
    public @Nls @NotNull String getDescription() {
        return "Minecraft datapack function";
    }

    @Override
    public @NotNull String getDefaultExtension() {
        return "mcfunction";
    }

    @Override
    public Icon getIcon() {
        return McfppIcons.FILE;
    }
}
