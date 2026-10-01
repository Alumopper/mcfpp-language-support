package top.mcfpp.intellij.lang;

import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;

public final class McfppParserDefinition implements ParserDefinition {
    public static final IFileElementType FILE = new IFileElementType(McfppLanguage.INSTANCE);

    private static final TokenSet COMMENTS = TokenSet.create(McfppTokenTypes.COMMENT, McfppTokenTypes.DOC_COMMENT, McfppTokenTypes.VERSION_DIRECTIVE);
    private static final TokenSet STRINGS = TokenSet.create(McfppTokenTypes.STRING);
    private static final TokenSet WHITE_SPACES = TokenSet.create(TokenType.WHITE_SPACE);

    @Override
    public @NotNull Lexer createLexer(Project project) {
        return new McfppLexer();
    }

    @Override
    public @NotNull PsiParser createParser(Project project) {
        return new McfppPsiParser();
    }

    @Override
    public @NotNull IFileElementType getFileNodeType() {
        return FILE;
    }

    @Override
    public @NotNull TokenSet getWhitespaceTokens() {
        return WHITE_SPACES;
    }

    @Override
    public @NotNull TokenSet getCommentTokens() {
        return COMMENTS;
    }

    @Override
    public @NotNull TokenSet getStringLiteralElements() {
        return STRINGS;
    }

    @Override
    public @NotNull PsiElement createElement(ASTNode node) {
        if (node.getElementType() instanceof McfppElementType) {
            return new McfppNamedElement(node);
        }
        return new ASTWrapperPsiElement(node);
    }

    @Override
    public @NotNull PsiFile createFile(@NotNull FileViewProvider viewProvider) {
        return new McfppFile(viewProvider);
    }
}
