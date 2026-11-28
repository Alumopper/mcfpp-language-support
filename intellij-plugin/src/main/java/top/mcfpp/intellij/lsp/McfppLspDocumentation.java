package top.mcfpp.intellij.lsp;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.LspServer;
import com.intellij.platform.lsp.api.LspServerManager;
import com.intellij.platform.lsp.api.LspServerState;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Position;
import org.jetbrains.annotations.Nullable;
import top.mcfpp.intellij.lang.McfppRemoteDocumentation;
import top.mcfpp.language.McfppDocumentationData;

import java.util.concurrent.CompletableFuture;

public final class McfppLspDocumentation implements McfppRemoteDocumentation {
    private final Project project;

    public McfppLspDocumentation(Project project) {
        this.project = project;
    }

    @Override
    public boolean isAvailable() {
        return server() != null;
    }

    @Override
    public @Nullable McfppDocumentationData fetch(VirtualFile file, int line, int column) {
        var application = ApplicationManager.getApplication();
        if (application.isDispatchThread() || application.isReadAccessAllowed() || !file.isValid()) return null;
        var server = server();
        if (server == null) return null;
        var params = new HoverParams(server.getDocumentIdentifier(file), new Position(line, column));
        return server.sendRequestSync(2_000, remote -> remote instanceof McfppExtendedServer mcfpp
                ? mcfpp.documentation(params) : CompletableFuture.completedFuture(null));
    }

    private @Nullable LspServer server() {
        if (project.isDisposed() || ApplicationManager.getApplication().isUnitTestMode()) return null;
        return LspServerManager.getInstance(project).getServersForProvider(McfppLspIntegrationProvider.class).stream()
                .filter(server -> server.getState() == LspServerState.Running).findFirst().orElse(null);
    }
}
