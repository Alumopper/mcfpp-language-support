package top.mcfpp.intellij.lsp;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

final class McfppLspServerDescriptor extends ProjectWideLspServerDescriptor {
    McfppLspServerDescriptor(@NotNull Project project) {
        super(project, "MCFPP");
    }

    @Override
    public boolean isSupportedFile(@NotNull VirtualFile file) {
        return "mcfpp".equalsIgnoreCase(file.getExtension());
    }

    @Override
    public @NotNull GeneralCommandLine createCommandLine() {
        Path javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                SystemInfo.isWindows ? "java.exe" : "java"
        );
        return new GeneralCommandLine(
                javaExecutable.toString(),
                "-Xmx768m",
                "-jar",
                McfppLanguageServerResource.extract().toString()
        );
    }
}
