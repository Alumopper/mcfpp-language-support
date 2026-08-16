package top.mcfpp.intellij.lang;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

import static com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey;

public final class McfppSyntaxHighlighter extends SyntaxHighlighterBase {
    public static final TextAttributesKey KEYWORD = createTextAttributesKey(
            "MCFPP_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);
    public static final TextAttributesKey CONTROL_KEYWORD = createTextAttributesKey(
            "MCFPP_CONTROL_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);
    public static final TextAttributesKey DECLARATION_KEYWORD = createTextAttributesKey(
            "MCFPP_DECLARATION_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);
    public static final TextAttributesKey MODIFIER_KEYWORD = createTextAttributesKey(
            "MCFPP_MODIFIER_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);
    public static final TextAttributesKey TYPE_KEYWORD = createTextAttributesKey(
            "MCFPP_TYPE_KEYWORD", DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL);
    public static final TextAttributesKey IDENTIFIER = createTextAttributesKey(
            "MCFPP_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER);
    public static final TextAttributesKey STRING = createTextAttributesKey(
            "MCFPP_STRING", DefaultLanguageHighlighterColors.STRING);
    public static final TextAttributesKey NUMBER = createTextAttributesKey(
            "MCFPP_NUMBER", DefaultLanguageHighlighterColors.NUMBER);
    public static final TextAttributesKey COMMENT = createTextAttributesKey(
            "MCFPP_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT);
    public static final TextAttributesKey DOC_COMMENT = createTextAttributesKey(
            "MCFPP_DOC_COMMENT", DefaultLanguageHighlighterColors.DOC_COMMENT);
    public static final TextAttributesKey COMMAND = createTextAttributesKey(
            "MCFPP_COMMAND", DefaultLanguageHighlighterColors.METADATA);
    public static final TextAttributesKey COMMAND_ROOT = createTextAttributesKey(
            "MCFPP_COMMAND_ROOT", DefaultLanguageHighlighterColors.STATIC_METHOD);
    public static final TextAttributesKey COMMAND_KEYWORD = createTextAttributesKey(
            "MCFPP_COMMAND_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);
    public static final TextAttributesKey COMMAND_RESOURCE = createTextAttributesKey(
            "MCFPP_COMMAND_RESOURCE", DefaultLanguageHighlighterColors.STRING);
    public static final TextAttributesKey COMMAND_PROPERTY = createTextAttributesKey(
            "MCFPP_COMMAND_PROPERTY", DefaultLanguageHighlighterColors.INSTANCE_FIELD);
    public static final TextAttributesKey COMMAND_ARGUMENT = createTextAttributesKey(
            "MCFPP_COMMAND_ARGUMENT", DefaultLanguageHighlighterColors.PARAMETER);
    public static final TextAttributesKey COMMAND_COORDINATE = createTextAttributesKey(
            "MCFPP_COMMAND_COORDINATE", DefaultLanguageHighlighterColors.NUMBER);
    public static final TextAttributesKey COMMAND_SELECTOR_KEY = createTextAttributesKey(
            "MCFPP_COMMAND_SELECTOR_KEY", DefaultLanguageHighlighterColors.INSTANCE_FIELD);
    public static final TextAttributesKey COMMAND_SELECTOR_VALUE = createTextAttributesKey(
            "MCFPP_COMMAND_SELECTOR_VALUE", DefaultLanguageHighlighterColors.STATIC_FIELD);
    public static final TextAttributesKey COMMAND_MACRO = createTextAttributesKey(
            "MCFPP_COMMAND_MACRO", DefaultLanguageHighlighterColors.METADATA);
    public static final TextAttributesKey ANNOTATION = createTextAttributesKey(
            "MCFPP_ANNOTATION", DefaultLanguageHighlighterColors.METADATA);
    public static final TextAttributesKey TARGET_SELECTOR = createTextAttributesKey(
            "MCFPP_TARGET_SELECTOR", DefaultLanguageHighlighterColors.CONSTANT);
    public static final TextAttributesKey BOOLEAN = createTextAttributesKey(
            "MCFPP_BOOLEAN", DefaultLanguageHighlighterColors.KEYWORD);
    public static final TextAttributesKey NULL = createTextAttributesKey(
            "MCFPP_NULL", DefaultLanguageHighlighterColors.KEYWORD);
    public static final TextAttributesKey FUNCTION_DECLARATION = createTextAttributesKey(
            "MCFPP_FUNCTION_DECLARATION", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION);
    public static final TextAttributesKey FUNCTION_CALL = createTextAttributesKey(
            "MCFPP_FUNCTION_CALL", DefaultLanguageHighlighterColors.FUNCTION_CALL);
    public static final TextAttributesKey TYPE_NAME = createTextAttributesKey(
            "MCFPP_TYPE_NAME", DefaultLanguageHighlighterColors.CLASS_NAME);
    public static final TextAttributesKey VARIABLE = createTextAttributesKey(
            "MCFPP_VARIABLE", DefaultLanguageHighlighterColors.LOCAL_VARIABLE);
    public static final TextAttributesKey PARAMETER = createTextAttributesKey(
            "MCFPP_PARAMETER", DefaultLanguageHighlighterColors.PARAMETER);
    public static final TextAttributesKey FIELD = createTextAttributesKey(
            "MCFPP_FIELD", DefaultLanguageHighlighterColors.INSTANCE_FIELD);
    public static final TextAttributesKey ENUM_MEMBER = createTextAttributesKey(
            "MCFPP_ENUM_MEMBER", DefaultLanguageHighlighterColors.STATIC_FIELD);
    public static final TextAttributesKey NAMESPACE = createTextAttributesKey(
            "MCFPP_NAMESPACE", DefaultLanguageHighlighterColors.CLASS_REFERENCE);
    public static final TextAttributesKey BRACES = createTextAttributesKey(
            "MCFPP_BRACES", DefaultLanguageHighlighterColors.BRACES);
    public static final TextAttributesKey BRACKETS = createTextAttributesKey(
            "MCFPP_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS);
    public static final TextAttributesKey PARENTHESES = createTextAttributesKey(
            "MCFPP_PARENTHESES", DefaultLanguageHighlighterColors.PARENTHESES);
    public static final TextAttributesKey OPERATOR = createTextAttributesKey(
            "MCFPP_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN);
    public static final TextAttributesKey BAD_CHARACTER = createTextAttributesKey(
            "MCFPP_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER);

    private static final TextAttributesKey[] EMPTY = TextAttributesKey.EMPTY_ARRAY;

    @Override
    public @NotNull Lexer getHighlightingLexer() {
        return new McfppLexer();
    }

    @Override
    public TextAttributesKey @NotNull [] getTokenHighlights(IElementType tokenType) {
        if (tokenType == McfppTokenTypes.KEYWORD) return pack(KEYWORD);
        if (tokenType == McfppTokenTypes.CONTROL_KEYWORD) return pack(CONTROL_KEYWORD);
        if (tokenType == McfppTokenTypes.DECLARATION_KEYWORD) return pack(DECLARATION_KEYWORD);
        if (tokenType == McfppTokenTypes.MODIFIER_KEYWORD) return pack(MODIFIER_KEYWORD);
        if (tokenType == McfppTokenTypes.TYPE_KEYWORD) return pack(TYPE_KEYWORD);
        if (tokenType == McfppTokenTypes.IDENTIFIER) return pack(IDENTIFIER);
        if (tokenType == McfppTokenTypes.STRING) return pack(STRING);
        if (tokenType == McfppTokenTypes.NUMBER) return pack(NUMBER);
        if (tokenType == McfppTokenTypes.COMMENT) return pack(COMMENT);
        if (tokenType == McfppTokenTypes.DOC_COMMENT) return pack(DOC_COMMENT);
        if (tokenType == McfppTokenTypes.COMMAND) return pack(COMMAND);
        if (tokenType == McfppTokenTypes.ANNOTATION) return pack(ANNOTATION);
        if (tokenType == McfppTokenTypes.TARGET_SELECTOR) return pack(TARGET_SELECTOR);
        if (tokenType == McfppTokenTypes.BOOLEAN_LITERAL) return pack(BOOLEAN);
        if (tokenType == McfppTokenTypes.NULL_LITERAL) return pack(NULL);
        if (tokenType == McfppTokenTypes.LEFT_BRACE || tokenType == McfppTokenTypes.RIGHT_BRACE) return pack(BRACES);
        if (tokenType == McfppTokenTypes.LEFT_BRACKET || tokenType == McfppTokenTypes.RIGHT_BRACKET) return pack(BRACKETS);
        if (tokenType == McfppTokenTypes.LEFT_PARENTHESIS || tokenType == McfppTokenTypes.RIGHT_PARENTHESIS) return pack(PARENTHESES);
        if (tokenType == McfppTokenTypes.OPERATOR) return pack(OPERATOR);
        if (tokenType == McfppTokenTypes.BAD_CHARACTER) return pack(BAD_CHARACTER);
        return EMPTY;
    }
}
