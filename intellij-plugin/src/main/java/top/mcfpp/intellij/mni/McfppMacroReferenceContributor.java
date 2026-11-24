package top.mcfpp.intellij.mni;

import com.intellij.openapi.util.TextRange;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.lang.McfppElementFactory;
import top.mcfpp.intellij.lang.McfppFile;
import top.mcfpp.intellij.lang.McfppLanguage;
import top.mcfpp.intellij.lang.McfppReferenceNames;

import java.util.Objects;

public final class McfppMacroReferenceContributor extends PsiReferenceContributor {
    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(
                PlatformPatterns.psiElement().withLanguage(McfppLanguage.INSTANCE), new PsiReferenceProvider() {
                    @Override
                    public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element,
                                                                           @NotNull ProcessingContext context) {
                        return referencesFor(element);
                    }
                });
    }

    static PsiReference[] referencesFor(PsiElement element) {
        if (!McfppReferenceNames.isCall(element)) return PsiReference.EMPTY_ARRAY;
        var file = (McfppFile) element.getContainingFile();
        var candidates = McfppReferenceNames.isMember(element)
                ? McfppMacroIndex.visibleAtReceiver(file, element.getText(), element.getTextOffset())
                : McfppMacroIndex.visibleAt(file, element.getText(), element.getTextOffset()).stream()
                        .filter(macro -> macro.receiverType() == null || macro.receiverType().isBlank()).toList();
        if (candidates.isEmpty()) return PsiReference.EMPTY_ARRAY;
        return new PsiReference[]{new PsiPolyVariantReferenceBase<PsiElement>(
                element, TextRange.from(0, element.getTextLength()), false) {
            @Override
            public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
                return candidates.stream()
                        .map(McfppMacroIndex.Macro::declaration).filter(Objects::nonNull)
                        .map(PsiElementResolveResult::new).toArray(ResolveResult[]::new);
            }

            @Override
            public @NotNull PsiElement handleElementRename(@NotNull String name) {
                return element.replace(McfppElementFactory.createIdentifier(element.getProject(), name));
            }
        }};
    }
}
