package top.mcfpp.intellij.lang;

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler;
import com.intellij.openapi.editor.highlighter.HighlighterIterator;

public final class McfppQuoteHandler extends SimpleTokenSetQuoteHandler {
    public McfppQuoteHandler() {
        super(McfppTokenTypes.STRING);
    }

    @Override
    public boolean isClosingQuote(HighlighterIterator iterator, int offset) {
        return iterator.getTokenType() == McfppTokenTypes.STRING && offset == iterator.getEnd() - 1 &&
                !isNonClosedLiteral(iterator, iterator.getDocument().getCharsSequence());
    }

    @Override
    protected boolean isNonClosedLiteral(HighlighterIterator iterator, CharSequence text) {
        int start = iterator.getStart();
        int last = iterator.getEnd() - 1;
        if (last <= start || text.charAt(last) != text.charAt(start)) return true;
        int backslashes = 0;
        for (int index = last - 1; index > start && text.charAt(index) == '\\'; index--) backslashes++;
        return backslashes % 2 != 0;
    }
}
