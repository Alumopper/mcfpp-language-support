package top.mcfpp.intellij.lang;

import com.intellij.codeInsight.daemon.HighlightDisplayKey;
import com.intellij.codeInspection.IntentionWrapper;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.DumbService;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import com.intellij.psi.*;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.language.VersionPreprocessor;
import java.util.*;

/** Conservative native diagnostics: uncertain syntax and compiler-only resolution stay with the LSP. */
public final class McfppUnresolvedReferenceInspection extends LocalInspectionTool {
    public static final String SHORT_NAME = "McfppUnresolvedReference";
    public static final String DIAGNOSTIC_CODE = "mcfpp.undefined-symbol";
    private static final Set<String> UNSUPPORTED_SYNTAX = Set.of("interface", "operator", "get", "set", "impl", "=>");

    @Override public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean onTheFly) {
        return new PsiElementVisitor() {
            @Override public void visitElement(@NotNull PsiElement element) {
                if (!canCheck(element) || isResolved(element)) return;
                List<LocalQuickFix> fixes = new ArrayList<>();
                McfppFile file = (McfppFile) element.getContainingFile();
                if (isUnqualified(element)) {
                    for (var candidate : McfppImportManager.candidates(file, element.getText())) {
                        fixes.add(new McfppImportQuickFix(element, candidate));
                    }
                    var createFunction = new McfppCreateFunctionIntention();
                    if (createFunction.isAvailable(element.getProject(), null, element)) {
                        fixes.add(new IntentionWrapper(createFunction));
                    }
                    var createType = new McfppCreateTypeIntention();
                    if (createType.isAvailable(element.getProject(), null, element)) {
                        fixes.add(new IntentionWrapper(createType));
                    }
                }
                holder.registerProblem(element, "Undefined symbol `" + element.getText() + "`",
                        ProblemHighlightType.LIKE_UNKNOWN_SYMBOL, fixes.toArray(LocalQuickFix[]::new));
            }
        };
    }

    static boolean isResolved(PsiElement element) {
        for (PsiReference reference : element.getReferences()) {
            if (reference instanceof PsiPolyVariantReference poly) {
                if (poly.multiResolve(false).length > 0) return true;
            } else if (reference.resolve() != null) return true;
        }
        return false;
    }

    public static boolean ownsDiagnostic(PsiElement element) {
        if (element == null || !canCheck(element) || isResolved(element) ||
                !com.intellij.codeInsight.daemon.impl.analysis.HighlightingLevelManager
                        .getInstance(element.getProject()).shouldInspect(element.getContainingFile())) return false;
        HighlightDisplayKey key = HighlightDisplayKey.find(SHORT_NAME);
        var profile = InspectionProjectProfileManager.getInstance(element.getProject()).getCurrentProfile();
        return key != null && profile.isToolEnabled(key, element) &&
                profile.getUnwrappedTool(SHORT_NAME, element) instanceof McfppUnresolvedReferenceInspection tool &&
                !tool.isSuppressedFor(element);
    }

    static boolean canCheck(PsiElement element) {
        if (!(element.getContainingFile() instanceof McfppFile file) || element.getNode() == null ||
                element.getNode().getElementType() != McfppTokenTypes.IDENTIFIER ||
                DumbService.isDumb(element.getProject()) || element.getReferences().length == 0) return false;
        if (!checkableOffsets(file).contains(element.getTextOffset())) return false;
        if (!isResolved(element) && !standardLibraryReady(file)) return false;
        // Resolve dependency readiness dynamically: indexing/library changes must not freeze cached eligibility.
        return McfppFileModels.get(file).imports().stream().noneMatch(imported -> imported.exposes(element.getText()) &&
                McfppSymbolResolver.findProjectSymbols(file.getProject(), imported.originalName(element.getText()), true)
                        .stream().noneMatch(target -> McfppFileModels.get(target.getContainingFile()).namespace()
                                .equals(imported.namespace())));
    }

    private static boolean standardLibraryReady(McfppFile file) {
        if (McfppExternalLibraryIndex.names(file.getProject()).contains("print") ||
                McfppSymbolResolver.findProjectSymbols(file.getProject(), "print", true).stream()
                        .anyMatch(target -> McfppStandardLibrary.isImplicitNamespace(
                                McfppFileModels.get(target.getContainingFile()).namespace()))) return true;
        var configuration = McfppNamespaceResolver.infer(file).dependency();
        if (configuration == null) return false;
        try {
            var config = com.google.gson.JsonParser.parseString(
                    com.intellij.openapi.vfs.VfsUtilCore.loadText(configuration)).getAsJsonObject();
            var arguments = config.get("compileArgs");
            if (arguments != null && arguments.isJsonArray()) {
                for (var argument : arguments.getAsJsonArray()) {
                    if (argument.isJsonPrimitive() && "-ignoreStdLib".equals(argument.getAsString())) return true;
                }
            }
        } catch (java.io.IOException | IllegalStateException | com.google.gson.JsonParseException ignored) {
            // Unavailable configuration is a dependency-readiness failure too.
        }
        return false;
    }

    private static Set<Integer> checkableOffsets(McfppFile file) {
        return CachedValuesManager.getCachedValue(file, () -> {
            var inference = McfppNamespaceResolver.infer(file);
            Set<Integer> offsets = new HashSet<>();
            try {
                String source = VersionPreprocessor.process(file.getText(), inference.targetVersion());
                List<McfppSourceTokens.Token> tokens = McfppSourceTokens.lex(source).stream()
                        .filter(token -> !token.trivia()).toList();
                if (nativeDependenciesAvailable(inference) && balanced(tokens)) collect(file, tokens, offsets);
            } catch (VersionPreprocessor.Error ignored) {
                // Invalid directives are diagnosed by the LSP; do not inspect both branches.
            }
            return inference.dependency() == null ? CachedValueProvider.Result.create(Set.copyOf(offsets), file)
                    : CachedValueProvider.Result.create(Set.copyOf(offsets), file, inference.dependency());
        });
    }

    private static void collect(McfppFile file, List<McfppSourceTokens.Token> tokens, Set<Integer> offsets) {
        McfppFileModel model = McfppFileModels.get(file);
        int annotationDepth = 0;
        int selectorDepth = 0;
        boolean header = false;
        for (int i = 0; i < tokens.size(); i++) {
            var token = tokens.get(i);
            String previous = i == 0 ? "" : tokens.get(i - 1).text();
            String next = i + 1 == tokens.size() ? "" : tokens.get(i + 1).text();
            if (header && (i > 0 && file.getText().substring(tokens.get(i - 1).end(), token.start()).contains("\n") ||
                    previous.equals(";"))) header = false;
            if (token.text().equals("import") || token.text().equals("namespace")) header = true;
            if ((previous.startsWith("@") && !previous.equals("@")) && (token.text().equals("<") || token.text().equals("("))) {
                annotationDepth = 1;
                continue;
            }
            if (annotationDepth > 0) {
                if (token.text().equals("<") || token.text().equals("(")) annotationDepth++;
                if (token.text().equals(">") || token.text().equals(")")) annotationDepth--;
                continue;
            }
            if (i > 0 && tokens.get(i - 1).type() == McfppTokenTypes.TARGET_SELECTOR && token.text().equals("[")) {
                selectorDepth = 1;
                continue;
            }
            if (selectorDepth > 0) {
                if (token.text().equals("[")) selectorDepth++;
                if (token.text().equals("]")) selectorDepth--;
                continue;
            }
            if (header || token.type() != McfppTokenTypes.IDENTIFIER || model.declarationAt(token.start()) != null ||
                    next.equals(":") || next.equals(".") || previous.equals("::") || previous.equals("@") ||
                    McfppReferenceContributor.isNativeJavaTarget(file.findElementAt(token.start()))) continue;
            // The tolerant model does not fully understand generic/type headers or structured literal keys.
            if (model.symbols().stream().anyMatch(symbol ->
                    (symbol.kind().isType() || symbol.kind() == McfppSymbolKind.FUNCTION ||
                            symbol.kind() == McfppSymbolKind.CONSTRUCTOR) &&
                            symbol.declarationStart() <= token.start() && token.start() < symbol.headerEnd())) continue;
            if (model.symbols().stream().anyMatch(symbol -> symbol.contains(token.start()) &&
                    ((symbol.kind().isType() && (symbol.signature().contains("<") || symbol.signature().contains(":"))) ||
                            (symbol.kind() == McfppSymbolKind.FUNCTION &&
                                    (symbol.signature().contains("<") || symbol.signature().contains(".")))))) continue;
            // Member inheritance, inferred receivers and compiler-only libraries remain LSP-owned.
            if (previous.equals(".") || previous.equals(":")) continue;
            if (next.isEmpty() || next.equals("as") || next.equals("from")) continue;
            offsets.add(token.start());
        }
    }

    private static boolean nativeDependenciesAvailable(McfppNamespaceResolver.Inference inference) {
        if (inference.dependency() == null) return true;
        try {
            var config = com.google.gson.JsonParser.parseString(
                    com.intellij.openapi.vfs.VfsUtilCore.loadText(inference.dependency())).getAsJsonObject();
            // The native source index cannot establish what declarations compiler archives contribute.
            for (String key : List.of("jar", "jars", "include", "includes")) {
                var value = config.get(key);
                if (value != null && !value.isJsonNull() &&
                        !(value.isJsonArray() && value.getAsJsonArray().isEmpty()) &&
                        !(value.isJsonPrimitive() && value.getAsString().isBlank())) return false;
            }
            return true;
        } catch (java.io.IOException | IllegalStateException | com.google.gson.JsonParseException ignored) {
            return false;
        }
    }

    private static boolean balanced(List<McfppSourceTokens.Token> tokens) {
        Deque<String> stack = new ArrayDeque<>();
        for (var token : tokens) {
            String value = token.text();
            if (value.equals("(") || value.equals("[") || value.equals("{")) stack.push(value);
            if (value.equals(")") || value.equals("]") || value.equals("}")) {
                String open = value.equals(")") ? "(" : value.equals("]") ? "[" : "{";
                if (stack.isEmpty() || !stack.pop().equals(open)) return false;
            }
            if (token.type() == McfppTokenTypes.BAD_CHARACTER ||
                    UNSUPPORTED_SYNTAX.contains(value)) return false;
        }
        return stack.isEmpty();
    }

    static boolean isUnqualified(PsiElement element) {
        var previous = com.intellij.psi.util.PsiTreeUtil.prevVisibleLeaf(element);
        return previous == null || (!previous.getText().equals(".") && !previous.getText().equals(":"));
    }
}
