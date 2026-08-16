package top.mcfpp.intellij.lang;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class MinecraftFunctionLexer extends LexerBase {
    private CharSequence buffer = "";
    private int bufferEnd;
    private int tokenStart;
    private int tokenEnd;
    private IElementType tokenType;

    @Override
    public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        this.buffer = buffer;
        this.bufferEnd = endOffset;
        this.tokenStart = startOffset;
        locateToken();
    }

    @Override
    public int getState() {
        return 0;
    }

    @Override
    public @Nullable IElementType getTokenType() {
        return tokenType;
    }

    @Override
    public int getTokenStart() {
        return tokenStart;
    }

    @Override
    public int getTokenEnd() {
        return tokenEnd;
    }

    @Override
    public void advance() {
        tokenStart = tokenEnd;
        locateToken();
    }

    @Override
    public @NotNull CharSequence getBufferSequence() {
        return buffer;
    }

    @Override
    public int getBufferEnd() {
        return bufferEnd;
    }

    private void locateToken() {
        if (tokenStart >= bufferEnd) {
            tokenEnd = tokenStart;
            tokenType = null;
            return;
        }
        char first = buffer.charAt(tokenStart);
        if (Character.isWhitespace(first)) {
            tokenEnd = tokenStart + 1;
            while (tokenEnd < bufferEnd && Character.isWhitespace(buffer.charAt(tokenEnd))) tokenEnd++;
            tokenType = MinecraftFunctionTokenTypes.WHITE_SPACE;
            return;
        }
        tokenEnd = tokenStart;
        while (tokenEnd < bufferEnd && buffer.charAt(tokenEnd) != '\n' && buffer.charAt(tokenEnd) != '\r') tokenEnd++;
        tokenType = first == '#' ? MinecraftFunctionTokenTypes.COMMENT : MinecraftFunctionTokenTypes.COMMAND;
    }
}
