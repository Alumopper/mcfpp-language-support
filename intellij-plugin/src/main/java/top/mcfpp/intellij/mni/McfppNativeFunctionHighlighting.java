package top.mcfpp.intellij.mni;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.util.Key;
import com.intellij.psi.*;
import com.intellij.psi.util.*;
import top.mcfpp.intellij.lang.*;

import java.util.*;

/** Classifies resolved declarations without executing provider code. */
public final class McfppNativeFunctionHighlighting {
    private static final Key<CachedValue<Map<Integer, TextAttributesKey>>> BINDINGS = Key.create("mcfpp.native.function.colors");
    private McfppNativeFunctionHighlighting() {}

    public static TextAttributesKey keyFor(PsiElement element) {
        if (!(element.getContainingFile() instanceof McfppFile file)) return null;
        TextAttributesKey own = bindings(file).get(element.getTextOffset());
        if (own != null) return own;
        Set<TextAttributesKey> keys = new HashSet<>();
        boolean ordinary = false;
        for (PsiReference reference : element.getReferences()) {
            List<PsiElement> targets = reference instanceof PsiPolyVariantReference poly
                    ? Arrays.stream(poly.multiResolve(false)).map(ResolveResult::getElement).filter(Objects::nonNull).toList()
                    : Optional.ofNullable(reference.resolve()).map(List::of).orElse(List.of());
            for (PsiElement target : targets) {
                TextAttributesKey key = null;
                if (target instanceof PsiMethod method) {
                    key = MniJavaAnnotation.from(method).map(annotation -> annotation.macro()
                            ? McfppSyntaxHighlighter.MACRO_FUNCTION : McfppSyntaxHighlighter.MNI_FUNCTION).orElse(null);
                } else if (target.getContainingFile() instanceof McfppFile declaration) {
                    key = bindings(declaration).get(target.getTextOffset());
                    if (key == null) ordinary = true;
                }
                if (key != null) keys.add(key);
            }
        }
        return !ordinary && keys.size() == 1 ? keys.iterator().next() : null;
    }

    private static Map<Integer, TextAttributesKey> bindings(McfppFile file) {
        return CachedValuesManager.getManager(file.getProject()).getCachedValue(file, BINDINGS, () -> {
            Map<Integer, TextAttributesKey> result = new HashMap<>();
            String source = McfppFileModels.activeSource(file);
            var model = McfppFileModels.get(file);
            for (var target : MniJavaTargetParser.parse(source)) {
                if (target.kind() != MniJavaTargetParser.Kind.JAVA_METHOD || target.segments().isEmpty()) continue;
                var signature = MniFunctionSignature.from(source, target);
                if (signature.isEmpty()) continue;
                var methods = MniJavaResolver.resolve(file.getProject(), target, target.segments().getLast(), signature);
                var keys = methods.stream().filter(PsiMethod.class::isInstance).map(PsiMethod.class::cast)
                        .map(MniJavaAnnotation::from).flatMap(Optional::stream)
                        .filter(signature.get()::parametersMatch)
                        .map(annotation -> annotation.macro() ? McfppSyntaxHighlighter.MACRO_FUNCTION : McfppSyntaxHighlighter.MNI_FUNCTION)
                        .distinct().toList();
                var key = keys.size() == 1 ? keys.getFirst() : McfppSyntaxHighlighter.MNI_FUNCTION;
                model.symbols().stream().filter(symbol -> symbol.kind() == McfppSymbolKind.FUNCTION &&
                        symbol.name().equals(signature.get().name()) && symbol.declarationStart() >= target.declarationStartOffset() && symbol.nameOffset() < target.startOffset())
                        .forEach(symbol -> result.put(symbol.nameOffset(), key));
                result.put(target.segments().getLast().startOffset(), key);
            }
            return CachedValueProvider.Result.create(Map.copyOf(result), PsiModificationTracker.MODIFICATION_COUNT,
                    ProjectRootModificationTracker.getInstance(file.getProject()));
        }, false);
    }
}
