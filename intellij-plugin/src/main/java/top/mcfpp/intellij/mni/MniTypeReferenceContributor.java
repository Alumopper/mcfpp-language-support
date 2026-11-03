package top.mcfpp.intellij.mni;

import com.intellij.patterns.uast.UastPatterns;
import com.intellij.psi.*;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.uast.UExpression;

public final class MniTypeReferenceContributor extends PsiReferenceContributor {
    @Override public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        UastReferenceRegistrar.registerUastReferenceProvider(registrar, UastPatterns.injectionHostUExpression(),
                new UastInjectionHostReferenceProvider() {
                    @Override public PsiReference @NotNull [] getReferencesForInjectionHost(@NotNull UExpression expression,
                            @NotNull PsiLanguageInjectionHost host, @NotNull ProcessingContext processing) {
                        var context = MniTypeContext.from(host);
                        return context == null ? PsiReference.EMPTY_ARRAY : context.references();
                    }
                });
    }
}
