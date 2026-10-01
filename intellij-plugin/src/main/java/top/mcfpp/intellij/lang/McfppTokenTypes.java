package top.mcfpp.intellij.lang;

import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;

public final class McfppTokenTypes {
    public static final IElementType WHITE_SPACE = TokenType.WHITE_SPACE;
    public static final IElementType COMMENT = new McfppTokenType("COMMENT");
    public static final IElementType VERSION_DIRECTIVE = new McfppTokenType("VERSION_DIRECTIVE");
    public static final IElementType DOC_COMMENT = new McfppTokenType("DOC_COMMENT");
    public static final IElementType COMMAND = new McfppTokenType("COMMAND");
    public static final IElementType STRING = new McfppTokenType("STRING");
    public static final IElementType NUMBER = new McfppTokenType("NUMBER");
    public static final IElementType KEYWORD = new McfppTokenType("KEYWORD");
    public static final IElementType CONTROL_KEYWORD = new McfppTokenType("CONTROL_KEYWORD");
    public static final IElementType DECLARATION_KEYWORD = new McfppTokenType("DECLARATION_KEYWORD");
    public static final IElementType MODIFIER_KEYWORD = new McfppTokenType("MODIFIER_KEYWORD");
    public static final IElementType TYPE_KEYWORD = new McfppTokenType("TYPE_KEYWORD");
    public static final IElementType BOOLEAN_LITERAL = new McfppTokenType("BOOLEAN_LITERAL");
    public static final IElementType NULL_LITERAL = new McfppTokenType("NULL_LITERAL");
    public static final IElementType ANNOTATION = new McfppTokenType("ANNOTATION");
    public static final IElementType TARGET_SELECTOR = new McfppTokenType("TARGET_SELECTOR");
    public static final IElementType IDENTIFIER = new McfppTokenType("IDENTIFIER");
    public static final IElementType LEFT_BRACE = new McfppTokenType("LEFT_BRACE");
    public static final IElementType RIGHT_BRACE = new McfppTokenType("RIGHT_BRACE");
    public static final IElementType LEFT_BRACKET = new McfppTokenType("LEFT_BRACKET");
    public static final IElementType RIGHT_BRACKET = new McfppTokenType("RIGHT_BRACKET");
    public static final IElementType LEFT_PARENTHESIS = new McfppTokenType("LEFT_PARENTHESIS");
    public static final IElementType RIGHT_PARENTHESIS = new McfppTokenType("RIGHT_PARENTHESIS");
    public static final IElementType COMMA = new McfppTokenType("COMMA");
    public static final IElementType DOT = new McfppTokenType("DOT");
    public static final IElementType COLON = new McfppTokenType("COLON");
    public static final IElementType SEMICOLON = new McfppTokenType("SEMICOLON");
    public static final IElementType OPERATOR = new McfppTokenType("OPERATOR");
    public static final IElementType BAD_CHARACTER = TokenType.BAD_CHARACTER;

    private McfppTokenTypes() {
    }
}
