package top.mcfpp.intellij.lang;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.SmartPointerManager;
import org.jetbrains.annotations.NotNull;

public final class McfppAddImportIntention extends McfppCreateDeclarationIntention {
    @Override public @NotNull String getFamilyName() { return "Import MCFPP symbol"; }
    @Override public boolean startInWriteAction() { return false; }

    @Override public boolean isAvailable(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        PsiElement identifier = unresolvedIdentifier(project, editor, element);
        if (identifier == null || !(identifier.getContainingFile() instanceof McfppFile file) ||
                !McfppUnresolvedReferenceInspection.isUnqualified(identifier)) return false;
        var candidates = McfppImportManager.candidates(file, identifier.getText());
        if (candidates.isEmpty()) return false;
        setText(candidates.size() == 1 ? "Import '" + candidates.getFirst().qualifiedName() + "'"
                : "Import MCFPP symbol '" + identifier.getText() + "'…");
        return true;
    }

    @Override public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        PsiElement identifier = unresolvedIdentifier(project, editor, element);
        if (identifier == null || !(identifier.getContainingFile() instanceof McfppFile file)) return;
        var candidates = McfppImportManager.candidates(file, identifier.getText());
        var pointer = SmartPointerManager.createPointer(identifier);
        java.util.function.Consumer<McfppImportManager.ImportCandidate> apply = candidate ->
                WriteCommandAction.runWriteCommandAction(project, getFamilyName(), null, () -> {
                    PsiElement current = pointer.getElement();
                    if (current != null) McfppImportQuickFix.apply(file, current, candidate);
                }, file);
        if (candidates.size() == 1) apply.accept(candidates.getFirst());
        else if (!candidates.isEmpty() && editor != null) {
            JBPopupFactory.getInstance().createPopupChooserBuilder(candidates.stream()
                            .map(McfppImportManager.ImportCandidate::qualifiedName).toList())
                    .setTitle("Import MCFPP symbol")
                    .setItemChosenCallback(name -> candidates.stream().filter(c -> c.qualifiedName().equals(name))
                            .findFirst().ifPresent(apply))
                    .createPopup().showInBestPositionFor(editor);
        }
    }
}
