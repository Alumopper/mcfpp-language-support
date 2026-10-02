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
    static final String INACTIVE_PLACEHOLDER = "Inactive code";
    @Override
    public FoldingDescriptor @NotNull [] buildFoldRegions(
            @NotNull PsiElement root,
            @NotNull Document document,
            boolean quick
    ) {
        PsiFile file = root.getContainingFile();
        if (!(file instanceof McfppFile)) return FoldingDescriptor.EMPTY_ARRAY;
        List<FoldingDescriptor> descriptors = new ArrayList<>();
        for (var range : McfppFileModels.versionAnalysis(file).inactiveRanges()) {
            int end = range.end();
            // Leave the newline and the next directive visible when collapsed.
            String source = file.getText();
            while (end > range.start() && (source.charAt(end - 1) == '\n' || source.charAt(end - 1) == '\r')) end--;
            if (end <= range.start() || source.substring(range.start(), end).isBlank()) continue;
            PsiElement anchor = file.findElementAt(range.start());
            if (anchor != null) {
                var descriptor = new FoldingDescriptor(anchor.getNode(), new TextRange(range.start(), end),
                        null, INACTIVE_PLACEHOLDER, true, foldingDependencies(file));
                descriptor.setGutterMarkEnabledForSingleLine(true);
                descriptors.add(descriptor);
            }
        }
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

    private static java.util.Set<Object> foldingDependencies(PsiFile file) {
        var configuration = McfppNamespaceResolver.infer(file).dependency();
        return configuration == null ? java.util.Set.of(file) : java.util.Set.of(file, configuration);
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
