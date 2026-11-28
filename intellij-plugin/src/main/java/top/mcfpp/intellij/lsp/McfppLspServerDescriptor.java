package top.mcfpp.intellij.lsp;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.tooling.McfppToolchain;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class McfppLspServerDescriptor extends ProjectWideLspServerDescriptor {
    @Override
    public @NotNull Class<? extends org.eclipse.lsp4j.services.LanguageServer> getLsp4jServerClass() {
        return McfppExtendedServer.class;
    }

    @Override
    public Object createInitializationOptions() {
        var javaPlugin = com.intellij.ide.plugins.PluginManagerCore.getPlugin(
                com.intellij.openapi.extensions.PluginId.getId("com.intellij.java"));
        if (javaPlugin == null || !javaPlugin.isEnabled() ||
                com.intellij.openapi.project.DumbService.isDumb(getProject())) {
            return null;
        }
        return com.intellij.openapi.application.ReadAction.compute(
                () -> top.mcfpp.intellij.mni.McfppMacroIndex.snapshot(getProject()));
    }

    McfppLspServerDescriptor(@NotNull Project project) {
        super(project, "MCFPP");
    }

    @Override
    public @NotNull com.intellij.platform.lsp.api.customization.LspCustomization getLspCustomization() {
        return new com.intellij.platform.lsp.api.customization.LspCustomization() {
            @Override
            public @NotNull com.intellij.platform.lsp.api.customization.LspHoverCustomizer getHoverCustomizer() {
                return com.intellij.platform.lsp.api.customization.LspHoverDisabled.INSTANCE;
            }

            @Override
            public @NotNull com.intellij.platform.lsp.api.customization.LspCompletionCustomizer getCompletionCustomizer() {
                return new McfppLspCompletionSupport();
            }

            @Override
            public @NotNull com.intellij.platform.lsp.api.customization.LspDiagnosticsCustomizer getDiagnosticsCustomizer() {
                return new McfppLspDiagnosticsSupport();
            }

            @Override
            public @NotNull com.intellij.platform.lsp.api.customization.LspSemanticTokensCustomizer getSemanticTokensCustomizer() {
                return new McfppLspSemanticTokensSupport();
            }

            @Override
            public @NotNull com.intellij.platform.lsp.api.customization.LspFormattingCustomizer getFormattingCustomizer() {
                return com.intellij.platform.lsp.api.customization.LspFormattingDisabled.INSTANCE;
            }
        };
    }

    @Override
    public boolean isSupportedFile(@NotNull VirtualFile file) {
        return "mcfpp".equalsIgnoreCase(file.getExtension());
    }

    @Override
    public @NotNull GeneralCommandLine createCommandLine() {
        Path javaExecutable;
        Path compilerJar = null;
        try {
            javaExecutable = McfppToolchain.javaExecutable(getProject());
            try {
                compilerJar = McfppToolchain.compiler(getProject());
            } catch (IOException ignored) {
                // Allow standalone server JAR to fall back to classpath bin.mclib if CLI JAR is unavailable.
            }
        } catch (IOException exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
        List<String> args = new ArrayList<>();
        args.add(javaExecutable.toString());
        args.add("-Xmx768m");
        if (compilerJar != null) {
            args.add("-Dmcfpp.stdlib.jar=" + compilerJar);
        }
        var sources = top.mcfpp.intellij.mni.McfppLibraryRootsProvider.localCompilerSourcePaths(getProject());
        if (!sources.isEmpty()) {
            args.add("-Dmcfpp.kotlin.source.roots=" + sources.stream().map(Path::toString)
                    .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator)));
        }
        String mavenRepository = System.getProperty("maven.repo.local");
        if (mavenRepository != null && !mavenRepository.isBlank()) {
            args.add("-Dmaven.repo.local=" + mavenRepository);
        }
        args.add("-jar");
        args.add(McfppLanguageServerResource.extract().toString());
        return new GeneralCommandLine(args);
    }
}
