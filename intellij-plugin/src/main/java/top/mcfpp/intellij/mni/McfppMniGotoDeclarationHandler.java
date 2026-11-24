package top.mcfpp.intellij.mni;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiPolyVariantReference;
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
        if (sourceElement == null) return null;
        List<PsiElement> targets;
        if (sourceElement.getLanguage() == McfppLanguage.INSTANCE) {
            targets = McfppMniLineMarkerProvider.findJavaTargets(sourceElement);
            if (targets.isEmpty()) {
                targets = java.util.Arrays.stream(McfppMniReferenceContributor.referencesFor(sourceElement))
                        .flatMap(reference -> reference instanceof PsiPolyVariantReference poly
                                ? java.util.Arrays.stream(poly.multiResolve(false)).map(result -> result.getElement())
                                : java.util.stream.Stream.of(reference.resolve()))
                        .filter(java.util.Objects::nonNull).distinct().toList();
            }
        } else if (sourceElement instanceof PsiIdentifier identifier) {
            targets = McfppMniLineMarkerProvider.findMcfppTargets(identifier);
        } else {
            return null;
        }
        return targets.isEmpty() ? null : targets.stream().map(PsiElement::getNavigationElement).toArray(PsiElement[]::new);
    }
}
