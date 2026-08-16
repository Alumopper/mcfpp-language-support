package top.mcfpp.intellij.mni;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import org.jetbrains.annotations.Nullable;
import top.mcfpp.intellij.lang.McfppLanguage;

import java.util.List;

/** Direct Ctrl+B navigation for declaration-to-implementation MNI relationships. */
public final class McfppMniGotoDeclarationHandler implements GotoDeclarationHandler {
    @Override
    public PsiElement @Nullable [] getGotoDeclarationTargets(
            PsiElement sourceElement,
            int offset,
            Editor editor
    ) {
        List<PsiElement> targets;
        if (sourceElement.getLanguage() == McfppLanguage.INSTANCE) {
            targets = McfppMniLineMarkerProvider.findJavaTargets(sourceElement);
        } else if (sourceElement instanceof PsiIdentifier identifier) {
            targets = McfppMniLineMarkerProvider.findMcfppTargets(identifier);
        } else {
            return null;
        }
        return targets.isEmpty() ? null : targets.toArray(PsiElement.EMPTY_ARRAY);
    }
}
