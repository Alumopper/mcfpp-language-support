package top.mcfpp.intellij.datapack;

import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.openapi.util.TextRange;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import java.util.ArrayList;

/** JSON strings implement contributed references; raw command files provide findReferenceAt themselves. */
public final class PackResourceReferenceContributor extends PsiReferenceContributor {
    @Override public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(), new PsiReferenceProvider() {
            @Override public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element, @NotNull ProcessingContext context) {
                if (!(element instanceof com.intellij.json.psi.JsonStringLiteral)) return PsiReference.EMPTY_ARRAY;
                var index = PackResourceIndex.forFile(element.getContainingFile());
                if (index == null) return PsiReference.EMPTY_ARRAY;
                var result = new ArrayList<PsiReference>(); var range = element.getTextRange();
                for (var ref : index.references(element.getContainingFile())) if (range.containsRange(ref.start(), ref.end()))
                    result.add(new PackResourceReference(element, new TextRange(ref.start() - range.getStartOffset(), ref.end() - range.getStartOffset()), ref));
                return result.toArray(PsiReference[]::new);
            }
        });
    }
}
