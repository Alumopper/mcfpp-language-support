package top.mcfpp.intellij.lang;

import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import top.mcfpp.syntax.DocumentationCommentSyntax;

final class McfppDocumentationEditor {
    private McfppDocumentationEditor() {}

    static boolean isComment(PsiElement element) {
        return element != null && element.getNode() != null && element.getNode().getElementType() == McfppTokenTypes.DOC_COMMENT;
    }

    static boolean complete(PsiFile file, int offset, CompletionResultSet result) {
        PsiElement comment = offset == 0 ? null : file.findElementAt(offset - 1);
        if (!isComment(comment)) return false;
        int start = comment.getTextRange().getStartOffset();
        int caret = offset - start;
        String text = comment.getText();
        int prefix = DocumentationCommentSyntax.tagPrefixStart(text, caret);
        if (prefix >= 0) {
            CompletionResultSet tags = result.withPrefixMatcher(text.substring(prefix, caret));
            for (String name : DocumentationCommentSyntax.TAG_NAMES) {
                tags.addElement(LookupElementBuilder.create(name).withPresentableText("@" + name)
                        .withTypeText("Documentation tag", true));
            }
        }
        result.stopHere();
        return true;
    }

    static void highlight(PsiElement comment, AnnotationHolder holder) {
        int start = comment.getTextRange().getStartOffset();
        for (var line : DocumentationCommentSyntax.lines(comment.getText(), true)) {
            if (line.tag() == null || !DocumentationCommentSyntax.TAG_NAMES.contains(line.tag())) continue;
            int at = start + line.contentStart();
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(new TextRange(at, at + 1 + line.tag().length()))
                    .textAttributes(McfppSyntaxHighlighter.DOC_TAG)
                    .create();
        }
    }
}
