package top.mcfpp.intellij.lsp;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.LspServerSupportProvider;
import org.jetbrains.annotations.NotNull;

public final class McfppLspIntegrationProvider implements LspServerSupportProvider {
    @Override
    public void fileOpened(
            @NotNull Project project,
            @NotNull VirtualFile file,
            @NotNull LspServerStarter serverStarter
    ) {
        if (!ApplicationManager.getApplication().isUnitTestMode() &&
                "mcfpp".equalsIgnoreCase(file.getExtension())) {
            serverStarter.ensureServerStarted(new McfppLspServerDescriptor(project));
        }
    }
}
