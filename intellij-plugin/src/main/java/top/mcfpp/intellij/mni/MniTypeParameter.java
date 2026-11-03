package top.mcfpp.intellij.mni;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.impl.light.LightElement;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.SearchScope;
import org.jetbrains.annotations.NotNull;

/** A genericType string declares a source-language parameter scoped to this JVM method. */
final class MniTypeParameter extends LightElement implements PsiNamedElement {
    private final MniTypeContext declaration;

    MniTypeParameter(MniTypeContext declaration) {
        super(declaration.host.getManager(), declaration.host.getLanguage());
        this.declaration = declaration;
    }
    @Override public String getName() { return declaration.text.trim(); }
    @Override public @NotNull PsiElement setName(@NotNull String name) {
        ElementManipulators.handleContentChange(declaration.host, declaration.valueRange, name);
        return this;
    }
    @Override public @NotNull PsiElement getNavigationElement() { return declaration.host; }
    @Override public PsiFile getContainingFile() { return declaration.host.getContainingFile(); }
    @Override public TextRange getTextRange() { return declaration.valueRange.shiftRight(declaration.host.getTextOffset()); }
    @Override public int getTextOffset() { return getTextRange().getStartOffset(); }
    @Override public String getText() { return getName(); }
    @Override public boolean isValid() { return declaration.host.isValid(); }
    @Override public @NotNull SearchScope getUseScope() {
        return new LocalSearchScope(declaration.method == null ? declaration.host : declaration.method.getSourcePsi());
    }
    @Override public boolean isEquivalentTo(PsiElement other) {
        return other instanceof MniTypeParameter parameter && declaration.host.isEquivalentTo(parameter.declaration.host);
    }
    @Override public String toString() { return "MCFPP generic parameter " + getName(); }
}
