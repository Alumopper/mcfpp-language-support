package top.mcfpp.intellij.datapack;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.vfs.*;
import com.intellij.psi.*;
import com.intellij.psi.util.*;
import top.mcfpp.api.resources.PackResources;
import top.mcfpp.intellij.lang.*;
import top.mcfpp.language.ProjectConfiguration;
import java.nio.file.Path;
import java.util.*;

/** Source resources only: excluded build output cannot shadow definitions or produce duplicate suggestions. */
public final class PackResourceIndex {
    public record Target(PackResources.Key key, PsiFile file, int offset, boolean entrypoint) {
        public PsiElement element() {
            if (!entrypoint) return file;
            return PsiTreeUtil.getParentOfType(file.findElementAt(offset), McfppNamedElement.class, false);
        }
    }
    public final ProjectConfiguration configuration;
    public final PsiFile configFile;
    public final Map<PackResources.Key, List<Target>> definitions = new LinkedHashMap<>();
    public final List<PsiFile> files = new ArrayList<>();
    public final Set<String> namespaces = new HashSet<>();
    public final Set<PackResources.Key> generatedFunctions = new HashSet<>();
    private PackResourceIndex(ProjectConfiguration config, PsiFile file) { configuration = config; configFile = file; namespaces.add(config.namespace()); }

    public static PackResourceIndex forFile(PsiFile file) {
        if (file == null || file.getVirtualFile() == null) return null;
        VirtualFile dir = file.getVirtualFile().getParent();
        while (dir != null) {
            VirtualFile config = dir.findChild("mcfpp.toml");
            if (config == null) config = dir.findChild("mcfpp.json");
            if (config != null) {
                PsiFile psi = PsiManager.getInstance(file.getProject()).findFile(config);
                if (psi == null) return null;
                return CachedValuesManager.getCachedValue(psi, () -> CachedValueProvider.Result.create(
                        build(psi), PsiModificationTracker.MODIFICATION_COUNT, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS));
            }
            dir = dir.getParent();
        }
        return null;
    }
    private static PackResourceIndex build(PsiFile configFile) {
        ProjectConfiguration config;
        try { config = ProjectConfiguration.parse(Path.of(configFile.getVirtualFile().getPath()), configFile.getText()); }
        catch (RuntimeException ignored) { return null; }
        var index = new PackResourceIndex(config, configFile);
        index.scan(config.dataPath(), false); index.scan(config.assetsPath(), true);
        var functions = new LinkedHashMap<String, List<Target>>();
        VirtualFile source = index.directory(config.sourcePath());
        if (source != null) VfsUtilCore.visitChildrenRecursively(source, new VirtualFileVisitor<Void>() {
            @Override public boolean visitFile(VirtualFile virtual) {
                ProgressManager.checkCanceled();
                if (virtual.isDirectory() || !virtual.getName().endsWith(".mcfpp")) return true;
                PsiFile file = PsiManager.getInstance(configFile.getProject()).findFile(virtual);
                if (file == null) return true;
                index.files.add(file);
                var model = McfppFileModels.get(file);
                for (var symbol : model.symbols()) if (symbol.kind() == McfppSymbolKind.FUNCTION && symbol.isTopLevel()) {
                    String id = model.namespace() + ":" + symbol.name();
                    functions.computeIfAbsent(id, ignored -> new ArrayList<>()).add(new Target(new PackResources.Key("function", id), file, symbol.nameOffset(), true));
                    String afterName = file.getText().substring(symbol.nameOffset() + symbol.name().length(), symbol.headerEnd());
                    if (afterName.replaceAll("\\s", "").startsWith("()")) index.generatedFunctions.add(new PackResources.Key("function", model.namespace() + ":" + snake(symbol.name())));
                }
                return true;
            }
        });
        config.entrypoints().forEach((id, targetId) -> {
            if (!PackResources.validId(id)) return;
            var key = new PackResources.Key("function", id);
            for (var target : functions.getOrDefault(targetId, List.of()))
                index.add(new Target(key, target.file, target.offset, true));
        });
        index.generatedFunctions.add(new PackResources.Key("function", config.namespace() + ":load"));
        index.generatedFunctions.add(new PackResources.Key("function", config.namespace() + ":tick"));
        index.generatedFunctions.add(new PackResources.Key("function", config.namespace() + ":init"));
        return index;
    }
    private static String snake(String name) {
        var result = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) { if (i > 0) result.append('_'); result.append(Character.toLowerCase(c)); }
            else if (Character.isLowerCase(c) || Character.isDigit(c) || "_-.".indexOf(c) >= 0) result.append(c);
            else result.append('u').append(Integer.toHexString(c));
        }
        return result.toString();
    }
    private void scan(Path root, boolean assets) {
        VirtualFile directory = directory(root);
        if (directory == null) return;
        VfsUtilCore.visitChildrenRecursively(directory, new VirtualFileVisitor<Void>() {
            @Override public boolean visitFile(VirtualFile virtual) {
                ProgressManager.checkCanceled();
                if (virtual.isDirectory()) return true;
                String extension = virtual.getExtension();
                if (!Set.of("json", "mcfunction", "png", "ogg").contains(extension == null ? "" : extension)) return true;
                PsiFile file = PsiManager.getInstance(configFile.getProject()).findFile(virtual);
                if (file == null) return true;
                var key = PackResources.definition(root, Path.of(virtual.getPath()).toAbsolutePath().normalize(), assets);
                if (key != null) add(new Target(key, file, 0, false));
                if (assets && virtual.getName().equals("sounds.json")) {
                    String namespace = root.relativize(Path.of(virtual.getPath()).toAbsolutePath().normalize()).getName(0).toString();
                    PackResources.sounds(namespace, file.getText()).forEach((sound, offset) -> add(new Target(sound, file, offset, false)));
                }
                if ("json".equals(extension) || "mcfunction".equals(extension)) files.add(file);
                return true;
            }
        });
    }
    private void add(Target target) {
        definitions.computeIfAbsent(target.key, ignored -> new ArrayList<>()).add(target);
        namespaces.add(target.key.id().split(":", 2)[0]);
    }
    private VirtualFile directory(Path path) {
        if (path.startsWith(configuration.root())) return configFile.getVirtualFile().getParent()
                .findFileByRelativePath(configuration.root().relativize(path).toString().replace('\\', '/'));
        return LocalFileSystem.getInstance().findFileByNioFile(path);
    }
    public PackResources.Key owner(PsiFile file) {
        if (file.getVirtualFile() == null) return null;
        Path path = Path.of(file.getVirtualFile().getPath()).toAbsolutePath().normalize();
        var key = PackResources.definition(configuration.dataPath(), path, false);
        return key != null ? key : PackResources.definition(configuration.assetsPath(), path, true);
    }
    public List<PackResources.Reference> references(PsiFile file) {
        if (file.getName().equals("sounds.json")) return PackResources.soundReferences(file.getText());
        boolean mcfpp = file instanceof McfppFile;
        var owner = owner(file);
        if (!mcfpp && owner == null) return List.of();
        return PackResources.references(mcfpp ? McfppFileModels.activeSource(file) : file.getText(), owner, mcfpp);
    }
    public boolean missing(PackResources.Reference ref) {
        String ns = ref.key().id().split(":", 2)[0];
        return !ref.optional() && !ns.equals("minecraft") && namespaces.contains(ns) && !definitions.containsKey(ref.key());
    }
}
