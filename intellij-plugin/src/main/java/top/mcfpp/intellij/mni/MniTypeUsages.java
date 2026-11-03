package top.mcfpp.intellij.mni;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.*;
import com.intellij.psi.search.*;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.*;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.lang.*;

import java.util.HashSet;

/** Source-language declarations must also search JVM string-literal usage contexts. */
public final class MniTypeUsages implements QueryExecutor<PsiReference, ReferencesSearch.SearchParameters> {
    @Override public boolean execute(@NotNull ReferencesSearch.SearchParameters parameters,
                                     @NotNull Processor<? super PsiReference> consumer) {
        return ReadAction.compute(() -> {
            var target = parameters.getElementToSearch();
            if (!(target instanceof PsiNamedElement named) || named.getName() == null) return true;
            if (target instanceof McfppNamedElement) {
                var symbol = McfppFileModels.get(target.getContainingFile()).declarationAt(target.getTextOffset());
                if (symbol == null || !symbol.kind().isType()) return true;
            } else if (!(target instanceof MniTypeParameter)) return true;
            var seen = new HashSet<String>();
            Processor<PsiElement> process = root -> {
                for (var host : PsiTreeUtil.findChildrenOfType(root, PsiLanguageInjectionHost.class)) {
                    com.intellij.openapi.progress.ProgressManager.checkCanceled();
                    var context = MniTypeContext.from(host);
                    if (context == null || context.field == MniTypeContext.Field.GENERIC) continue;
                    for (var reference : context.references()) {
                        if (!reference.isReferenceTo(target)) continue;
                        String key = host.getContainingFile().getVirtualFile() + ":" + host.getTextOffset() + ":" + reference.getRangeInElement();
                        if (seen.add(key) && !consumer.process(reference)) return false;
                    }
                }
                return true;
            };
            SearchScope scope = parameters.getEffectiveSearchScope();
            if (scope instanceof LocalSearchScope local) {
                for (var element : local.getScope()) if (!process.process(element)) return false;
                return true;
            }
            // Index annotation names rather than decoded type names: Java Unicode escapes and Kotlin
            // string escapes can hide the target's spelling from the host language's word index.
            var files = new HashSet<PsiFile>();
            var global = scope instanceof GlobalSearchScope value ? value : GlobalSearchScope.projectScope(target.getProject());
            for (String annotation : java.util.List.of("MNIFunction", "MNIMacro", "MacroParam", "MNIOperator")) {
                if (!PsiSearchHelper.getInstance(target.getProject()).processAllFilesWithWord(annotation, global, file ->
                        !files.add(file) || process.process(file), true)) return false;
            }
            return true;
        });
    }
}
