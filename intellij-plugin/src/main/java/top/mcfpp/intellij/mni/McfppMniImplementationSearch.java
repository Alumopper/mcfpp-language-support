package top.mcfpp.intellij.mni;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.searches.DefinitionsScopedSearch;
import com.intellij.util.Processor;
import com.intellij.util.QueryExecutor;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.lang.McfppLanguage;

/** Supplies JVM implementations for native MCFPP declarations to Go to Implementation. */
public final class McfppMniImplementationSearch implements QueryExecutor<PsiElement, DefinitionsScopedSearch.SearchParameters> {
    @Override
    public boolean execute(@NotNull DefinitionsScopedSearch.SearchParameters parameters,
                           @NotNull Processor<? super PsiElement> consumer) {
        var targets = ReadAction.compute(() -> {
            var declaration = parameters.getElement();
            return declaration.getLanguage() == McfppLanguage.INSTANCE
                    ? McfppMniLineMarkerProvider.findJavaTargets(declaration) : java.util.List.<PsiElement>of();
        });
        for (PsiElement target : targets) {
            if (!consumer.process(target.getNavigationElement())) return false;
        }
        return true;
    }
}
