package top.mcfpp.intellij.lang;

import com.intellij.lexer.Lexer;
import com.intellij.psi.tree.IElementType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McfppLexerTest {
    @Test
    void recognizesVersionDirectivesAndKeepsArithmeticDistinctFromCommands() {
        List<Token> tokens = tokens("""
                #if MC >= 26.3
                var x = -12 /3
                x += 2
                x -= 1
                x *= 3
                x /= 2
                x %= 4
                #elif MC >= 26.1
                #else
                #endif
                #ifdef ordinary comment
                /say hello
                """);
        for (String directive : List.of("#if MC >= 26.3", "#elif MC >= 26.1", "#else", "#endif")) {
            assertEquals(McfppTokenTypes.VERSION_DIRECTIVE, typeOf(tokens, directive));
        }
        for (String operator : List.of("-", "/", "+=", "-=", "*=", "/=", "%=")) {
            assertEquals(McfppTokenTypes.OPERATOR, typeOf(tokens, operator));
        }
        assertEquals(McfppTokenTypes.COMMENT, typeOf(tokens, "#ifdef ordinary comment"));
        assertEquals(McfppTokenTypes.COMMAND, typeOf(tokens, "/say hello"));
        assertEquals(McfppTokenTypes.DOC_COMMENT, typeOf(tokens("#{\n#if MC >= 26.3\n}#"), "#{\n#if MC >= 26.3\n}#"));
    }

    @Test
    void recognizesLanguageSpecificTokenCategories() {
        String source = """
                #{ docs }#
                public data Example {
                    @From<\"top.mcfpp.Mni\">
                    var enabled as bool = true
                    /execute as @a run say hello
                }
                """;

        List<Token> tokens = tokens(source);

        assertEquals(McfppTokenTypes.DOC_COMMENT, typeOf(tokens, "#{ docs }#"));
        assertEquals(McfppTokenTypes.MODIFIER_KEYWORD, typeOf(tokens, "public"));
        assertEquals(McfppTokenTypes.DECLARATION_KEYWORD, typeOf(tokens, "data"));
        assertEquals(McfppTokenTypes.ANNOTATION, typeOf(tokens, "@From"));
        assertEquals(McfppTokenTypes.TYPE_KEYWORD, typeOf(tokens, "bool"));
        assertEquals(McfppTokenTypes.BOOLEAN_LITERAL, typeOf(tokens, "true"));
        assertEquals(McfppTokenTypes.COMMAND, typeOf(tokens, "/execute as @a run say hello"));
        assertEquals(McfppTokenTypes.LEFT_BRACE, typeOf(tokens, "{"));
        assertEquals(McfppTokenTypes.RIGHT_BRACE, typeOf(tokens, "}"));
    }

    @Test
    void distinguishesSelectorsFromAnnotationsOutsideCommands() {
        List<Token> tokens = tokens("@e @Custom vec3 null 1..5 2.5f");

        assertEquals(McfppTokenTypes.TARGET_SELECTOR, typeOf(tokens, "@e"));
        assertEquals(McfppTokenTypes.ANNOTATION, typeOf(tokens, "@Custom"));
        assertEquals(McfppTokenTypes.TYPE_KEYWORD, typeOf(tokens, "vec3"));
        assertEquals(McfppTokenTypes.NULL_LITERAL, typeOf(tokens, "null"));
        assertEquals(McfppTokenTypes.OPERATOR, typeOf(tokens, ".."));
        assertEquals(McfppTokenTypes.NUMBER, typeOf(tokens, "2.5f"));
    }

    private static IElementType typeOf(List<Token> tokens, String text) {
        return tokens.stream().filter(token -> token.text().equals(text)).findFirst().orElseThrow().type();
    }

    private static List<Token> tokens(String source) {
        Lexer lexer = new McfppLexer();
        lexer.start(source);
        List<Token> tokens = new ArrayList<>();
        while (lexer.getTokenType() != null) {
            if (lexer.getTokenType() != McfppTokenTypes.WHITE_SPACE) {
                tokens.add(new Token(
                        lexer.getTokenType(),
                        source.substring(lexer.getTokenStart(), lexer.getTokenEnd())
                ));
            }
            lexer.advance();
        }
        return tokens;
    }

    private record Token(IElementType type, String text) {
    }
}
