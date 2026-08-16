package top.mcfpp.intellij.lang;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;
import org.jetbrains.annotations.NotNull;

public final class MinecraftFunctionFile extends PsiFileBase {
    public MinecraftFunctionFile(@NotNull FileViewProvider viewProvider) {
        super(viewProvider, MinecraftFunctionLanguage.INSTANCE);
    }

    @Override
    public @NotNull FileType getFileType() {
        return MinecraftFunctionFileType.INSTANCE;
    }

    @Override
    public String toString() {
        return "Minecraft Function File";
    }
}
