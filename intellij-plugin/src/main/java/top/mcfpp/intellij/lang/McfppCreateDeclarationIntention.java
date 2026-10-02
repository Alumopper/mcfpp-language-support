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
    static @Nullable PsiElement unresolvedIdentifier(Project project, Editor editor, PsiElement element) {
        if (editor != null && element.getNode() != null &&
                element.getNode().getElementType() != McfppTokenTypes.IDENTIFIER) {
            int caret = editor.getCaretModel().getOffset();
            PsiElement previous = caret == 0 ? null : element.getContainingFile().findElementAt(caret - 1);
            if (previous != null && previous.getTextRange().getEndOffset() == caret && previous.getNode() != null &&
                    previous.getNode().getElementType() == McfppTokenTypes.IDENTIFIER) element = previous;
        }
        if (DumbService.isDumb(project) || !(element.getContainingFile() instanceof McfppFile) ||
                element.getNode() == null || element.getNode().getElementType() != McfppTokenTypes.IDENTIFIER ||
                !McfppNames.isIdentifier(element.getText())) return null;
        PsiReference[] references = element.getReferences();
        if (references.length == 0) return null;
        if (McfppUnresolvedReferenceInspection.isResolved(element) ||
                !McfppUnresolvedReferenceInspection.isUnqualified(element)) return null;
        if (!McfppFileModels.activeSource(element.getContainingFile()).regionMatches(
                element.getTextOffset(), element.getText(), 0, element.getTextLength())) return null;
        return element;
    }

    static void insertTopLevelDeclaration(
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull McfppFile file,
            @NotNull String declaration,
            int relativeCaretOffset
    ) {
        Document document = PsiDocumentManager.getInstance(project).getDocument(file);
        if (document == null) return;
        int offset = document.getTextLength();
        String prefix;
        if (offset == 0) prefix = "";
        else if (document.getCharsSequence().charAt(offset - 1) == '\n') prefix = "\n";
        else prefix = "\n\n";
        document.insertString(offset, prefix + declaration);
        int caretOffset = offset + prefix.length() + relativeCaretOffset;
        var caret = document.createRangeMarker(caretOffset, caretOffset);
        caret.setGreedyToRight(true);
        try {
            PsiDocumentManager.getInstance(project).commitDocument(document);
            com.intellij.psi.codeStyle.CodeStyleManager.getInstance(project)
                    .reformatText(file, offset + prefix.length(), document.getTextLength());
            PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(document);
            int adjusted = com.intellij.psi.codeStyle.CodeStyleManager.getInstance(project)
                    .adjustLineIndent(file, caret.getEndOffset());
            if (editor != null) {
                editor.getCaretModel().moveToOffset(adjusted);
                editor.getScrollingModel().scrollToCaret(ScrollType.MAKE_VISIBLE);
            }
        } finally {
            caret.dispose();
        }
    }
}
