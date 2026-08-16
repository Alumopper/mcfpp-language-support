package top.mcfpp.intellij.lang;

import com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

public final class McfppFileType extends LanguageFileType {
    public static final McfppFileType INSTANCE = new McfppFileType();

    private McfppFileType() {
        super(McfppLanguage.INSTANCE);
    }

    @Override
    public @NonNls @NotNull String getName() {
        return "MCFPP";
    }

    @Override
    public @Nls @NotNull String getDescription() {
        return "MCFPP source file";
    }

    @Override
    public @NotNull String getDefaultExtension() {
        return "mcfpp";
    }

    @Override
    public Icon getIcon() {
        return McfppIcons.FILE;
    }
}
