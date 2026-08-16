package top.mcfpp.intellij.mni;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiPolyVariantReferenceBase;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.PsiElementResolveResult;
import com.intellij.psi.impl.source.resolve.ResolveCache;
import org.jetbrains.annotations.NotNull;

public final class McfppJavaReference extends PsiPolyVariantReferenceBase<PsiElement> {
    private static final ResolveCache.PolyVariantResolver<McfppJavaReference> RESOLVER =
            (reference, incompleteCode) -> reference.resolveWithoutCache();
    private final MniJavaTargetParser.Target target;
    private final MniJavaTargetParser.Segment segment;

    McfppJavaReference(
            @NotNull PsiElement element,
            @NotNull TextRange rangeInElement,
            @NotNull MniJavaTargetParser.Target target,
            @NotNull MniJavaTargetParser.Segment segment
    ) {
        super(element, rangeInElement, false);
        this.target = target;
        this.segment = segment;
    }

    @Override
    public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
        return ResolveCache.getInstance(myElement.getProject())
                .resolveWithCaching(this, RESOLVER, true, incompleteCode);
    }

    private ResolveResult @NotNull [] resolveWithoutCache() {
        CharSequence source = myElement.getContainingFile().getViewProvider().getContents();
        return MniJavaResolver.resolve(
                        myElement.getProject(),
                        target,
                        segment,
                        MniFunctionSignature.from(source, target)
                ).stream()
                .map(PsiElementResolveResult::new)
                .toArray(ResolveResult[]::new);
    }
}
