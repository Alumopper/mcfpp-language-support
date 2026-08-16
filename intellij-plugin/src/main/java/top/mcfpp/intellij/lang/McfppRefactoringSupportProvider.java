package top.mcfpp.intellij.lang;

import com.intellij.lang.refactoring.RefactoringSupportProvider;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class McfppRefactoringSupportProvider extends RefactoringSupportProvider {
    @Override
    public boolean isMemberInplaceRenameAvailable(
            @NotNull PsiElement element,
            @Nullable PsiElement context
    ) {
        return element instanceof McfppNamedElement;
    }
}
