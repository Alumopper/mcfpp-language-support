package top.mcfpp.intellij.mni;

import com.intellij.psi.PsiElement;

import java.util.List;

/** Optional project service provided by the LSP integration. */
public interface McfppRemoteImplementationLookup {
    List<PsiElement> find(PsiElement call);
}
