package top.mcfpp.intellij.lang;

import com.intellij.codeInsight.completion.InsertHandler;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.lookup.LookupElement;

final class McfppCompletionInsertHandler implements InsertHandler<LookupElement> {
    private final String name;
    private final boolean addImport;
    private final boolean callable;

    private McfppCompletionInsertHandler(String name, boolean addImport, boolean callable) {
        this.name = name;
        this.addImport = addImport;
        this.callable = callable;
    }

    static McfppCompletionInsertHandler projectSymbol(String name) {
        return new McfppCompletionInsertHandler(name, true, false);
    }

    static McfppCompletionInsertHandler symbol(McfppSymbol symbol) {
        return new McfppCompletionInsertHandler(symbol.name(), false, symbol.kind() == McfppSymbolKind.FUNCTION);
    }

    @Override
    public void handleInsert(InsertionContext context, LookupElement item) {
        if (callable || addImport && resolvesToFunction(context)) insertParentheses(context);
        if (addImport) McfppImportManager.autoImport(context, name);
    }

    private boolean resolvesToFunction(InsertionContext context) {
        context.commitDocument();
        return McfppSymbolResolver.findProjectSymbols(context.getProject(), name, true).stream()
                .map(element -> McfppFileModels.get(element.getContainingFile())
                        .declarationAt(element.getTextOffset()))
                .anyMatch(symbol -> symbol != null && symbol.kind() == McfppSymbolKind.FUNCTION);
    }

    private static void insertParentheses(InsertionContext context) {
        int tail = context.getTailOffset();
        CharSequence source = context.getDocument().getCharsSequence();
        int next = tail;
        while (next < source.length() && Character.isWhitespace(source.charAt(next))) next++;
        if (next < source.length() && source.charAt(next) == '(') return;
        context.getDocument().insertString(tail, "()");
        context.setTailOffset(tail + 2);
        context.getEditor().getCaretModel().moveToOffset(tail + 1);
        if (context.getCompletionChar() == '(') context.setAddCompletionChar(false);
    }
}
