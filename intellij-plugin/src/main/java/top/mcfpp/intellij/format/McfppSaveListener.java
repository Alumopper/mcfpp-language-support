package top.mcfpp.intellij.format;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectLocator;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.tooling.McfppNotifications;
import top.mcfpp.intellij.tooling.McfppSettings;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Formats/fixes documents asynchronously when a save is requested. */
public final class McfppSaveListener implements FileDocumentManagerListener {
    private static final Key<Boolean> SAVING = Key.create("mcfpp.formatted.save");
    private static final Key<SaveState> IN_FLIGHT = Key.create("mcfpp.format.save.inFlight");

    @FunctionalInterface
    interface FormatterRequest {
        JsonObject request(Project project, String operation, String path, String source, boolean fix, int cursor)
                throws IOException;
    }

    private static final class SaveState {
        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicBoolean rerun = new AtomicBoolean();
        private volatile long stamp;
    }

    private final FormatterRequest formatterRequest;

    public McfppSaveListener() {
        this((project, operation, path, source, fix, cursor) ->
                project.getService(McfppNodeService.class).request(operation, path, source, fix, cursor));
    }

    McfppSaveListener(FormatterRequest formatterRequest) {
        this.formatterRequest = formatterRequest;
    }

    @Override
    public void beforeDocumentSaving(@NotNull Document document) {
        if (Boolean.TRUE.equals(document.getUserData(SAVING))) return;
        var file = FileDocumentManager.getInstance().getFile(document);
        if (file == null || !"mcfpp".equalsIgnoreCase(file.getExtension())) return;
        Project project = ProjectLocator.getInstance().guessProjectForFile(file);
        if (project == null || project.isDisposed()) return;
        var settings = McfppSettings.get(project);
        if (!settings.formatOnSave && !settings.fixOnSave) return;

        SaveState state = document.getUserData(IN_FLIGHT);
        if (state == null) {
            state = new SaveState();
            document.putUserData(IN_FLIGHT, state);
        }
        if (!state.running.compareAndSet(false, true)) {
            if (state.stamp != document.getModificationStamp()) state.rerun.set(true);
            return;
        }
        startAttempt(project, file, document, state, settings.formatOnSave ? "format" : "fix", settings.fixOnSave);
    }

    private void startAttempt(Project project, com.intellij.openapi.vfs.VirtualFile file, Document document,
                              SaveState state, String operation, boolean fix) {
        String source = document.getText();
        long stamp = document.getModificationStamp();
        state.stamp = stamp;
        Editor[] editors = EditorFactory.getInstance().getEditors(document, project);
        int cursor = editors.length == 0 ? 0 : editors[0].getCaretModel().getOffset();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            JsonObject response = null;
            Throwable failure = null;
            try {
                response = formatterRequest.request(project, operation, file.getPath(), source, fix, cursor);
            } catch (Throwable error) {
                failure = error;
            }
            JsonObject result = response;
            Throwable error = failure;
            ApplicationManager.getApplication().invokeLater(() -> finishAttempt(
                    project, file, document, state, operation, fix, source, stamp, cursor, result, error));
        });
    }

    private void finishAttempt(Project project, com.intellij.openapi.vfs.VirtualFile file, Document document,
                               SaveState state, String operation, boolean fix, String source, long stamp, int cursor,
                               JsonObject response, Throwable failure) {
        try {
            if (failure != null && !project.isDisposed()) {
                String message = failure.getMessage();
                McfppNotifications.error(project, message == null ? failure.getClass().getSimpleName() : message);
            }
            if (response != null && !project.isDisposed() && file.isValid() &&
                    isCurrent(project, file, document, stamp) && response.has("source")) {
                String formatted = response.get("source").getAsString();
                if (!source.equals(formatted)) {
                    document.putUserData(SAVING, Boolean.TRUE);
                    try {
                        boolean[] replaced = {false};
                        WriteCommandAction.runWriteCommandAction(project, "Format MCFPP on save", null, () -> {
                            if (isCurrent(project, file, document, stamp)) {
                                replace(document, formatted);
                                replaced[0] = true;
                            }
                        });
                        if (replaced[0]) {
                            Editor[] editors = EditorFactory.getInstance().getEditors(document, project);
                            if (response.has("cursorOffset") && editors.length > 0 && !editors[0].isDisposed() &&
                                    !editors[0].getSelectionModel().hasSelection()) {
                                editors[0].getCaretModel().moveToOffset(
                                        Math.min(formatted.length(), response.get("cursorOffset").getAsInt()));
                            }
                            if (file.isValid() && FileDocumentManager.getInstance().isFileModified(file)) {
                                FileDocumentManager.getInstance().saveDocument(document);
                            }
                        }
                    } catch (RuntimeException error) {
                        if (!project.isDisposed()) {
                            McfppNotifications.error(project, error.getMessage() == null ? "Could not format MCFPP on save" : error.getMessage());
                        }
                    } finally {
                        document.putUserData(SAVING, null);
                    }
                }
            }
        } finally {
            finishState(project, file, document, state, operation, fix);
        }
    }

    private void finishState(Project project, com.intellij.openapi.vfs.VirtualFile file, Document document,
                             SaveState state, String operation, boolean fix) {
        if (!project.isDisposed() && file.isValid() && state.rerun.getAndSet(false)) {
            startAttempt(project, file, document, state, operation, fix);
            return;
        }
        state.running.set(false);
        if (!project.isDisposed() && file.isValid() && state.rerun.getAndSet(false) && state.running.compareAndSet(false, true)) {
            startAttempt(project, file, document, state, operation, fix);
        }
    }

    static boolean isCurrent(Project project, com.intellij.openapi.vfs.VirtualFile file, Document document, long stamp) {
        return !project.isDisposed() && file.isValid() &&
                FileDocumentManager.getInstance().getFile(document) == file &&
                document.getModificationStamp() == stamp;
    }

    public static void replace(Document document, String replacement) {
        String old = document.getText();
        int start = 0, end = old.length(), newEnd = replacement.length();
        while (start < end && start < newEnd && old.charAt(start) == replacement.charAt(start)) start++;
        while (end > start && newEnd > start && old.charAt(end - 1) == replacement.charAt(newEnd - 1)) {
            end--;
            newEnd--;
        }
        document.replaceString(start, end, replacement.substring(start, newEnd));
    }
}
