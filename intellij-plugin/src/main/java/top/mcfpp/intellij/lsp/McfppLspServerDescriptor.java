package top.mcfpp.intellij.lsp;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

final class McfppLspServerDescriptor extends ProjectWideLspServerDescriptor {
    @Override public @NotNull Class<? extends org.eclipse.lsp4j.services.LanguageServer> getLsp4jServerClass() {
        return McfppExtendedServer.class;
    }

    @Override public Object createInitializationOptions() {
        var javaPlugin = com.intellij.ide.plugins.PluginManagerCore.getPlugin(com.intellij.openapi.extensions.PluginId.getId("com.intellij.java"));
        if (javaPlugin == null || !javaPlugin.isEnabled() || com.intellij.openapi.project.DumbService.isDumb(getProject())) return null;
        return com.intellij.openapi.application.ReadAction.compute(() -> top.mcfpp.intellij.mni.McfppMacroIndex.snapshot(getProject()));
    }
    McfppLspServerDescriptor(@NotNull Project project) {
        super(project, "MCFPP");
    }

    @Override
    public @NotNull com.intellij.platform.lsp.api.customization.LspCustomization getLspCustomization() {
        return new com.intellij.platform.lsp.api.customization.LspCustomization() {
            @Override public @NotNull com.intellij.platform.lsp.api.customization.LspCompletionCustomizer
            getCompletionCustomizer() { return new McfppLspCompletionSupport(); }
            @Override public @NotNull com.intellij.platform.lsp.api.customization.LspDiagnosticsCustomizer
            getDiagnosticsCustomizer() { return new McfppLspDiagnosticsSupport(); }
            @Override public @NotNull com.intellij.platform.lsp.api.customization.LspFormattingCustomizer
            getFormattingCustomizer() { return com.intellij.platform.lsp.api.customization.LspFormattingDisabled.INSTANCE; }
        };
    }

    @Override
    public boolean isSupportedFile(@NotNull VirtualFile file) {
        return "mcfpp".equalsIgnoreCase(file.getExtension());
    }

    @Override
    public @NotNull GeneralCommandLine createCommandLine() {
        Path javaExecutable;
        try {
            javaExecutable = top.mcfpp.intellij.tooling.McfppToolchain.javaHome(getProject())
                    .resolve("bin").resolve(SystemInfo.isWindows ? "java.exe" : "java");
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
        return new GeneralCommandLine(
                javaExecutable.toString(),
                "-Xmx768m",
                "-jar",
                McfppLanguageServerResource.extract().toString()
        );
    }
}
