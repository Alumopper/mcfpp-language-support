package top.mcfpp.intellij.lang;

import com.intellij.codeInsight.completion.InsertHandler;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.openapi.editor.RangeMarker;

final class McfppCompletionInsertHandler implements InsertHandler<LookupElement> {
    private final McfppSymbol symbol;
    private final boolean unqualified;

    private McfppCompletionInsertHandler(McfppSymbol symbol, boolean unqualified) {
        this.symbol = symbol;
        this.unqualified = unqualified;
    }

    static McfppCompletionInsertHandler symbol(McfppSymbol symbol) {
        return new McfppCompletionInsertHandler(symbol, false);
    }

    static McfppCompletionInsertHandler projectSymbol(McfppSymbol symbol, boolean unqualified) {
        return new McfppCompletionInsertHandler(symbol, unqualified);
    }

    @Override
    public void handleInsert(InsertionContext context, LookupElement item) {
        context.commitDocument();
        if (unqualified && symbol.isTopLevel() && context.getFile() instanceof McfppFile file) {
            McfppImportManager.ImportCandidate candidate =
                    new McfppImportManager.ImportCandidate(symbol.namespace(), symbol.name());
            String reference = McfppImportManager.referenceText(file, candidate, context.getStartOffset());
            context.getDocument().replaceString(context.getStartOffset(), context.getTailOffset(), reference);
            context.setTailOffset(context.getStartOffset() + reference.length());
            context.getEditor().getCaretModel().moveToOffset(context.getTailOffset());
            McfppFileModel model = McfppFileModels.get(file);
            if (!reference.contains(":") && !symbol.namespace().isEmpty() &&
                    !symbol.namespace().equals(model.namespace()) &&
                    !McfppStandardLibrary.isImplicitNamespace(symbol.namespace()) &&
                    !model.importExposes(symbol.namespace(), symbol.name(), reference)) {
                // A marker keeps the tail correct when a header insertion shifts the selected reference.
                RangeMarker tail = context.getDocument().createRangeMarker(context.getTailOffset(), context.getTailOffset());
                tail.setGreedyToRight(true);
                try {
                    McfppImportManager.insertImport(context.getDocument(), symbol.namespace(), symbol.name());
                    context.setTailOffset(tail.getEndOffset());
                } finally {
                    tail.dispose();
                }
            }
        }
        if (symbol.kind() == McfppSymbolKind.FUNCTION) insertParentheses(context);
        context.commitDocument();
    }

    private static void insertParentheses(InsertionContext context) {
        int tail = context.getTailOffset();
        CharSequence source = context.getDocument().getCharsSequence();
        int next = tail;
        while (next < source.length() && Character.isWhitespace(source.charAt(next))) next++;
        if (next < source.length() && source.charAt(next) == '(') {
            if (context.getCompletionChar() == '(') context.setAddCompletionChar(false);
            return;
        }
        context.getDocument().insertString(tail, "()");
        context.setTailOffset(tail + 2);
        context.getEditor().getCaretModel().moveToOffset(tail + 1);
        if (context.getCompletionChar() == '(') context.setAddCompletionChar(false);
    }
}
