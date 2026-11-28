package top.mcfpp.intellij.lsp;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiPolyVariantReference;
import top.mcfpp.intellij.lang.McfppReferenceNames;

/** Makes remote definitions available to IDEA's Ctrl-hover as well as the declaration action. */
public final class McfppLspGotoDeclarationHandler implements GotoDeclarationHandler {
    @Override
    public PsiElement[] getGotoDeclarationTargets(PsiElement source, int offset, Editor editor) {
        if (!McfppReferenceNames.isName(source)) return null;
        var references = source.getReferences();
        if (references.length == 0) return null;
        for (var reference : references) {
            if (reference instanceof PsiPolyVariantReference poly) {
                for (var result : poly.multiResolve(false)) {
                    if (result.isValidResult() && result.getElement() != null) return null;
                }
            } else if (reference.resolve() != null) {
                return null;
            }
        }
        var lookup = source.getProject().getService(McfppLspDefinitionLookup.class);
        var target = lookup == null ? null : lookup.find(source);
        return target == null ? null : new PsiElement[]{target};
    }
}
