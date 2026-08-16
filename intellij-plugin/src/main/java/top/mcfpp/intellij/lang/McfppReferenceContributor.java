package top.mcfpp.intellij.lang;

import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.PsiReferenceProvider;
import com.intellij.psi.PsiReferenceRegistrar;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;

public final class McfppReferenceContributor extends PsiReferenceContributor {
    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(
                PlatformPatterns.psiElement().withLanguage(McfppLanguage.INSTANCE),
                new PsiReferenceProvider() {
                    @Override
                    public PsiReference @NotNull [] getReferencesByElement(
                            @NotNull PsiElement element,
                            @NotNull ProcessingContext context
                    ) {
                        return referencesFor(element);
                    }
                }
        );
    }

    static PsiReference @NotNull [] referencesFor(PsiElement element) {
        if (element.getFirstChild() != null || element.getNode() == null ||
                element.getNode().getElementType() != McfppTokenTypes.IDENTIFIER) return PsiReference.EMPTY_ARRAY;
        McfppNamedElement declaration = PsiTreeUtil.getParentOfType(element, McfppNamedElement.class, false);
        if (declaration != null && declaration.getNameIdentifier() == element) return PsiReference.EMPTY_ARRAY;
        if (McfppFileModels.get(element.getContainingFile()).declarationAt(element.getTextOffset()) != null) {
            return PsiReference.EMPTY_ARRAY;
        }
        if (isNativeJavaTarget(element)) return PsiReference.EMPTY_ARRAY;
        return new PsiReference[]{new McfppSymbolReference(element)};
    }

    static boolean isNativeJavaTarget(PsiElement element) {
        CharSequence source = element.getContainingFile().getViewProvider().getContents();
        int offset = element.getTextOffset();
        int boundary = offset;
        while (boundary > 0) {
            char previous = source.charAt(boundary - 1);
            if (previous == ';' || previous == '{' || previous == '}') break;
            boundary--;
        }
        String prefix = source.subSequence(boundary, offset).toString();
        int assignment = prefix.indexOf('=');
        return assignment >= 0 && prefix.substring(0, assignment).matches("(?s).*\\b(func|operator|get|set)\\b.*");
    }
}
