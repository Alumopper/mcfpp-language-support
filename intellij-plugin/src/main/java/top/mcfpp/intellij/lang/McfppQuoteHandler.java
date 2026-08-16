package top.mcfpp.intellij.lang;

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler;

public final class McfppQuoteHandler extends SimpleTokenSetQuoteHandler {
    public McfppQuoteHandler() {
        super(McfppTokenTypes.STRING);
    }
}
