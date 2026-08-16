package top.mcfpp.intellij.mni;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider;
import com.intellij.openapi.roots.SyntheticLibrary;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.navigation.ItemPresentation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.mcfpp.intellij.lang.McfppIcons;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.net.URL;
import javax.swing.Icon;

/** Exposes a local MCFPP compiler checkout as navigable MNI library sources. */
public final class McfppLibraryRootsProvider extends AdditionalLibraryRootsProvider {
    private static final String SOURCES_PROPERTY = "mcfpp.sources.path";
    private static final String HOME_ENVIRONMENT = "MCFPP_HOME";
    private static final String LIBRARY_NAME = "MCFPP standard library and MNI sources";

    @Override
    public @NotNull Collection<SyntheticLibrary> getAdditionalProjectLibraries(@NotNull Project project) {
        List<VirtualFile> roots = sourceRoots(project);
        return roots.isEmpty() ? List.of() : List.of(new McfppSourcesLibrary(roots));
    }

    @Override
    public @NotNull Collection<VirtualFile> getRootsToWatch(@NotNull Project project) {
        return virtualRoots(discoverSourceRoots(
                project.getBasePath(),
                System.getProperty(SOURCES_PROPERTY),
                System.getenv(HOME_ENVIRONMENT)
        ));
    }

    /** All compiler source roots available to the project, with the packaged standard library as a portable fallback. */
    public static @NotNull List<VirtualFile> sourceRoots(@NotNull Project project) {
        List<Path> discovered = discoverSourceRoots(
                project.getBasePath(),
                System.getProperty(SOURCES_PROPERTY),
                System.getenv(HOME_ENVIRONMENT)
        );
        List<VirtualFile> roots = new ArrayList<>(virtualRoots(discovered));
        if (discovered.stream().noneMatch(McfppLibraryRootsProvider::isStandardLibraryRoot)) {
            VirtualFile bundled = bundledStandardLibraryRoot();
            if (bundled != null) roots.add(bundled);
        }
        return List.copyOf(new LinkedHashSet<>(roots));
    }

    /** MCFPP-only roots used by the direct source fallback while IDEA's file indexes are unavailable or stale. */
    public static @NotNull List<VirtualFile> mcfppSourceRoots(@NotNull Project project) {
        List<Path> discovered = discoverSourceRoots(
                project.getBasePath(),
                System.getProperty(SOURCES_PROPERTY),
                System.getenv(HOME_ENVIRONMENT)
        );
        List<VirtualFile> roots = new ArrayList<>(virtualRoots(discovered.stream()
                .filter(path -> isMcfppSourceRoot(path) || !isConventionalJvmSourceRoot(path))
                .toList()));
        if (discovered.stream().noneMatch(McfppLibraryRootsProvider::isStandardLibraryRoot)) {
            VirtualFile bundled = bundledStandardLibraryRoot();
            if (bundled != null) roots.add(bundled);
        }
        return List.copyOf(new LinkedHashSet<>(roots));
    }

    static List<Path> discoverSourceRoots(String projectBasePath, String configuredSources, String mcfppHome) {
        Set<Path> roots = new LinkedHashSet<>();
        addConfiguredSourceLocations(roots, configuredSources);
        addConfiguredSourceLocation(roots, mcfppHome);

        if (projectBasePath != null && !projectBasePath.isBlank()) {
            Path ancestor = Path.of(projectBasePath).toAbsolutePath().normalize();
            for (int level = 0; ancestor != null && level < 8; level++, ancestor = ancestor.getParent()) {
                addConventionalSourceRoots(roots, ancestor);
                addConventionalSourceRoots(roots, ancestor.resolve("MCFPP"));
            }
        }
        return List.copyOf(roots);
    }

    private static void addConfiguredSourceLocations(Set<Path> roots, String configuredSources) {
        if (configuredSources == null || configuredSources.isBlank()) return;
        for (String path : configuredSources.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            addConfiguredSourceLocation(roots, path);
        }
    }

    private static void addConfiguredSourceLocation(Set<Path> roots, String path) {
        if (path == null || path.isBlank()) return;
        try {
            Path candidate = Path.of(path).toAbsolutePath().normalize();
            Path javaSources = candidate.resolve("src/main/java");
            Path kotlinSources = candidate.resolve("src/main/kotlin");
            Path mcfppSources = candidate.resolve("src/main/mcfpp");
            boolean conventionalLayout = Files.isDirectory(javaSources) || Files.isDirectory(kotlinSources) ||
                    Files.isDirectory(mcfppSources);
            addSourceRoot(roots, javaSources);
            addSourceRoot(roots, kotlinSources);
            addSourceRoot(roots, mcfppSources);
            if (!conventionalLayout) addSourceRoot(roots, candidate);
        } catch (RuntimeException ignored) {
            // An invalid optional override must not prevent the project from opening.
        }
    }

    private static void addConventionalSourceRoots(Set<Path> roots, Path compilerRoot) {
        addSourceRoot(roots, compilerRoot.resolve("src/main/java"));
        addSourceRoot(roots, compilerRoot.resolve("src/main/kotlin"));
        addSourceRoot(roots, compilerRoot.resolve("src/main/mcfpp"));
    }

    private static void addSourceRoot(Set<Path> roots, Path path) {
        if (Files.isDirectory(path)) roots.add(path.toAbsolutePath().normalize());
    }

    private static boolean isMcfppSourceRoot(Path path) {
        return path.endsWith(Path.of("src", "main", "mcfpp"));
    }

    private static boolean isStandardLibraryRoot(Path path) {
        if (!isMcfppSourceRoot(path)) return false;
        Path compilerRoot = path.getParent();
        if (compilerRoot != null) compilerRoot = compilerRoot.getParent();
        if (compilerRoot != null) compilerRoot = compilerRoot.getParent();
        if (compilerRoot == null) return false;
        Path configuration = compilerRoot.resolve("mcfpp.json");
        try {
            String text = Files.readString(configuration);
            return text.matches("(?s).*\\\"namespace\\\"\\s*:\\s*\\\"mcfpp\\\".*");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isConventionalJvmSourceRoot(Path path) {
        return path.endsWith(Path.of("src", "main", "java")) ||
                path.endsWith(Path.of("src", "main", "kotlin"));
    }

    private static @Nullable VirtualFile bundledStandardLibraryRoot() {
        URL resource = McfppLibraryRootsProvider.class.getResource("/stdlib/src/main/mcfpp");
        return resource == null ? null : VfsUtil.findFileByURL(resource);
    }

    private static List<VirtualFile> virtualRoots(List<Path> paths) {
        LocalFileSystem fileSystem = LocalFileSystem.getInstance();
        List<VirtualFile> roots = new ArrayList<>();
        for (Path path : paths) {
            // This provider is queried during early IDE startup. A synchronous refresh here can initialize the
            // event queue from a background thread; source changes are picked up through getRootsToWatch().
            VirtualFile root = fileSystem.findFileByNioFile(path);
            if (root != null && root.isDirectory()) roots.add(root);
        }
        return List.copyOf(roots);
    }

    private static final class McfppSourcesLibrary extends SyntheticLibrary implements ItemPresentation {
        private final List<VirtualFile> sourceRoots;

        private McfppSourcesLibrary(List<VirtualFile> sourceRoots) {
            super(LIBRARY_NAME, (directory, name, ignored1, ignored2, ignored3) ->
                    !directory && !isSourceFile(name));
            this.sourceRoots = sourceRoots;
        }

        private static boolean isSourceFile(String name) {
            return name.endsWith(".mcfpp") || name.endsWith(".java") || name.endsWith(".kt") ||
                    name.endsWith(".kts");
        }

        @Override
        public @NotNull Collection<VirtualFile> getSourceRoots() {
            return sourceRoots;
        }

        @Override
        public boolean isShowInExternalLibrariesNode() {
            return true;
        }

        @Override
        public @NotNull String getPresentableText() {
            return LIBRARY_NAME;
        }

        @Override
        public @Nullable Icon getIcon(boolean unused) {
            return McfppIcons.FILE;
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof McfppSourcesLibrary library && sourceRoots.equals(library.sourceRoots);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sourceRoots);
        }
    }
}
