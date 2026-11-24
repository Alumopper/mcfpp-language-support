package top.mcfpp.intellij.lang;

import com.intellij.psi.PsiElement;

public final class McfppReferenceNames {
    private McfppReferenceNames() {}

    public static boolean isName(PsiElement element) {
        if (element == null || element.getFirstChild() != null || element.getNode() == null ||
                !(element.getContainingFile() instanceof McfppFile)) return false;
        var type = element.getNode().getElementType();
        return type == McfppTokenTypes.IDENTIFIER ||
                ((type == McfppTokenTypes.KEYWORD || type == McfppTokenTypes.TYPE_KEYWORD) &&
                        element.textMatches("map") && isMember(element));
    }

    public static boolean isMember(PsiElement element) {
        CharSequence text = element.getContainingFile().getViewProvider().getContents();
        int offset = element.getTextOffset() - 1;
        while (offset >= 0 && Character.isWhitespace(text.charAt(offset))) offset--;
        return offset >= 0 && text.charAt(offset) == '.';
    }

    public static boolean isCall(PsiElement element) {
        if (!isName(element)) return false;
        var file = element.getContainingFile();
        if (McfppFileModels.get(file).declarationAt(element.getTextOffset()) != null) return false;
        CharSequence text = file.getViewProvider().getContents();
        int offset = element.getTextRange().getEndOffset();
        while (offset < text.length() && Character.isWhitespace(text.charAt(offset))) offset++;
        return offset < text.length() && (text.charAt(offset) == '(' || text.charAt(offset) == '{');
    }
}
