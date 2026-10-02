package top.mcfpp.intellij.lang;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.tree.IElementType;
import java.util.ArrayList;
import java.util.List;

/** Offset-preserving tokens, including comments and version directives. */
final class McfppSourceTokens {
    record Token(IElementType type, String text, int start, int end) {
        boolean trivia() {
            return type == McfppTokenTypes.COMMENT || type == McfppTokenTypes.DOC_COMMENT;
        }
    }

    static List<Token> lex(String source) {
        McfppLexer lexer = new McfppLexer();
        lexer.start(source);
        List<Token> result = new ArrayList<>();
        while (lexer.getTokenType() != null) {
            ProgressManager.checkCanceled();
            if (lexer.getTokenType() != McfppTokenTypes.WHITE_SPACE) {
                result.add(new Token(lexer.getTokenType(), source.substring(lexer.getTokenStart(), lexer.getTokenEnd()),
                        lexer.getTokenStart(), lexer.getTokenEnd()));
            }
            lexer.advance();
        }
        return result;
    }
}
