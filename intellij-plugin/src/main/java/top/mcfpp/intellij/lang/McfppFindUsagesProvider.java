package top.mcfpp.intellij.lang;

import com.intellij.lang.cacheBuilder.DefaultWordsScanner;
import com.intellij.lang.cacheBuilder.WordsScanner;
import com.intellij.lang.findUsages.FindUsagesProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class McfppFindUsagesProvider implements FindUsagesProvider {
    @Override
    public @Nullable WordsScanner getWordsScanner() {
        return new DefaultWordsScanner(
                new McfppLexer(),
                TokenSet.create(McfppTokenTypes.IDENTIFIER),
                TokenSet.create(McfppTokenTypes.COMMENT, McfppTokenTypes.DOC_COMMENT, McfppTokenTypes.VERSION_DIRECTIVE),
                TokenSet.create(McfppTokenTypes.STRING)
        );
    }

    @Override
    public boolean canFindUsagesFor(@NotNull PsiElement psiElement) {
        return psiElement instanceof McfppNamedElement;
    }

    @Override
    public @Nullable String getHelpId(@NotNull PsiElement psiElement) {
        return null;
    }

    @Override
    public @NotNull String getType(@NotNull PsiElement element) {
        McfppSymbol symbol = symbol(element);
        return symbol == null ? "declaration" : symbol.kind().presentableName();
    }

    @Override
    public @NotNull String getDescriptiveName(@NotNull PsiElement element) {
        return element instanceof McfppNamedElement named && named.getName() != null ? named.getName() : element.getText();
    }

    @Override
    public @NotNull String getNodeText(@NotNull PsiElement element, boolean useFullName) {
        McfppSymbol symbol = symbol(element);
        if (symbol == null) return getDescriptiveName(element);
        return useFullName ? symbol.qualifiedName() : symbol.signature();
    }

    private static McfppSymbol symbol(PsiElement element) {
        if (!(element.getContainingFile() instanceof McfppFile file)) return null;
        return McfppFileModels.get(file).declarationAt(element.getTextOffset());
    }
}
