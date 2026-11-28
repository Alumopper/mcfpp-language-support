package top.mcfpp.intellij.mni;

import com.intellij.psi.PsiElement;
import top.mcfpp.intellij.lang.McfppAnnotationTypeIndex;
import top.mcfpp.intellij.lang.McfppFileModels;
import top.mcfpp.intellij.lang.McfppSymbolIndex;
import top.mcfpp.intellij.lang.McfppSymbolKind;

import java.util.LinkedHashMap;
import java.util.Map;

/** A receiver macro takes its type parameters from its MCFPP declaration. */
final class MniMacroTypeScope {
    private MniMacroTypeScope() {}

    static Map<String, PsiElement> generics(MniTypeContext context) {
        if (context.method == null) return Map.of();
        var method = context.method.getJavaPsi();
        var owner = method.getContainingClass();
        if (owner == null || owner.getQualifiedName() == null) return Map.of();
        var annotation = MniJavaAnnotation.from(method).filter(MniJavaAnnotation::macro).orElse(null);
        if (annotation == null) return Map.of();

        String qualified = owner.getQualifiedName() + "." + method.getName();
        var result = new LinkedHashMap<String, PsiElement>();
        var files = McfppAnnotationTypeIndex.bindingFiles(context.host.getContainingFile(),
                McfppSymbolIndex.mniMethodKey(qualified));
        for (var file : files) {
            String source = McfppFileModels.activeSource(file);
            for (var target : MniJavaTargetParser.parse(source)) {
                if (target.kind() != MniJavaTargetParser.Kind.JAVA_METHOD || !qualified.equals(target.qualifiedName())) continue;
                var signature = MniFunctionSignature.from(source, target).orElse(null);
                if (signature == null || signature.receiverType().isEmpty() || !signature.parametersMatch(annotation) ||
                        !signature.returnType().equals(annotation.returnType())) continue;
                for (var symbol : McfppFileModels.get(file).symbols()) {
                    if (symbol.kind() == McfppSymbolKind.PARAMETER && symbol.nameOffset() > signature.nameOffset() &&
                            symbol.nameOffset() < target.startOffset() && signature.typeParameters().contains(symbol.name())) {
                        var parameter = file.findElementAt(symbol.nameOffset());
                        if (parameter != null) result.putIfAbsent(symbol.name(), parameter);
                    }
                }
                int start = signature.receiverStartOffset();
                int end = source.lastIndexOf('.', signature.nameOffset());
                if (end <= start) continue;
                var receiver = MniTypeSyntax.parse(source.substring(start, end), false, false, false);
                for (var name : receiver.names()) {
                    if (name.start() == 0 || name.builtin() || name.namespace() != null || name.applied() ||
                            result.containsKey(name.text()) || !McfppAnnotationTypeIndex.visibleTypes(file, name.text()).isEmpty()) continue;
                    var parameter = file.findElementAt(start + name.start());
                    if (parameter != null) result.put(name.text(), parameter);
                }
            }
        }
        return result;
    }
}
