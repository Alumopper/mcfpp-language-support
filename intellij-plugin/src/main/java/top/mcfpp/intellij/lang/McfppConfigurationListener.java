package top.mcfpp.intellij.lang;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInsight.folding.CodeFoldingManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteIntentReadAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.psi.PsiDocumentManager;
import org.jetbrains.annotations.NotNull;
import java.util.List;

/** A saved target-version change must refresh open editors without requiring a source edit. */
public final class McfppConfigurationListener implements BulkFileListener {
    private final Project project;
    public McfppConfigurationListener(Project project) { this.project = project; }

    @Override public void after(@NotNull List<? extends VFileEvent> events) {
        if (events.stream().noneMatch(event -> event.getPath().endsWith("/mcfpp.json"))) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            WriteIntentReadAction.run((Runnable) () -> {
                for (var fileEditor : FileEditorManager.getInstance(project).getAllEditors()) {
                    if (!(fileEditor instanceof TextEditor textEditor)) continue;
                    var editor = textEditor.getEditor();
                    var file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
                    if (!(file instanceof McfppFile)) continue;
                    refreshEditor(project, editor, (McfppFile) file);
                }
            });
        }, project.getDisposed());
    }

    static void refreshEditor(Project project, com.intellij.openapi.editor.Editor editor, McfppFile file) {
        var folding = editor.getFoldingModel();
        var previous = java.util.Arrays.stream(folding.getAllFoldRegions())
                .filter(region -> McfppFoldingBuilder.INACTIVE_PLACEHOLDER.equals(region.getPlaceholderText()))
                .map(region -> new com.intellij.openapi.util.TextRange(region.getStartOffset(), region.getEndOffset()))
                .collect(java.util.stream.Collectors.toSet());
        DaemonCodeAnalyzer.getInstance(project).restart(file, McfppConfigurationListener.class);
        CodeFoldingManager.getInstance(project).updateFoldRegions(editor);
        // Ordinary updates expand newly created regions. Collapse only branches that just became inactive,
        // preserving an existing inactive branch that the user deliberately expanded.
        folding.runBatchFoldingOperation(() -> {
            for (var region : folding.getAllFoldRegions()) {
                if (McfppFoldingBuilder.INACTIVE_PLACEHOLDER.equals(region.getPlaceholderText()) &&
                        !previous.contains(new com.intellij.openapi.util.TextRange(region.getStartOffset(), region.getEndOffset()))) {
                    region.setExpanded(false);
                }
            }
        });
    }
}
