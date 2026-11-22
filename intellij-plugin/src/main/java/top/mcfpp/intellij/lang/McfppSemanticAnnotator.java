package top.mcfpp.intellij.lang;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class McfppSemanticAnnotator implements Annotator, DumbAware {
    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (element instanceof McfppFile file) {
            for (var range : McfppFileModels.versionAnalysis(file).inactiveRanges()) {
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                        .range(new TextRange(range.start(), range.end()))
                        .textAttributes(com.intellij.openapi.editor.colors.CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES)
                        .create();
            }
            return;
        }
        if (element.getFirstChild() != null || element.getNode() == null) {
            return;
        }
        boolean identifier = element.getNode().getElementType() == McfppTokenTypes.IDENTIFIER;
        boolean string = element.getNode().getElementType() == McfppTokenTypes.STRING;
        boolean command = element.getNode().getElementType() == McfppTokenTypes.COMMAND;
        if (!identifier && !string && !command) return;

        PsiFile file = element.getContainingFile();
        if (McfppFileModels.versionAnalysis(file).inactiveRanges().stream().anyMatch(range ->
                range.start() <= element.getTextOffset() && element.getTextOffset() < range.end())) return;
        boolean lspActive = McfppLspState.isRunning(file.getProject());
        McfppSemanticTokens.Analysis analysis = McfppFileModels.semanticAnalysis(file);
        if (identifier) {
            if (!lspActive && "ref".equals(element.getText())) {
                holder.newAnnotation(HighlightSeverity.ERROR,
                                "'ref expression' has been removed. Pass a ScoreRef or NBTRef<T> created from an explicit Minecraft address.")
                        .range(element).create();
                return;
            }
            var key = analysis.attributes().get(element.getTextOffset());
            var javaPlugin = com.intellij.ide.plugins.PluginManagerCore.getPlugin(
                    com.intellij.openapi.extensions.PluginId.getId("com.intellij.java"));
            if ((key == McfppSyntaxHighlighter.FUNCTION_DECLARATION || key == McfppSyntaxHighlighter.FUNCTION_CALL) &&
                    javaPlugin != null && javaPlugin.isEnabled() && !com.intellij.openapi.project.DumbService.isDumb(element.getProject())) {
                var nativeKey = top.mcfpp.intellij.mni.McfppNativeFunctionHighlighting.keyFor(element);
                if (nativeKey != null) {
                    holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                            .range(element)
                            .textAttributes(nativeKey)
                            .create();
                    return;
                }
            }
            // When LSP is running, let LSP semanticTokensProvider own standard semantic coloring.
            if (lspActive || key == null) return;
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
