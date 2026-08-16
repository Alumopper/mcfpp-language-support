package top.mcfpp.intellij.lang;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.SearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class McfppNamedElement extends ASTWrapperPsiElement implements PsiNameIdentifierOwner {
    McfppNamedElement(@NotNull ASTNode node) {
        super(node);
    }

    @Override
    public @Nullable PsiElement getNameIdentifier() {
        List<PsiElement> identifiers = new ArrayList<>();
        collectIdentifiers(this, identifiers);
        return identifiers.isEmpty() ? null : identifiers.get(identifiers.size() - 1);
    }

    private static void collectIdentifiers(PsiElement element, List<PsiElement> result) {
        for (PsiElement child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNode() != null && child.getNode().getElementType() == McfppTokenTypes.IDENTIFIER) {
                result.add(child);
            } else if (child.getFirstChild() != null) {
                collectIdentifiers(child, result);
            }
        }
    }

    @Override
    public @Nullable String getName() {
        PsiElement identifier = getNameIdentifier();
        return identifier == null ? null : identifier.getText();
    }

    @Override
    public @NotNull PsiElement setName(@NotNull String name) {
        if (!McfppNames.isIdentifier(name)) return this;
        PsiElement identifier = getNameIdentifier();
        if (identifier == null) return this;

        identifier.replace(McfppElementFactory.createIdentifier(getProject(), name));
        return this;
    }

    @Override
    public int getTextOffset() {
        PsiElement identifier = getNameIdentifier();
        return identifier == null ? super.getTextOffset() : identifier.getTextOffset();
    }

    @Override
    public @NotNull SearchScope getUseScope() {
        if (getContainingFile() instanceof McfppFile file) {
            McfppSymbol symbol = McfppFileModels.get(file).declarationAt(getTextOffset());
            if (symbol != null && (symbol.kind() == McfppSymbolKind.VARIABLE ||
                    symbol.kind() == McfppSymbolKind.PARAMETER)) {
                return new LocalSearchScope(file);
            }
            return GlobalSearchScope.projectScope(getProject());
        }
        return super.getUseScope();
    }
}
