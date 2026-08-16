package top.mcfpp.intellij.lang;

import com.intellij.application.options.CodeStyle;
import com.intellij.lang.Language;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.highlighter.HighlighterIterator;
import com.intellij.openapi.project.Project;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Computes Enter-key indentation from the previous meaningful line. Using the
 * editor highlighter keeps this operation proportional to one line and avoids
 * treating delimiters in strings, comments, or commands as code.
 */
public final class McfppLineIndentProvider implements LineIndentProvider {
    @Override
    public @Nullable String getLineIndent(
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull Language language,
            int offset
    ) {
        if (!isSuitableFor(language) || !(editor instanceof EditorEx editorEx)) return null;

        Document document = editor.getDocument();
        int safeOffset = Math.max(0, Math.min(offset, document.getTextLength()));
        int previousLine = document.getLineNumber(safeOffset) - 1;
        CharSequence text = document.getImmutableCharSequence();
        while (previousLine >= 0 && isBlank(document, text, previousLine)) previousLine--;
        if (previousLine < 0) return "";

        CommonCodeStyleSettings.IndentOptions options = CodeStyle.getIndentOptions(project, document);
        int lineStart = document.getLineStartOffset(previousLine);
        int lineEnd = document.getLineEndOffset(previousLine);
        int columns = leadingColumns(text, lineStart, lineEnd, Math.max(1, options.TAB_SIZE));

        DelimiterDepth depth = delimiterDepth(editorEx, lineStart, lineEnd);
        columns += depth.braces * Math.max(1, options.INDENT_SIZE);
        columns += depth.continuations * Math.max(1, options.CONTINUATION_INDENT_SIZE);
        return whitespace(columns, options);
    }

    @Override
    public boolean isSuitableFor(@NotNull Language language) {
        return language == McfppLanguage.INSTANCE;
    }

    private static boolean isBlank(Document document, CharSequence text, int line) {
        int start = document.getLineStartOffset(line);
        int end = document.getLineEndOffset(line);
        for (int offset = start; offset < end; offset++) {
            if (!Character.isWhitespace(text.charAt(offset))) return false;
        }
        return true;
    }

    private static int leadingColumns(CharSequence text, int start, int end, int tabSize) {
        int columns = 0;
        for (int offset = start; offset < end; offset++) {
            char character = text.charAt(offset);
            if (character == ' ') {
                columns++;
            } else if (character == '\t') {
                columns += tabSize - columns % tabSize;
            } else {
                break;
            }
        }
        return columns;
    }

    private static DelimiterDepth delimiterDepth(EditorEx editor, int lineStart, int lineEnd) {
        Deque<IElementType> openers = new ArrayDeque<>();
        HighlighterIterator iterator = editor.getHighlighter().createIterator(lineStart);
        while (!iterator.atEnd() && iterator.getStart() < lineEnd) {
            IElementType type = iterator.getTokenType();
            if (isOpener(type)) {
                openers.push(type);
            } else if (isCloser(type) && !openers.isEmpty() && matches(openers.peek(), type)) {
                openers.pop();
            }
            iterator.advance();
        }

        int braces = 0;
        int continuations = 0;
        for (IElementType opener : openers) {
            if (opener == McfppTokenTypes.LEFT_BRACE) braces++;
            else continuations++;
        }
        return new DelimiterDepth(braces, continuations);
    }

    private static boolean isOpener(IElementType type) {
        return type == McfppTokenTypes.LEFT_BRACE ||
                type == McfppTokenTypes.LEFT_PARENTHESIS ||
                type == McfppTokenTypes.LEFT_BRACKET;
    }

    private static boolean isCloser(IElementType type) {
        return type == McfppTokenTypes.RIGHT_BRACE ||
                type == McfppTokenTypes.RIGHT_PARENTHESIS ||
                type == McfppTokenTypes.RIGHT_BRACKET;
    }

    private static boolean matches(IElementType opener, IElementType closer) {
        return (opener == McfppTokenTypes.LEFT_BRACE && closer == McfppTokenTypes.RIGHT_BRACE) ||
                (opener == McfppTokenTypes.LEFT_PARENTHESIS && closer == McfppTokenTypes.RIGHT_PARENTHESIS) ||
                (opener == McfppTokenTypes.LEFT_BRACKET && closer == McfppTokenTypes.RIGHT_BRACKET);
    }

    private static String whitespace(int columns, CommonCodeStyleSettings.IndentOptions options) {
        if (columns <= 0) return "";
        if (!options.USE_TAB_CHARACTER) return " ".repeat(columns);

        int tabSize = Math.max(1, options.TAB_SIZE);
        int tabs = columns / tabSize;
        int spaces = columns % tabSize;
        return "\t".repeat(tabs) + " ".repeat(spaces);
    }

    private record DelimiterDepth(int braces, int continuations) {
    }
}
