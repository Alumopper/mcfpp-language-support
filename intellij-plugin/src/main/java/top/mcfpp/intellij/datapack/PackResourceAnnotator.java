package top.mcfpp.intellij.datapack;

import com.intellij.lang.annotation.*;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

public final class PackResourceAnnotator implements Annotator {
    @Override public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (!(element instanceof PsiFile file)) return;
        var index = PackResourceIndex.forFile(file); if (index == null) return;
        for (var ref : index.references(file)) if (index.missing(ref))
            holder.newAnnotation(HighlightSeverity.ERROR, "Missing " + ref.key().kind() + " resource: " + ref.key().id())
                    .range(new TextRange(ref.start(), ref.end())).create();
        var key = index.owner(file);
        if (key != null && index.definitions.getOrDefault(key, java.util.List.of()).size() > 1 && file.getTextLength() > 0)
            holder.newAnnotation(HighlightSeverity.ERROR, "Conflicting resource ID: " + key.id())
                    .range(new TextRange(0, Math.min(file.getTextLength(), 1))).create();
        if (key != null && index.generatedFunctions.contains(key) && file.getTextLength() > 0) {
            String relative = index.configuration.dataPath().relativize(java.nio.file.Path.of(file.getVirtualFile().getPath()).toAbsolutePath().normalize()).toString().replace('\\', '/');
            boolean override = index.configuration.resourceOverrides().contains(relative);
            if (!override) holder.newAnnotation(HighlightSeverity.ERROR, "Resource conflicts with compiler output: " + key.id())
                    .range(new TextRange(0, 1)).create();
        }
    }
}
