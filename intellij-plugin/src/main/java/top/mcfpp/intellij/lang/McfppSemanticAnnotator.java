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

public final class McfppSemanticAnnotator implements Annotator, DumbAware {
    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (element.getFirstChild() != null || element.getNode() == null) {
            return;
        }
        boolean identifier = element.getNode().getElementType() == McfppTokenTypes.IDENTIFIER;
        boolean string = element.getNode().getElementType() == McfppTokenTypes.STRING;
        boolean command = element.getNode().getElementType() == McfppTokenTypes.COMMAND;
        if (!identifier && !string && !command) return;

        PsiFile file = element.getContainingFile();
        McfppSemanticTokens.Analysis analysis = CachedValuesManager.getCachedValue(file, () ->
                CachedValueProvider.Result.create(
                        McfppSemanticTokens.analyzeDetailed(file.getViewProvider().getContents()),
                        file
                )
        );
        if (identifier) {
            var key = analysis.attributes().get(element.getTextOffset());
            if (key == null) return;
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(element)
                    .textAttributes(key)
                    .create();
            return;
        }

        TextRange elementRange = element.getTextRange();
        List<McfppSemanticTokens.EmbeddedRange> ranges = analysis.embeddedRanges();
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
