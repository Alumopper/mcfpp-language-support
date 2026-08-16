package top.mcfpp.intellij.lang;

import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;

final class MinecraftFunctionTokenTypes {
    static final IElementType COMMENT = new MinecraftFunctionTokenType("MCFUNCTION_COMMENT");
    static final IElementType COMMAND = new MinecraftFunctionTokenType("MCFUNCTION_COMMAND");
    static final IElementType WHITE_SPACE = TokenType.WHITE_SPACE;

    private MinecraftFunctionTokenTypes() {
    }
}
