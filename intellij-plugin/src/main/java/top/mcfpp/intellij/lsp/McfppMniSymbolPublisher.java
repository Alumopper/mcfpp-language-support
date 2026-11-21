package top.mcfpp.intellij.lsp;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;

public final class McfppMniSymbolPublisher implements ProjectActivity {
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        project.getService(McfppMniSymbolPublisherService.class).start();
        return Unit.INSTANCE;
    }
}
