package top.mcfpp.intellij.lang;

import com.intellij.codeInspection.LocalQuickFixOnPsiElement;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

final class McfppImportQuickFix extends LocalQuickFixOnPsiElement {
    private final McfppImportManager.ImportCandidate candidate;

    McfppImportQuickFix(PsiElement element, McfppImportManager.ImportCandidate candidate) {
        super(element);
        this.candidate = candidate;
    }

    @Override public @NotNull String getText() { return "Import '" + candidate.qualifiedName() + "'"; }
    @Override public @NotNull String getFamilyName() { return "Import MCFPP symbol"; }
    @Override public void invoke(@NotNull Project project, @NotNull PsiFile file,
                                 @NotNull PsiElement start, @NotNull PsiElement end) {
        apply((McfppFile) file, start, candidate);
    }

    static void apply(McfppFile file, PsiElement identifier, McfppImportManager.ImportCandidate candidate) {
        if (!identifier.isValid() || !McfppUnresolvedReferenceInspection.isUnqualified(identifier) ||
                McfppUnresolvedReferenceInspection.isResolved(identifier) ||
                !McfppImportManager.candidates(file, identifier.getText()).contains(candidate)) return;
        PsiDocumentManager manager = PsiDocumentManager.getInstance(file.getProject());
        Document document = manager.getDocument(file);
        if (document == null) return;
        int offset = identifier.getTextOffset();
        String reference = McfppImportManager.referenceText(file, candidate, offset);
        document.replaceString(offset, offset + identifier.getTextLength(), reference);
        manager.commitDocument(document);
        if (!reference.contains(":") && !McfppFileModels.get(file)
                .importExposes(candidate.namespace(), candidate.name(), reference)) {
            McfppImportManager.insertImport(document, candidate.namespace(), candidate.name());
            manager.commitDocument(document);
        }
    }
}
