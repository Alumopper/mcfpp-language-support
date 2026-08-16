package top.mcfpp.intellij.lang;

import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.FoldingBuilderEx;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class McfppFoldingBuilder extends FoldingBuilderEx {
    @Override
    public FoldingDescriptor @NotNull [] buildFoldRegions(
            @NotNull PsiElement root,
            @NotNull Document document,
            boolean quick
    ) {
        PsiFile file = root.getContainingFile();
        if (!(file instanceof McfppFile)) return FoldingDescriptor.EMPTY_ARRAY;
        List<FoldingDescriptor> descriptors = new ArrayList<>();
        for (McfppSymbol symbol : McfppFileModels.get(file).symbols()) {
            if (symbol.bodyStart() < 0 || symbol.endOffset() - symbol.bodyStart() < 3) continue;
            if (document.getLineNumber(symbol.bodyStart()) == document.getLineNumber(symbol.endOffset() - 1)) continue;
            PsiElement anchor = file.findElementAt(symbol.bodyStart());
            ASTNode node = anchor == null ? null : anchor.getNode();
            if (node != null) {
                descriptors.add(new FoldingDescriptor(
                        node,
                        new TextRange(symbol.bodyStart(), symbol.endOffset()),
                        null,
                        "{…}"
                ));
            }
        }
        return descriptors.toArray(FoldingDescriptor.EMPTY_ARRAY);
    }

    @Override
    public @Nullable String getPlaceholderText(@NotNull ASTNode node) {
        return "{…}";
    }

    @Override
    public boolean isCollapsedByDefault(@NotNull ASTNode node) {
        return false;
    }
}
