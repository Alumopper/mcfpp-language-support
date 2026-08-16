package top.mcfpp.intellij.lang;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.mni.McfppLibraryRootsProvider;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Small direct index for attached MCFPP sources. FileBasedIndex remains the fast path; this cache keeps standard-library
 * navigation and completion working while Gradle/project indexing is incomplete.
 */
final class McfppExternalLibraryIndex {
    private McfppExternalLibraryIndex() {
    }

    static @NotNull List<VirtualFile> filesForName(@NotNull Project project, @NotNull String name) {
        return snapshot(project).filesByName().getOrDefault(name, List.of());
    }

    static @NotNull Set<String> names(@NotNull Project project) {
        return snapshot(project).names();
    }

    static @NotNull Set<String> typeNames(@NotNull Project project) {
        return snapshot(project).typeNames();
    }

    private static Snapshot snapshot(Project project) {
        return CachedValuesManager.getManager(project).getCachedValue(project, () -> {
            List<VirtualFile> roots = McfppLibraryRootsProvider.mcfppSourceRoots(project);
            Object[] dependencies = new Object[roots.size() + 1];
            dependencies[0] = ProjectRootModificationTracker.getInstance(project);
            for (int index = 0; index < roots.size(); index++) {
                dependencies[index + 1] = roots.get(index);
            }
            return CachedValueProvider.Result.create(build(roots), dependencies);
        });
    }

    private static Snapshot build(List<VirtualFile> roots) {
        Map<String, LinkedHashSet<VirtualFile>> mutableFiles = new LinkedHashMap<>();
        Set<String> names = new LinkedHashSet<>();
        Set<String> typeNames = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();

        for (VirtualFile root : roots) {
            VfsUtilCore.iterateChildrenRecursively(
                    root,
                    file -> file.isDirectory() || "mcfpp".equalsIgnoreCase(file.getExtension()),
                    file -> {
                        ProgressManager.checkCanceled();
                        if (file.isDirectory() || !visited.add(file.getUrl())) return true;
                        indexFile(file, mutableFiles, names, typeNames);
                        return true;
                    }
            );
        }

        Map<String, List<VirtualFile>> files = new LinkedHashMap<>();
        mutableFiles.forEach((name, values) -> files.put(name, List.copyOf(values)));
        return new Snapshot(
                Collections.unmodifiableMap(files),
                Collections.unmodifiableSet(names),
                Collections.unmodifiableSet(typeNames)
        );
    }

    private static void indexFile(
            VirtualFile file,
            Map<String, LinkedHashSet<VirtualFile>> files,
            Set<String> names,
            Set<String> typeNames
    ) {
        try {
            McfppFileModel model = McfppFileModel.parse(VfsUtilCore.loadText(file));
            if (model.namespace().isEmpty()) {
                model = model.withNamespace(McfppNamespaceResolver.infer(file).namespace());
            }
            for (McfppSymbol symbol : model.projectSymbols()) {
                names.add(symbol.name());
                if (symbol.kind().isType()) typeNames.add(symbol.name());
                files.computeIfAbsent(symbol.name(), ignored -> new LinkedHashSet<>()).add(file);
            }
        } catch (IOException ignored) {
            // A single unreadable optional source file must not disable the rest of the library.
        }
    }

    private record Snapshot(
            @NotNull Map<String, List<VirtualFile>> filesByName,
            @NotNull Set<String> names,
            @NotNull Set<String> typeNames
    ) {
    }
}
