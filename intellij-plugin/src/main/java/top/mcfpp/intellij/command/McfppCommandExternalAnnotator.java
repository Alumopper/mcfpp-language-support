package top.mcfpp.intellij.command;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.mcfpp.intellij.lang.McfppFile;

import java.util.ArrayList;
import java.util.List;

/** Background syntax checks for static raw Minecraft commands. */
public final class McfppCommandExternalAnnotator
        extends ExternalAnnotator<McfppCommandExternalAnnotator.Input, List<McfppCommandExternalAnnotator.Diagnostic>>
        implements DumbAware {
    private static final int MAX_COMMANDS_PER_FILE = 200;

    @Override
    public @Nullable Input collectInformation(@NotNull PsiFile file) {
        if (!(file instanceof McfppFile)) return null;
        List<McfppCommandContext.StaticCommand> commands = McfppCommandContext.staticCommands(
                file.getViewProvider().getContents());
        if (commands.isEmpty()) return null;
        return new Input(file.getProject(), commands.size() <= MAX_COMMANDS_PER_FILE
                ? commands : commands.subList(0, MAX_COMMANDS_PER_FILE));
    }

    @Override
    public @NotNull List<Diagnostic> doAnnotate(Input input) {
        McfppCommandService service = McfppCommandService.getInstance(input.project());
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (McfppCommandContext.StaticCommand command : input.commands()) {
            ProgressManager.checkCanceled();
            McfppCommandService.CommandCheck check = service.check(command.text());
            if (check.syntaxError()) {
                diagnostics.add(new Diagnostic(command.range(), check.message()));
            }
        }
        return List.copyOf(diagnostics);
    }

    @Override
    public void apply(@NotNull PsiFile file, List<Diagnostic> diagnostics, @NotNull AnnotationHolder holder) {
        int length = file.getTextLength();
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.range().getEndOffset() > length) continue;
            holder.newAnnotation(HighlightSeverity.ERROR, diagnostic.message())
                    .range(diagnostic.range())
                    .create();
        }
    }

    public record Input(@NotNull Project project, @NotNull List<McfppCommandContext.StaticCommand> commands) {
    }

    public record Diagnostic(@NotNull TextRange range, @NotNull String message) {
    }
}
