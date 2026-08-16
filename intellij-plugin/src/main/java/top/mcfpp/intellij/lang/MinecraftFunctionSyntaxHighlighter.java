package top.mcfpp.intellij.lang;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

final class MinecraftFunctionSyntaxHighlighter extends SyntaxHighlighterBase {
    @Override
    public @NotNull Lexer getHighlightingLexer() {
        return new MinecraftFunctionLexer();
    }

    @Override
    public TextAttributesKey @NotNull [] getTokenHighlights(IElementType tokenType) {
        if (tokenType == MinecraftFunctionTokenTypes.COMMENT) return pack(McfppSyntaxHighlighter.COMMENT);
        if (tokenType == MinecraftFunctionTokenTypes.COMMAND) return pack(McfppSyntaxHighlighter.COMMAND);
        return TextAttributesKey.EMPTY_ARRAY;
    }
}
