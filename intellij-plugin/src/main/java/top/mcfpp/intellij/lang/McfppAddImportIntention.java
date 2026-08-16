package top.mcfpp.intellij.lang;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

public final class McfppAddImportIntention extends McfppCreateDeclarationIntention {
    @Override
    public @NotNull String getFamilyName() {
        return "Import MCFPP symbol";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        PsiElement identifier = unresolvedIdentifier(project, element);
        if (identifier == null || !(identifier.getContainingFile() instanceof McfppFile file)) return false;
        McfppImportManager.ImportCandidate candidate = McfppImportManager.uniqueCandidate(file, identifier.getText());
        if (candidate == null) return false;
        setText("Import '" + candidate.namespace() + ':' + candidate.name() + "'");
        return true;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        PsiElement identifier = unresolvedIdentifier(project, element);
        if (identifier == null || !(identifier.getContainingFile() instanceof McfppFile file)) return;
        McfppImportManager.ImportCandidate candidate = McfppImportManager.uniqueCandidate(file, identifier.getText());
        if (candidate == null) return;
        McfppImportManager.insertImport(editor.getDocument(), candidate.namespace(), candidate.name());
        PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument());
    }
}
