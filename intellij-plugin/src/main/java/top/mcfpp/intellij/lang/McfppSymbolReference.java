package top.mcfpp.intellij.lang;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementResolveResult;
import com.intellij.psi.PsiPolyVariantReferenceBase;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.impl.source.resolve.ResolveCache;
import org.jetbrains.annotations.NotNull;

final class McfppSymbolReference extends PsiPolyVariantReferenceBase<PsiElement> {
    private static final ResolveCache.PolyVariantResolver<McfppSymbolReference> RESOLVER =
            (reference, incompleteCode) -> reference.resolveWithoutCache();

    McfppSymbolReference(@NotNull PsiElement element) {
        super(element, TextRange.from(0, element.getTextLength()), true);
    }

    @Override
    public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
        return ResolveCache.getInstance(myElement.getProject())
                .resolveWithCaching(this, RESOLVER, true, incompleteCode);
    }

    private ResolveResult @NotNull [] resolveWithoutCache() {
        return McfppSymbolResolver.resolve(myElement).stream()
                .map(PsiElementResolveResult::new)
                .toArray(ResolveResult[]::new);
    }

    @Override
    public PsiElement handleElementRename(@NotNull String newElementName) {
        if (!McfppNames.isIdentifier(newElementName)) return myElement;
        return myElement.replace(McfppElementFactory.createIdentifier(myElement.getProject(), newElementName));
    }
}
