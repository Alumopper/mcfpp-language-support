package top.mcfpp.intellij.lang;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;

import java.util.List;
import java.util.LinkedHashSet;

/** Declaration-only access for JVM annotation editors; resolution never runs compiler code. */
public final class McfppAnnotationTypeIndex {
    private McfppAnnotationTypeIndex() {}

    public static List<PsiElement> find(Project project, String name) {
        return McfppSymbolResolver.findTypes(project, name, true);
    }

    public static List<String> names(Project project) {
        return List.of(new McfppGoToTypeContributor().getNames(project, true));
    }

    public static String namespace(com.intellij.psi.PsiFile file) {
        return McfppNamespaceResolver.infer(file).namespace();
    }

    public static List<McfppFile> bindingFiles(PsiFile host, String provider, String method) {
        if (provider == null) return List.of();
        var result = new LinkedHashSet<McfppFile>();
        for (String key : List.of(McfppSymbolIndex.mniClassKey(provider), McfppSymbolIndex.mniMethodKey(provider + "." + method))) {
            result.addAll(bindingFiles(host, key));
        }
        return List.copyOf(result);
    }

    public static List<PsiElement> visibleTypes(McfppFile file, String name) {
        var model = McfppFileModels.get(file);
        var local = find(file.getProject(), name).stream().filter(target -> {
            var symbol = McfppFileModels.get(target.getContainingFile()).declarationAt(target.getTextOffset());
            return symbol != null && model.namespace().equals(symbol.namespace()) && symbol.owner() == null;
        }).toList();
        if (!local.isEmpty()) return local;
        var imported = new LinkedHashSet<PsiElement>();
        for (var declaration : model.imports()) {
            if (!declaration.exposes(name)) continue;
            for (var target : find(file.getProject(), declaration.originalName(name))) {
                var symbol = McfppFileModels.get(target.getContainingFile()).declarationAt(target.getTextOffset());
                if (symbol != null && declaration.namespace().equals(symbol.namespace()) && symbol.owner() == null)
                    imported.add(target);
            }
        }
        if (!imported.isEmpty()) return List.copyOf(imported);
        return find(file.getProject(), name).stream().filter(target -> {
            var symbol = McfppFileModels.get(target.getContainingFile()).declarationAt(target.getTextOffset());
            return symbol != null && McfppStandardLibrary.isImplicitNamespace(symbol.namespace()) && symbol.owner() == null;
        }).toList();
    }

    public static List<McfppFile> bindingFiles(PsiFile host, String key) {
        var project = host.getProject();
        if (com.intellij.openapi.project.DumbService.isDumb(project)) return List.of();
        var index = com.intellij.util.indexing.FileBasedIndex.getInstance();
        var scope = com.intellij.psi.search.GlobalSearchScope.allScope(project);
        var files = new LinkedHashSet<>(index.getContainingFiles(McfppSymbolIndex.NAME, key, scope));
        files.addAll(McfppExternalLibraryIndex.filesForName(project, key));
        var manager = com.intellij.psi.PsiManager.getInstance(project);
        return files.stream().map(manager::findFile).filter(McfppFile.class::isInstance).map(McfppFile.class::cast).toList();
    }
}
