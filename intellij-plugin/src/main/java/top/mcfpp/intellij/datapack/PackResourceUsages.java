package top.mcfpp.intellij.datapack;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.util.*;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.lang.McfppNamedElement;
import java.util.*;
import java.util.regex.Pattern;

/** Resource references contain punctuation and cannot rely on a language's identifier word index. */
public final class PackResourceUsages implements QueryExecutor<PsiReference, ReferencesSearch.SearchParameters> {
    @Override public boolean execute(@NotNull ReferencesSearch.SearchParameters parameters, @NotNull Processor<? super PsiReference> consumer) {
        return ReadAction.compute(() -> search(parameters, consumer));
    }
    private boolean search(ReferencesSearch.SearchParameters parameters, Processor<? super PsiReference> consumer) {
        var target = parameters.getElementToSearch();
        if (!(target instanceof PsiFile) && !(target instanceof McfppNamedElement)) return true;
        var index = PackResourceIndex.forFile(target.getContainingFile()); if (index == null) return true;
        var keys = new HashSet<top.mcfpp.api.resources.PackResources.Key>();
        index.definitions.forEach((key, targets) -> { if (targets.stream().anyMatch(t -> target.equals(t.element()))) keys.add(key); });
        if (keys.isEmpty()) return true;
        for (PsiFile file : index.files) {
            if (!com.intellij.psi.search.PsiSearchScopeUtil.isInScope(parameters.getEffectiveSearchScope(), file)) continue;
            for (var ref : index.references(file)) if (keys.contains(ref.key()) &&
                    !consumer.process(new PackResourceReference(file, new TextRange(ref.start(), ref.end()), ref))) return false;
        }
        if (target instanceof McfppNamedElement named) {
            // Only the quoted target in [entrypoints] changes; callers retain the public key.
            String text = index.configFile.getText(); int section = text.indexOf("[entrypoints]");
            if (section >= 0) {
                int next = text.indexOf('\n' + "[", section + 1); if (next < 0) next = text.length();
                var matcher = Pattern.compile("(?m)^\\s*\"[^\"]+\"\\s*=\\s*\"([^\"]+)\"").matcher(text);
                matcher.region(section + "[entrypoints]".length(), next);
                while (matcher.find()) {
                    String id = matcher.group(1);
                    if (!index.configuration.entrypoints().containsValue(id) || !id.endsWith(":" + named.getName())) continue;
                    boolean matches = keys.stream().anyMatch(k -> id.equals(index.configuration.entrypoints().get(k.id())));
                    if (!matches) continue;
                    int start = matcher.start(1) + id.indexOf(':') + 1;
                    var reference = new PsiReferenceBase<PsiElement>(index.configFile, new TextRange(start, matcher.end(1)), true) {
                        @Override public PsiElement resolve() { return target; }
                        @Override public PsiElement handleElementRename(@NotNull String newName) {
                            PackResourceReference.replace(myElement, getRangeInElement(), newName); return myElement;
                        }
                    };
                    if (!consumer.process(reference)) return false;
                }
            }
        }
        return true;
    }
}
