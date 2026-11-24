package top.mcfpp.intellij.mni;

import com.intellij.codeInsight.TargetElementEvaluatorEx2;
import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.codeInsight.TargetElementUtilExtender;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import top.mcfpp.intellij.lang.McfppReferenceNames;

/** Keep the call-site receiver and arguments available to implementation search. */
public final class McfppImplementationTargetEvaluator extends TargetElementEvaluatorEx2
        implements TargetElementUtilExtender {
    private static final int IMPLEMENTATION = 1 << 27;

    @Override
    public int getAllAdditionalFlags() {
        return 0;
    }

    @Override
    public int getAdditionalDefinitionSearchFlags() {
        return IMPLEMENTATION;
    }

    @Override
    public int getAdditionalReferenceSearchFlags() {
        return 0;
    }

    @Override
    public PsiElement adjustReferenceOrReferencedElement(PsiFile file, Editor editor, int offset, int flags,
                                                         PsiElement referenced) {
        if ((flags & IMPLEMENTATION) != 0 && (flags & TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED) != 0) {
            var usage = file.findElementAt(offset);
            if (McfppReferenceNames.isCall(usage)) return usage;
        }
        return referenced;
    }

    @Override
    public boolean includeSelfInGotoImplementation(PsiElement element) {
        return !McfppReferenceNames.isCall(element);
    }
}
