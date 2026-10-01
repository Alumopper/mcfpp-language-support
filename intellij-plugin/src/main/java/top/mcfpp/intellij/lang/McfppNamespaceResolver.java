package top.mcfpp.intellij.lang;

import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.mcfpp.language.VersionPreprocessor;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class McfppNamespaceResolver {
    private static final Pattern NAMESPACE = Pattern.compile("\\\"namespace\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern SOURCE_PATH = Pattern.compile("\\\"sourcePath\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern VERSION = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");

    private McfppNamespaceResolver() {
    }

    static @NotNull Inference infer(@NotNull PsiFile file) {
        VirtualFile virtualFile = file.getVirtualFile();
        if (virtualFile == null) return Inference.NONE;
        return infer(virtualFile);
    }

    static @NotNull Inference infer(@NotNull VirtualFile virtualFile) {
        VirtualFile directory = virtualFile.getParent();
        for (int level = 0; directory != null && level < 10; level++, directory = directory.getParent()) {
            VirtualFile configuration = directory.findChild("mcfpp.json");
            if (configuration == null || configuration.isDirectory()) continue;
            try {
                String text = VfsUtilCore.loadText(configuration);
                String baseNamespace = match(NAMESPACE, text);
                String sourcePath = match(SOURCE_PATH, text);
                String version = match(VERSION, text);
                if (version == null) version = VersionPreprocessor.DEFAULT_VERSION;
                if (baseNamespace == null || sourcePath == null) return new Inference("", configuration, version);
                VirtualFile sourceRoot = directory.findFileByRelativePath(sourcePath.replace('\\', '/'));
                if (sourceRoot == null || !VfsUtilCore.isAncestor(sourceRoot, virtualFile, false)) {
                    return new Inference("", configuration, version);
                }
                String relativeDirectory = VfsUtilCore.getRelativePath(virtualFile.getParent(), sourceRoot, '/');
                String suffix = relativeDirectory == null || relativeDirectory.isEmpty()
                        ? ""
                        : "." + relativeDirectory.replace('/', '.');
                return new Inference(baseNamespace + suffix, configuration, version);
            } catch (IOException ignored) {
                return new Inference("", configuration, VersionPreprocessor.DEFAULT_VERSION);
            }
        }
        return Inference.NONE;
    }

    private static @Nullable String match(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    record Inference(@NotNull String namespace, @Nullable VirtualFile dependency, @NotNull String targetVersion) {
        static final Inference NONE = new Inference("", null, VersionPreprocessor.DEFAULT_VERSION);
    }
}
