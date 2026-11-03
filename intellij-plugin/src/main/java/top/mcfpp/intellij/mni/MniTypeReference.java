package top.mcfpp.intellij.mni;

import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.lang.*;

import java.util.*;

public final class MniTypeReference extends PsiPolyVariantReferenceBase<PsiLanguageInjectionHost> {
    private final MniTypeContext context;
    private final MniTypeSyntax.Name name;

    MniTypeReference(MniTypeContext context, MniTypeSyntax.Name name) {
        super(context.host, context.range(name.identifierStart(), name.end()), true);
        this.context = context; this.name = name;
    }

    @Override public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
        if (name.namespace() == null) {
            var generic = context.generics().get(name.text());
            if (generic != null) return new ResolveResult[]{new PsiElementResolveResult(generic)};
        }
        List<PsiElement> targets = McfppAnnotationTypeIndex.find(myElement.getProject(), name.simpleName());
        if (name.namespace() != null) targets = targets.stream().filter(target -> symbol(target) != null &&
                name.namespace().equals(symbol(target).namespace())).toList();
        else {
            var method = context.method == null ? null : context.method.getJavaPsi();
            var owner = method == null ? null : method.getContainingClass();
            var bindingNamespaces = McfppAnnotationTypeIndex.bindingNamespaces(myElement.getContainingFile(),
                    owner == null ? null : owner.getQualifiedName(), method == null ? "" : method.getName());
            var bound = targets.stream().filter(target -> symbol(target) != null && bindingNamespaces.contains(symbol(target).namespace())).toList();
            String namespace = McfppAnnotationTypeIndex.namespace(myElement.getContainingFile());
            var local = targets.stream().filter(target -> symbol(target) != null && namespace.equals(symbol(target).namespace())).toList();
            if (!bound.isEmpty()) targets = bound;
            else if (!local.isEmpty()) targets = local;
            else {
                var standard = targets.stream().filter(target -> symbol(target) != null && symbol(target).namespace().equals("mcfpp.lang")).toList();
                if (!standard.isEmpty()) targets = standard;
            }
        }
        // Paired data/object declarations share a public name; type positions select the data declaration.
        var qualifiedDataNames = new HashSet<String>();
        for (var target : targets) if (symbol(target) != null && !objectDeclaration(symbol(target))) qualifiedDataNames.add(symbol(target).qualifiedName());
        targets = targets.stream().filter(target -> symbol(target) == null || !objectDeclaration(symbol(target)) ||
                !qualifiedDataNames.contains(symbol(target).qualifiedName())).toList();
        return targets.stream().map(PsiElementResolveResult::new).toArray(ResolveResult[]::new);
    }

    private static McfppSymbol symbol(PsiElement target) {
        return target.getContainingFile() instanceof McfppFile file ? McfppFileModels.get(file).declarationAt(target.getTextOffset()) : null;
    }

    private static boolean objectDeclaration(McfppSymbol symbol) {
        return symbol.signature().matches("(?s)^(?:final\\s+)?object\\s+data\\b.*");
    }

    @Override public boolean isReferenceTo(@NotNull PsiElement target) {
        var resolved = multiResolve(false);
        return resolved.length == 1 && myElement.getManager().areElementsEquivalent(resolved[0].getElement(), target);
    }

    @Override public @NotNull PsiElement handleElementRename(@NotNull String newName) {
        return ElementManipulators.handleContentChange(myElement, getRangeInElement(), newName);
    }
}
