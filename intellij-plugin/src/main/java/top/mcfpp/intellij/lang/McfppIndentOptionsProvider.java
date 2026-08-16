package top.mcfpp.intellij.lang;

import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.codeStyle.FileTypeIndentOptionsProvider;
import org.jetbrains.annotations.NotNull;

public final class McfppIndentOptionsProvider implements FileTypeIndentOptionsProvider {
    @Override
    public @NotNull CommonCodeStyleSettings.IndentOptions createIndentOptions() {
        CommonCodeStyleSettings.IndentOptions options = new CommonCodeStyleSettings.IndentOptions();
        options.INDENT_SIZE = 4;
        options.CONTINUATION_INDENT_SIZE = 8;
        options.TAB_SIZE = 4;
        options.USE_TAB_CHARACTER = false;
        return options;
    }

    @Override
    public @NotNull FileType getFileType() {
        return McfppFileType.INSTANCE;
    }

    @Override
    public @NotNull String getPreviewText() {
        return """
                data Example {
                    func update(value as int) -> bool {
                        if (value > 0) {
                            return true
                        }
                        return false
                    }
                }
                """;
    }

    @Override
    public void prepareForReformat(@NotNull PsiFile file) {
    }
}
