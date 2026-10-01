package top.mcfpp.intellij.lang;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Error-tolerant view of a function call, including compile-time read-only arguments. */
record McfppCallContext(
        @NotNull PsiElement callee,
        @NotNull PsiElement openParenthesis,
        int currentArgument,
        @NotNull List<Argument> readOnlyArguments,
        @NotNull List<Argument> arguments
) {
    static @Nullable McfppCallContext atCaret(@NotNull PsiFile file, int offset) {
        if (file.getTextLength() == 0) return null;
        PsiElement cursor = file.findElementAt(Math.max(0, Math.min(offset - 1, file.getTextLength() - 1)));
        int nestedParentheses = 0;
        while (cursor != null) {
            IElementType type = type(cursor);
            if (type == McfppTokenTypes.RIGHT_PARENTHESIS) {
                nestedParentheses++;
            } else if (type == McfppTokenTypes.LEFT_PARENTHESIS) {
                if (nestedParentheses == 0) return create(file, cursor, offset);
                nestedParentheses--;
            }
            cursor = PsiTreeUtil.prevLeaf(cursor, true);
        }
        return null;
    }

    static @Nullable McfppCallContext fromCallee(@NotNull PsiElement callee) {
        PsiElement cursor = nextCodeLeaf(callee);
        if (cursor != null && "<".equals(cursor.getText())) {
            PsiElement close = matchingReadOnlyClose(cursor);
            cursor = close == null ? null : nextCodeLeaf(close);
        }
        if (cursor == null || type(cursor) != McfppTokenTypes.LEFT_PARENTHESIS) return null;
        return create(callee.getContainingFile(), cursor, cursor.getTextOffset() + 1);
    }

    private static @Nullable McfppCallContext create(PsiFile file, PsiElement open, int caretOffset) {
        PsiElement callee = previousCodeLeaf(open);
        PsiElement readOnlyOpen = null;
        PsiElement readOnlyClose = null;
        if (callee != null && ">".equals(callee.getText())) {
            readOnlyClose = callee;
            readOnlyOpen = findReadOnlyOpen(readOnlyClose);
            callee = readOnlyOpen == null ? null : previousCodeLeaf(readOnlyOpen);
        }
        if (callee == null || type(callee) != McfppTokenTypes.IDENTIFIER) return null;
        if (McfppFileModels.get(file).declarationAt(callee.getTextOffset()) != null) return null;
        return new McfppCallContext(
                callee,
                open,
                currentArgument(open, caretOffset),
                readOnlyOpen == null ? List.of() : arguments(file, readOnlyOpen, readOnlyClose),
                arguments(file, open, matchingParenthesisClose(open))
        );
    }

    int argumentCount() {
        return arguments.size();
    }

    private static @Nullable PsiElement findReadOnlyOpen(PsiElement closingAngle) {
        int scanned = 0;
        for (PsiElement cursor = PsiTreeUtil.prevLeaf(closingAngle, true);
             cursor != null && scanned++ < 512;
             cursor = PsiTreeUtil.prevLeaf(cursor, true)) {
            if (isTrivia(cursor) || !"<".equals(cursor.getText())) continue;
            PsiElement candidateClose = matchingReadOnlyClose(cursor);
            PsiElement candidateCallee = previousCodeLeaf(cursor);
            if (candidateClose != null && candidateClose.getTextOffset() == closingAngle.getTextOffset() &&
                    candidateCallee != null && type(candidateCallee) == McfppTokenTypes.IDENTIFIER) {
                return cursor;
            }
        }
        return null;
    }

    private static @Nullable PsiElement matchingReadOnlyClose(PsiElement openingAngle) {
        int depth = 1;
        int scanned = 0;
        for (PsiElement cursor = PsiTreeUtil.nextLeaf(openingAngle, true);
             cursor != null && scanned++ < 512;
             cursor = PsiTreeUtil.nextLeaf(cursor, true)) {
            if (isTrivia(cursor)) continue;
            if ("<".equals(cursor.getText())) {
                depth++;
            } else if (">".equals(cursor.getText())) {
                if (depth > 1) {
                    depth--;
                } else {
                    PsiElement after = nextCodeLeaf(cursor);
                    if (after != null && type(after) == McfppTokenTypes.LEFT_PARENTHESIS) return cursor;
                }
            }
            if (type(cursor) == McfppTokenTypes.SEMICOLON) return null;
        }
        return null;
    }

    private static @Nullable PsiElement matchingParenthesisClose(PsiElement open) {
        int depth = 0;
        for (PsiElement cursor = PsiTreeUtil.nextLeaf(open, true); cursor != null;
             cursor = PsiTreeUtil.nextLeaf(cursor, true)) {
            IElementType type = type(cursor);
            if (type == McfppTokenTypes.LEFT_PARENTHESIS) depth++;
            else if (type == McfppTokenTypes.RIGHT_PARENTHESIS) {
                if (depth == 0) return cursor;
                depth--;
            }
        }
        return null;
    }

    private static List<Argument> arguments(PsiFile file, PsiElement open, @Nullable PsiElement close) {
        if (close == null) return List.of();
        List<Argument> result = new ArrayList<>();
        int segmentStart = open.getTextRange().getEndOffset();
        int parentheses = 0;
        int brackets = 0;
        int braces = 0;
        int angles = 0;
        for (PsiElement cursor = PsiTreeUtil.nextLeaf(open, true);
             cursor != null && cursor.getTextOffset() < close.getTextOffset();
             cursor = PsiTreeUtil.nextLeaf(cursor, true)) {
            IElementType type = type(cursor);
            String text = cursor.getText();
            boolean topLevel = parentheses == 0 && brackets == 0 && braces == 0 && angles == 0;
            if (type == McfppTokenTypes.COMMA && topLevel) {
                addArgument(file, segmentStart, cursor.getTextOffset(), result);
                segmentStart = cursor.getTextRange().getEndOffset();
                continue;
            }
            if (type == McfppTokenTypes.LEFT_PARENTHESIS) parentheses++;
            else if (type == McfppTokenTypes.RIGHT_PARENTHESIS && parentheses > 0) parentheses--;
            else if (type == McfppTokenTypes.LEFT_BRACKET) brackets++;
            else if (type == McfppTokenTypes.RIGHT_BRACKET && brackets > 0) brackets--;
            else if (type == McfppTokenTypes.LEFT_BRACE) braces++;
            else if (type == McfppTokenTypes.RIGHT_BRACE && braces > 0) braces--;
            else if ("<".equals(text) && matchingReadOnlyClose(cursor) != null) angles++;
            else if (">".equals(text) && angles > 0) angles--;
        }
        addArgument(file, segmentStart, close.getTextOffset(), result);
        return List.copyOf(result);
    }

    private static void addArgument(PsiFile file, int start, int end, List<Argument> result) {
        CharSequence source = file.getViewProvider().getContents();
        while (start < end && Character.isWhitespace(source.charAt(start))) start++;
        while (end > start && Character.isWhitespace(source.charAt(end - 1))) end--;
        if (start < end) result.add(new Argument(source.subSequence(start, end).toString(), start, end));
    }

    private static int currentArgument(PsiElement open, int caretOffset) {
        int parentheses = 0;
        int brackets = 0;
        int braces = 0;
        int index = 0;
        for (PsiElement cursor = PsiTreeUtil.nextLeaf(open, true);
             cursor != null && cursor.getTextOffset() < caretOffset;
             cursor = PsiTreeUtil.nextLeaf(cursor, true)) {
            IElementType type = type(cursor);
            if (type == McfppTokenTypes.LEFT_PARENTHESIS) parentheses++;
            else if (type == McfppTokenTypes.RIGHT_PARENTHESIS) {
                if (parentheses == 0) break;
                parentheses--;
            } else if (type == McfppTokenTypes.LEFT_BRACKET) brackets++;
            else if (type == McfppTokenTypes.RIGHT_BRACKET && brackets > 0) brackets--;
            else if (type == McfppTokenTypes.LEFT_BRACE) braces++;
            else if (type == McfppTokenTypes.RIGHT_BRACE && braces > 0) braces--;
            else if (type == McfppTokenTypes.COMMA && parentheses == 0 && brackets == 0 && braces == 0) index++;
        }
        return index;
    }

    private static @Nullable PsiElement previousCodeLeaf(PsiElement element) {
        PsiElement cursor = PsiTreeUtil.prevLeaf(element, true);
        while (cursor != null && isTrivia(cursor)) cursor = PsiTreeUtil.prevLeaf(cursor, true);
        return cursor;
    }

    private static @Nullable PsiElement nextCodeLeaf(PsiElement element) {
        PsiElement cursor = PsiTreeUtil.nextLeaf(element, true);
        while (cursor != null && isTrivia(cursor)) cursor = PsiTreeUtil.nextLeaf(cursor, true);
        return cursor;
    }

    private static boolean isTrivia(PsiElement element) {
        IElementType type = type(element);
        return type == McfppTokenTypes.WHITE_SPACE || type == McfppTokenTypes.COMMENT ||
                type == McfppTokenTypes.DOC_COMMENT || type == McfppTokenTypes.VERSION_DIRECTIVE;
    }

    private static @Nullable IElementType type(PsiElement element) {
        return element.getNode() == null ? null : element.getNode().getElementType();
    }

    record Argument(@NotNull String text, int startOffset, int endOffset) {
    }
}
