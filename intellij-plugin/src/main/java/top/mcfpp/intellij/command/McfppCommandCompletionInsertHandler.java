package top.mcfpp.intellij.command;

import com.intellij.codeInsight.completion.InsertHandler;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.openapi.editor.Document;

/** Applies the exact structured replacement range returned by Datapack Sandbox. */
public final class McfppCommandCompletionInsertHandler implements InsertHandler<LookupElement> {
    private final String originalSource;
    private final int commandStart;
    private final int commandEnd;
    private final int originalCaret;
    private final String replacement;

    public McfppCommandCompletionInsertHandler(
            String originalSource,
            int commandStart,
            int commandEnd,
            int originalCaret,
            String replacement
    ) {
        this.originalSource = originalSource;
        this.commandStart = commandStart;
        this.commandEnd = commandEnd;
        this.originalCaret = originalCaret;
        this.replacement = replacement;
    }

    @Override
    public void handleInsert(InsertionContext context, LookupElement item) {
        Document document = context.getDocument();
        int defaultStart = context.getStartOffset();
        int defaultEnd = context.getTailOffset();
        if (defaultStart >= 0 && defaultStart <= originalCaret && originalCaret <= originalSource.length() &&
                defaultEnd >= defaultStart && defaultEnd <= document.getTextLength()) {
            document.replaceString(defaultStart, defaultEnd, originalSource.substring(defaultStart, originalCaret));
        }

        int start = Math.max(0, Math.min(commandStart, document.getTextLength()));
        int end = Math.max(start, Math.min(commandEnd, document.getTextLength()));
        document.replaceString(start, end, replacement);
        int tail = start + replacement.length();
        context.setTailOffset(tail);
        context.getEditor().getCaretModel().moveToOffset(tail);
        context.setAddCompletionChar(false);
    }
}
