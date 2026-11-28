package top.mcfpp.intellij.datapack;

import com.intellij.lang.annotation.*;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.lang.McfppFile;
import top.mcfpp.intellij.lang.McfppLspState;
import top.mcfpp.intellij.lang.McfppResourceArguments;

public final class PackResourceAnnotator implements Annotator {
    @Override public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (!(element instanceof PsiFile file)) return;
        var index = PackResourceIndex.forFile(file);
        if (file instanceof McfppFile && !McfppLspState.isRunning(file.getProject())) annotateArguments(file, index, holder);
        if (index == null) return;
        for (var ref : index.commandReferences(file)) if (index.missing(ref))
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

    private static void annotateArguments(PsiFile file, PackResourceIndex index, AnnotationHolder holder) {
        var vanilla = PackResourceIndex.vanilla(index);
        for (var argument : McfppResourceArguments.uses(file)) {
            var reference = argument.reference();
            if (reference == null) {
                TextRange range = argument.start() == argument.end()
                        ? new TextRange(argument.start() - 1, argument.end() + 1)
                        : new TextRange(argument.start(), argument.end());
                holder.newAnnotation(HighlightSeverity.WARNING, "Invalid " + argument.kind() + " resource ID: " + argument.value())
                        .range(range).create();
                continue;
            }
            var key = reference.key();
            if (index != null && index.contains(key) || vanilla.contains(key)) continue;
            boolean minecraft = key.id().startsWith("minecraft:");
            if (minecraft ? !vanilla.knows(key.kind()) : index == null) continue;
            holder.newAnnotation(HighlightSeverity.WARNING, "Missing " + key.kind() + " resource: " + key.id())
                    .range(new TextRange(reference.start(), reference.end())).create();
        }
    }
}
