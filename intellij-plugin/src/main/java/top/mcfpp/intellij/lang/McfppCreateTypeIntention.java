package top.mcfpp.intellij.lang;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

public final class McfppCreateTypeIntention extends McfppCreateDeclarationIntention {
    @Override
    public @NotNull String getFamilyName() {
        return "Create MCFPP data type";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        PsiElement identifier = unresolvedIdentifier(project, editor, element);
        if (identifier == null || !looksLikeType(identifier)) return false;
        setText("Create MCFPP data type '" + identifier.getText() + "'");
        return true;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        PsiElement identifier = unresolvedIdentifier(project, editor, element);
        if (identifier == null || !looksLikeType(identifier) || !(identifier.getContainingFile() instanceof McfppFile file)) return;
        String declaration = "data " + identifier.getText() + " {\n    \n}\n";
        insertTopLevelDeclaration(project, editor, file, declaration, declaration.indexOf("    \n") + 4);
    }

    private static boolean looksLikeType(PsiElement identifier) {
        String name = identifier.getText();
        if (!name.isEmpty() && Character.isUpperCase(name.codePointAt(0))) return true;
        CharSequence source = identifier.getContainingFile().getViewProvider().getContents();
        int cursor = identifier.getTextOffset() - 1;
        while (cursor >= 0 && Character.isWhitespace(source.charAt(cursor))) cursor--;
        int end = cursor + 1;
        while (cursor >= 0 && Character.isUnicodeIdentifierPart(source.charAt(cursor))) cursor--;
        return "as".contentEquals(source.subSequence(cursor + 1, end));
    }
}
