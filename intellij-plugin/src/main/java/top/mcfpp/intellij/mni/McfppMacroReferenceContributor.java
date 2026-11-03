package top.mcfpp.intellij.mni;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.openapi.util.TextRange;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.lang.*;
public final class McfppMacroReferenceContributor extends PsiReferenceContributor {
    @Override public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement().withLanguage(McfppLanguage.INSTANCE),new PsiReferenceProvider() {
            @Override public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element,@NotNull ProcessingContext context) {
                if(element.getNode()==null || element.getNode().getElementType()!=McfppTokenTypes.IDENTIFIER ||
                    !(element.getContainingFile() instanceof McfppFile file) || McfppFileModels.get(file).declarationAt(element.getTextOffset())!=null)
                    return PsiReference.EMPTY_ARRAY;
                String text=file.getText(); int offset=element.getTextRange().getEndOffset();
                while(offset<text.length() && Character.isWhitespace(text.charAt(offset))) offset++;
                if(offset>=text.length() || text.charAt(offset)!='(') return PsiReference.EMPTY_ARRAY;
                if(McfppMacroIndex.visibleAt(file,element.getText(),element.getTextOffset()).isEmpty()) return PsiReference.EMPTY_ARRAY;
                return new PsiReference[]{new PsiPolyVariantReferenceBase<PsiElement>(element,TextRange.from(0,element.getTextLength()),false) {
                    @Override public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
                        return McfppMacroIndex.visibleAt(file,element.getText(),element.getTextOffset()).stream().map(m -> new PsiElementResolveResult(m.method())).toArray(ResolveResult[]::new);
                    }
                    @Override public @NotNull PsiElement handleElementRename(@NotNull String name) {
                        var macros=McfppMacroIndex.visibleAt(file,element.getText(),element.getTextOffset());
                        if(macros.stream().anyMatch(m -> !McfppMacroIndex.constant(m.method().getAnnotation(McfppMacroIndex.ANNOTATION).findDeclaredAttributeValue("identifier")).isEmpty())) return element;
                        return element.replace(McfppElementFactory.createIdentifier(element.getProject(),name));
                    }
                }};
            }
        });
    }
}

