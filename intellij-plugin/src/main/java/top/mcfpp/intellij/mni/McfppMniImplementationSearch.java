package top.mcfpp.intellij.mni;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.searches.DefinitionsScopedSearch;
import com.intellij.util.Processor;
import com.intellij.util.QueryExecutor;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.lang.McfppLanguage;
import top.mcfpp.intellij.lang.McfppReferenceContributor;
import top.mcfpp.intellij.lang.McfppReferenceNames;

import java.util.LinkedHashSet;
import java.util.List;

/** Supplies JVM targets for MCFPP declarations and calls to Go to Implementation. */
public final class McfppMniImplementationSearch implements QueryExecutor<PsiElement, DefinitionsScopedSearch.SearchParameters> {
    @Override
    public boolean execute(@NotNull DefinitionsScopedSearch.SearchParameters parameters,
                           @NotNull Processor<? super PsiElement> consumer) {
        var source = parameters.getElement();
        var targets = ReadAction.compute(() -> localTargets(source));
        if (targets.isEmpty()) {
            var project = ReadAction.compute(() -> McfppReferenceNames.isCall(source) ? source.getProject() : null);
            var lookup = project == null ? null : project.getService(McfppRemoteImplementationLookup.class);
            if (lookup != null) targets = lookup.find(source);
        }
        for (PsiElement target : targets) {
            if (!ReadAction.compute(() -> !target.isValid() || consumer.process(target.getNavigationElement()))) return false;
        }
        return true;
    }

    private static List<PsiElement> localTargets(PsiElement element) {
        if (!element.isValid() || element.getLanguage() != McfppLanguage.INSTANCE) return List.of();
        var direct = McfppMniLineMarkerProvider.findJavaTargets(nameElement(element));
        if (!direct.isEmpty() || !McfppReferenceNames.isCall(element)) return direct;

        var targets = new LinkedHashSet<PsiElement>();
        resolve(McfppMacroReferenceContributor.referencesFor(element), targets);
        if (targets.isEmpty()) resolve(McfppReferenceContributor.referencesFor(element), targets);
        return List.copyOf(targets);
    }

    private static void resolve(PsiReference[] references, LinkedHashSet<PsiElement> targets) {
        for (PsiReference reference : references) {
            if (reference instanceof PsiPolyVariantReference poly) {
                for (var result : poly.multiResolve(false)) addTargets(result.getElement(), targets);
            } else {
                addTargets(reference.resolve(), targets);
            }
        }
    }

    private static void addTargets(PsiElement declaration, LinkedHashSet<PsiElement> targets) {
        if (declaration != null) targets.addAll(McfppMniLineMarkerProvider.findJavaTargets(nameElement(declaration)));
    }

    private static PsiElement nameElement(PsiElement element) {
        if (element instanceof PsiNameIdentifierOwner named && named.getNameIdentifier() != null) {
            return named.getNameIdentifier();
        }
        return element;
    }
}
