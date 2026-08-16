package top.mcfpp.intellij.lang;

import com.intellij.lang.Language;
import org.jetbrains.annotations.NotNull;

public final class McfppLanguage extends Language {
    public static final McfppLanguage INSTANCE = new McfppLanguage();

    private McfppLanguage() {
        super("MCFPP");
    }

    @Override
    public @NotNull String getDisplayName() {
        return "MCFPP";
    }
}
