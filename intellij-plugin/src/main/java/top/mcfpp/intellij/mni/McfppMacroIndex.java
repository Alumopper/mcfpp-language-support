package top.mcfpp.intellij.mni;

import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import top.mcfpp.intellij.lang.McfppFile;
import top.mcfpp.intellij.lang.McfppFileModels;
import top.mcfpp.intellij.lang.McfppFileType;
import top.mcfpp.language.MniSymbolSnapshot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Links explicit MCFPP native declarations ({@code func name(...) = Owner.method}) to
 * {@code @MNIMacro} provider methods. {@code injectedBy} no longer exposes macros.
 */
public final class McfppMacroIndex {
    public static final String ANNOTATION = "top.mcfpp.annotations.MNIMacro";
    private static final Key<CachedValue<List<Macro>>> CACHE = Key.create("mcfpp.macro.index");

    public record Macro(String namespace, String name, String returnType,
                        List<MniSymbolSnapshot.Parameter> parameters, PsiMethod method) {
        public String signature() {
            return "func " + name + "(" + parameters.stream()
                    .map(p -> p.name + ": " + p.typeName)
                    .collect(java.util.stream.Collectors.joining(", ")) + "): " + returnType;
        }
    }

    private McfppMacroIndex() {
    }

    public static List<Macro> all(Project project) {
        if (DumbService.isDumb(project)) return List.of();
        return CachedValuesManager.getManager(project).getCachedValue(project, CACHE, () ->
                CachedValueProvider.Result.create(collect(project),
                        PsiModificationTracker.MODIFICATION_COUNT,
                        ProjectRootModificationTracker.getInstance(project)), false);
    }

    private static List<Macro> collect(Project project) {
        List<Macro> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        PsiManager manager = PsiManager.getInstance(project);
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        for (VirtualFile virtual : FileTypeIndex.getFiles(McfppFileType.INSTANCE, GlobalSearchScope.projectScope(project))) {
            if (!com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).isInContent(virtual)) continue;
            PsiFile psiFile = manager.findFile(virtual);
            if (!(psiFile instanceof McfppFile file)) continue;
            String source = file.getText();
            if (!source.contains("=")) continue;
            String namespace = McfppFileModels.get(file).namespace();
            for (MniJavaTargetParser.Target target : MniJavaTargetParser.parse(source)) {
                if (target.kind() != MniJavaTargetParser.Kind.JAVA_METHOD) continue;
                Optional<MniFunctionSignature> parsed = MniFunctionSignature.from(source, target);
                if (parsed.isEmpty()) continue;
                MniFunctionSignature signature = parsed.get();
                PsiClass owner = facade.findClass(target.ownerQualifiedName(), scope);
                if (owner == null) continue;
                PsiMethod method = matchingMethod(owner, target, signature);
                if (method == null) continue;
                MniJavaAnnotation annotation = MniJavaAnnotation.from(method).orElse(null);
                if (annotation == null || !annotation.macro()) continue;
                List<MniSymbolSnapshot.Parameter> parameters = new ArrayList<>();
                for (int index = 0; index < signature.normalTypes().size(); index++) {
                    var parameter = new MniSymbolSnapshot.Parameter();
                    parameter.name = index < signature.normalNames().size()
                            ? signature.normalNames().get(index) : "arg" + (index + 1);
                    parameter.typeName = signature.normalTypes().get(index);
                    parameter.mode = index < annotation.parameterModes().size()
                            ? annotation.parameterModes().get(index) : "VALUE";
                    parameters.add(parameter);
                }
                String identity = namespace + ":" + signature.name() + parameters.stream()
                        .map(p -> p.typeName).toList();
                if (seen.add(identity)) {
                    result.add(new Macro(namespace, signature.name(), signature.returnType(),
                            List.copyOf(parameters), method));
                }
            }
        }
        return List.copyOf(result);
    }

    private static PsiMethod matchingMethod(PsiClass owner, MniJavaTargetParser.Target target,
                                             MniFunctionSignature signature) {
        for (PsiMethod method : owner.findMethodsByName(target.memberName(), false)) {
            if (!method.hasModifierProperty(PsiModifier.PUBLIC) ||
                    !method.hasModifierProperty(PsiModifier.STATIC)) continue;
            if (MniJavaAnnotation.from(method).filter(signature::parametersMatch).isPresent()) return method;
        }
        return null;
    }

    public static List<Macro> visible(PsiFile file, String name) {
        var model = McfppFileModels.get(file);
        return all(file.getProject()).stream().filter(m -> {
            if (m.namespace().equals(model.namespace()) && (name == null || m.name().equals(name))) return true;
            return model.imports().stream().anyMatch(i -> i.namespace().equals(m.namespace()) &&
                    (name == null ? i.isWildcard() || i.importedName().equals(m.name())
                            : i.exposes(name) && i.originalName(name).equals(m.name())));
        }).toList();
    }

    public static String qualifier(PsiFile file, int offset) {
        var match = Pattern.compile("([\\w.]+)\\s*:\\s*$")
                .matcher(file.getText().substring(0, Math.min(offset, file.getTextLength())));
        return match.find() ? match.group(1) : null;
    }

    public static List<Macro> visibleAt(PsiFile file, String name, int offset) {
        String qualifier = qualifier(file, offset);
        if (qualifier == null) return visible(file, name);
        var imports = McfppFileModels.get(file).imports();
        String namespace = imports.stream().filter(i -> i.isWildcard() && qualifier.equals(i.alias()))
                .map(i -> i.namespace()).findFirst().orElse(qualifier);
        return all(file.getProject()).stream()
                .filter(m -> namespace.equals(m.namespace()) && (name == null || name.equals(m.name())))
                .toList();
    }

    public static String visibleName(PsiFile file, Macro macro, int offset) {
        if (qualifier(file, offset) != null || macro.namespace().equals(McfppFileModels.get(file).namespace()))
            return macro.name();
        return McfppFileModels.get(file).imports().stream()
                .filter(i -> !i.isWildcard() && i.namespace().equals(macro.namespace()) &&
                        i.importedName().equals(macro.name()))
                .map(i -> i.alias() == null || i.alias().isEmpty() ? macro.name() : i.alias())
                .findFirst().orElse(macro.name());
    }

    public static MniSymbolSnapshot snapshot(Project project) {
        var snapshot = new MniSymbolSnapshot();
        snapshot.projectUri = project.getBasePath() == null ? "" : Path.of(project.getBasePath()).toUri().toString();
        snapshot.generation = PsiModificationTracker.getInstance(project).getModificationCount();
        List<MniSymbolSnapshot.Symbol> symbols = new ArrayList<>();
        for (var macro : all(project)) {
            var symbol = new MniSymbolSnapshot.Symbol();
            symbol.namespace = macro.namespace();
            symbol.name = macro.name();
            symbol.macro = true;
            symbol.typeName = macro.returnType();
            symbol.detail = macro.signature();
            symbol.parameters = macro.parameters();
            var file = macro.method().getContainingFile();
            symbol.uri = Path.of(file.getVirtualFile().getPath()).toUri().toString();
            var document = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(file);
            var identifier = macro.method().getNameIdentifier();
            if (document != null && identifier != null) {
                int offset = identifier.getTextOffset();
                symbol.line = document.getLineNumber(offset);
                symbol.character = offset - document.getLineStartOffset(symbol.line);
                symbol.endLine = symbol.line;
                symbol.endCharacter = symbol.character + identifier.getTextLength();
            }
            var doc = macro.method().getDocComment();
            if (doc != null) symbol.documentation = doc.getText();
            symbols.add(symbol);
        }
        snapshot.symbols = List.copyOf(symbols);
        return snapshot;
    }

    public static String constant(com.intellij.psi.PsiAnnotationMemberValue value) {
        if (!(value instanceof com.intellij.psi.PsiExpression expression)) return "";
        Object result = JavaPsiFacade.getInstance(value.getProject())
                .getConstantEvaluationHelper().computeConstantExpression(expression);
        return result instanceof String text ? text : "";
    }
}
