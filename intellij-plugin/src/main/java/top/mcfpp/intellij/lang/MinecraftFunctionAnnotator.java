package top.mcfpp.intellij.lang;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Adds structural command colors on top of the line-oriented .mcfunction lexer. */
public final class MinecraftFunctionAnnotator implements Annotator, DumbAware {
    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (element.getFirstChild() != null || element.getNode() == null ||
                element.getNode().getElementType() != MinecraftFunctionTokenTypes.COMMAND) return;

        PsiFile file = element.getContainingFile();
        List<McfppSemanticTokens.EmbeddedRange> ranges = CachedValuesManager.getCachedValue(file, () ->
                CachedValueProvider.Result.create(
                        McfppCommandSyntax.highlightMcfunction(file.getViewProvider().getContents()),
                        file
                )
        );
        TextRange elementRange = element.getTextRange();
        for (int index = firstStartingAtOrAfter(ranges, elementRange.getStartOffset()); index < ranges.size(); index++) {
            McfppSemanticTokens.EmbeddedRange range = ranges.get(index);
            if (range.startOffset() >= elementRange.getEndOffset()) break;
            if (range.endOffset() > elementRange.getEndOffset()) continue;
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(new TextRange(range.startOffset(), range.endOffset()))
                    .textAttributes(range.attributesKey())
                    .create();
        }
    }

    private static int firstStartingAtOrAfter(List<McfppSemanticTokens.EmbeddedRange> ranges, int offset) {
        int low = 0;
        int high = ranges.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (ranges.get(middle).startOffset() < offset) low = middle + 1;
            else high = middle;
        }
        return low;
    }
}
