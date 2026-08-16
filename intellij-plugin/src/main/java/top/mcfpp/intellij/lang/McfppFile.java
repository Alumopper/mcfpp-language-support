package top.mcfpp.intellij.lang;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;
import org.jetbrains.annotations.NotNull;

public final class McfppFile extends PsiFileBase {
    public McfppFile(@NotNull FileViewProvider viewProvider) {
        super(viewProvider, McfppLanguage.INSTANCE);
    }

    @Override
    public @NotNull FileType getFileType() {
        return McfppFileType.INSTANCE;
    }

    @Override
    public String toString() {
        return "MCFPP File";
    }
}
