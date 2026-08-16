package top.mcfpp.intellij.lang;

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

abstract class McfppCreateDeclarationIntention extends PsiElementBaseIntentionAction {
    static @Nullable PsiElement unresolvedIdentifier(Project project, PsiElement element) {
        if (DumbService.isDumb(project) || !(element.getContainingFile() instanceof McfppFile) ||
                element.getNode() == null || element.getNode().getElementType() != McfppTokenTypes.IDENTIFIER ||
                !McfppNames.isIdentifier(element.getText())) return null;
        PsiReference[] references = element.getReferences();
        if (references.length == 0) return null;
        for (PsiReference reference : references) if (reference.resolve() != null) return null;
        return element;
    }

    static void insertTopLevelDeclaration(
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull McfppFile file,
            @NotNull String declaration,
            int relativeCaretOffset
    ) {
        Document document = editor.getDocument();
        int offset = document.getTextLength();
        String prefix;
        if (offset == 0) prefix = "";
        else if (document.getCharsSequence().charAt(offset - 1) == '\n') prefix = "\n";
        else prefix = "\n\n";
        document.insertString(offset, prefix + declaration);
        PsiDocumentManager.getInstance(project).commitDocument(document);
        editor.getCaretModel().moveToOffset(offset + prefix.length() + relativeCaretOffset);
        editor.getScrollingModel().scrollToCaret(ScrollType.MAKE_VISIBLE);
    }
}
