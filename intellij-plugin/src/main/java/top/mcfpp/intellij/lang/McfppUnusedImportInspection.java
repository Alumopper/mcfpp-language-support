package top.mcfpp.intellij.lang;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Diagnose imports individually; Optimize Imports remains conservative about removing declarations. */
public final class McfppUnusedImportInspection extends LocalInspectionTool {
    @Override public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean onTheFly) {
        return new PsiElementVisitor() {
            @Override public void visitFile(@NotNull PsiFile file) {
                if (!(file instanceof McfppFile) || DumbService.isDumb(file.getProject())) return;
                String source = McfppFileModels.activeSource(file);
                List<McfppImportLayout.Entry> imports = McfppImportLayout.entries(source);
                if (imports.isEmpty()) return;
                var model = McfppFileModels.get(file);
                var tokens = McfppSourceTokens.lex(source).stream().filter(token -> !token.trivia()).toList();
                Set<McfppImport> used = new HashSet<>();
                boolean namespace = false;
                for (int i = 0; i < tokens.size(); i++) {
                    var token = tokens.get(i);
                    if (namespace && (i > 0 && source.substring(tokens.get(i - 1).end(), token.start()).contains("\n") ||
                            i > 0 && tokens.get(i - 1).text().equals(";"))) namespace = false;
                    if (token.text().equals("namespace")) namespace = true;
                    if (namespace || token.type() != McfppTokenTypes.IDENTIFIER ||
                            model.declarationAt(token.start()) != null ||
                            imports.stream().anyMatch(entry -> entry.start() <= token.start() && token.start() < entry.end())) continue;
                    String previous = i == 0 ? "" : tokens.get(i - 1).text();
                    String next = i + 1 == tokens.size() ? "" : tokens.get(i + 1).text();
                    if (previous.equals(".") || previous.equals(":")) continue;
                    PsiElement element = file.findElementAt(token.start());
                    if (element == null || McfppReferenceContributor.isNativeJavaTarget(element)) continue;
                    List<PsiElement> targets = McfppSymbolResolver.resolve(element);
                    for (var entry : imports) {
                        var imported = entry.value();
                        if (imported.isWildcard() && imported.alias() != null) {
                            if (imported.alias().equals(token.text()) && next.equals(":")) used.add(imported);
                        } else if (!next.equals(":") && imported.exposes(token.text()) &&
                                (targets.isEmpty() || targets.stream().anyMatch(target ->
                                        McfppFileModels.get(target.getContainingFile()).namespace().equals(imported.namespace()) &&
                                        imported.originalName(token.text()).equals(target instanceof PsiNamedElement named
                                                ? named.getName() : target.getText())))) {
                            used.add(imported);
                        }
                    }
                }
                // Interpolations have compiler-specific lookup rules; retain potential imports rather than fading them.
                var embedded = McfppSemanticTokens.analyzeDetailed(source).embeddedRanges();
                for (var entry : imports) {
                    if (embedded.stream().anyMatch(range -> entry.value().exposes(source.substring(range.startOffset(), range.endOffset())))) {
                        used.add(entry.value());
                    }
                    if (used.contains(entry.value())) continue;
                    holder.registerProblem(file, "Unused import", ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                            new TextRange(entry.start(), entry.end()), new RemoveImportFix(entry.value()));
                }
            }
        };
    }

    private record RemoveImportFix(McfppImport imported) implements LocalQuickFix {
        @Override public @NotNull String getFamilyName() { return "Remove unused import"; }
        @Override public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiFile file = descriptor.getPsiElement().getContainingFile();
            var document = PsiDocumentManager.getInstance(project).getDocument(file);
            if (document == null) return;
            TextRange problem = descriptor.getTextRangeInElement();
            if (problem == null) return;
            // Recheck the statement at its reported location, retaining attached comments and version directives.
            for (var entry : McfppImportLayout.entries(document.getText())) {
                if (entry.start() == problem.getStartOffset() && entry.value().equals(imported)) {
                    document.deleteString(entry.start(), entry.end());
                    PsiDocumentManager.getInstance(project).commitDocument(document);
                    return;
                }
            }
        }
    }
}
