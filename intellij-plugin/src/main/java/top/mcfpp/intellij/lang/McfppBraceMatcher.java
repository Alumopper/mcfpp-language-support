package top.mcfpp.intellij.lang;

import com.intellij.lang.BracePair;
import com.intellij.lang.PairedBraceMatcher;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

public final class McfppBraceMatcher implements PairedBraceMatcher {
    private static final BracePair[] PAIRS = {
            new BracePair(McfppTokenTypes.LEFT_BRACE, McfppTokenTypes.RIGHT_BRACE, true),
            new BracePair(McfppTokenTypes.LEFT_BRACKET, McfppTokenTypes.RIGHT_BRACKET, false),
            new BracePair(McfppTokenTypes.LEFT_PARENTHESIS, McfppTokenTypes.RIGHT_PARENTHESIS, false)
    };

    @Override
    public BracePair @NotNull [] getPairs() {
        return PAIRS;
    }

    @Override
    public boolean isPairedBracesAllowedBeforeType(
            @NotNull IElementType leftBraceType,
            IElementType contextType
    ) {
        return contextType == null || contextType == McfppTokenTypes.WHITE_SPACE ||
                contextType == McfppTokenTypes.COMMA || contextType == McfppTokenTypes.SEMICOLON ||
                contextType == McfppTokenTypes.RIGHT_BRACE || contextType == McfppTokenTypes.RIGHT_BRACKET ||
                contextType == McfppTokenTypes.RIGHT_PARENTHESIS || contextType == McfppTokenTypes.COMMENT;
    }

    @Override
    public int getCodeConstructStart(PsiFile file, int openingBraceOffset) {
        return openingBraceOffset;
    }
}
