package top.mcfpp.intellij.command;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;

/** Starts the command service off the UI thread so the first completion is immediate. */
public final class McfppCommandServiceStartupActivity implements ProjectActivity {
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        String basePath = project.getBasePath();
        if (!ApplicationManager.getApplication().isUnitTestMode() && basePath != null && isCommandProject(basePath)) {
            ApplicationManager.getApplication().executeOnPooledThread(() ->
                    McfppCommandService.getInstance(project).warmUp());
        }
        return Unit.INSTANCE;
    }

    private static boolean isCommandProject(String basePath) {
        Path root = Path.of(basePath);
        return Files.isRegularFile(root.resolve("mcfpp.json")) || Files.isRegularFile(root.resolve("pack.mcmeta"));
    }
}
