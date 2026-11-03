package top.mcfpp.intellij.lang;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

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

    public static Set<String> bindingNamespaces(com.intellij.psi.PsiFile host, String provider, String method) {
        Set<String> result = new LinkedHashSet<>();
        if (provider == null || com.intellij.openapi.project.DumbService.isDumb(host.getProject())) return result;
        var index = com.intellij.util.indexing.FileBasedIndex.getInstance();
        var scope = com.intellij.psi.search.GlobalSearchScope.allScope(host.getProject());
        for (String key : List.of(McfppSymbolIndex.mniClassKey(provider), McfppSymbolIndex.mniMethodKey(provider + "." + method))) {
            for (var file : index.getContainingFiles(McfppSymbolIndex.NAME, key, scope)) {
                var psi = com.intellij.psi.PsiManager.getInstance(host.getProject()).findFile(file);
                if (psi instanceof McfppFile) result.add(McfppFileModels.get(psi).namespace());
            }
        }
        return result;
    }
}
