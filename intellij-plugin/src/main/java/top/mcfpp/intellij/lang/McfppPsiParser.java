package top.mcfpp.intellij.lang;

import com.intellij.lang.ASTNode;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * A deliberately tolerant structural parser. The compiler/LSP remains the source of
 * full syntax diagnostics; this parser gives IDEA stable declaration nodes even while
 * the user is typing incomplete code.
 */
final class McfppPsiParser implements PsiParser {
    private static final Set<String> VARIABLE_KEYWORDS = Set.of("var", "global", "dynamic");

    @Override
    public @NotNull ASTNode parse(@NotNull IElementType root, @NotNull PsiBuilder builder) {
        PsiBuilder.Marker file = builder.mark();
        while (!builder.eof()) {
            String text = builder.getTokenText();
            if ("func".equals(text)) {
                parseFunction(builder);
            } else if ("object".equals(text)) {
                parseNamedAfter(builder, 2, McfppElementTypes.TYPE_DECLARATION);
            } else if ("data".equals(text)) {
                parseNamedAfter(builder, 1, McfppElementTypes.TYPE_DECLARATION);
            } else if ("enum".equals(text)) {
                parseNamedAfter(builder, 1, McfppElementTypes.ENUM_DECLARATION);
            } else if ("typealias".equals(text)) {
                parseTypeAlias(builder);
            } else if ("constructor".equals(text)) {
                parseConstructor(builder);
            } else if (VARIABLE_KEYWORDS.contains(text)) {
                parseVariable(builder);
            } else if ("for".equals(text)) {
                parseForeachVariable(builder);
            } else {
                builder.advanceLexer();
            }
        }
        file.done(root);
        return builder.getTreeBuilt();
    }

    private static void parseFunction(PsiBuilder builder) {
        FunctionHeader header = scanFunctionHeader(builder);
        if (header == null) {
            builder.advanceLexer();
            return;
        }

        PsiBuilder.Marker declaration = builder.mark();
        for (int ordinal = 0; ordinal <= header.nameOrdinal() && !builder.eof(); ordinal++) {
            builder.advanceLexer();
        }
        declaration.done(McfppElementTypes.FUNCTION_DECLARATION);

        if (header.hasReadOnlyParameters() && "<".equals(builder.getTokenText())) {
            parseParameters(builder, "<", ">");
        }
        int scanned = 0;
        while (!builder.eof() && builder.getTokenType() != McfppTokenTypes.LEFT_PARENTHESIS && scanned++ < 32) {
            builder.advanceLexer();
        }
        if (builder.getTokenType() == McfppTokenTypes.LEFT_PARENTHESIS) {
            parseParameters(builder, "(", ")");
        }
    }

    private static FunctionHeader scanFunctionHeader(PsiBuilder builder) {
        PsiBuilder.Marker rollback = builder.mark();
        int ordinal = 0;
        int angles = 0;
        int lastTopLevelIdentifier = -1;
        int identifierBeforeAngle = -1;
        IElementType previousType = null;
        String previousText = null;
        int scanned = 0;
        while (!builder.eof() && scanned++ < 256) {
            IElementType type = builder.getTokenType();
            String text = builder.getTokenText();
            if ("<".equals(text)) {
                if (angles == 0) identifierBeforeAngle = lastTopLevelIdentifier;
                angles++;
            } else if (">".equals(text) && angles > 0) {
                angles--;
            } else if (type == McfppTokenTypes.LEFT_PARENTHESIS && angles == 0) {
                int name = previousType == McfppTokenTypes.IDENTIFIER
                        ? lastTopLevelIdentifier
                        : ">".equals(previousText) ? identifierBeforeAngle : -1;
                if (name >= 0) {
                    boolean hasReadOnlyParameters = ">".equals(previousText);
                    rollback.rollbackTo();
                    return new FunctionHeader(name, hasReadOnlyParameters);
                }
            }
            if (type == McfppTokenTypes.IDENTIFIER && angles == 0) lastTopLevelIdentifier = ordinal;
            if (type == McfppTokenTypes.LEFT_BRACE || type == McfppTokenTypes.RIGHT_BRACE ||
                    type == McfppTokenTypes.SEMICOLON) {
                rollback.rollbackTo();
                return null;
            }
            previousType = type;
            previousText = text;
            builder.advanceLexer();
            ordinal++;
        }
        rollback.rollbackTo();
        return null;
    }

    private static void parseConstructor(PsiBuilder builder) {
        builder.advanceLexer();
        int scanned = 0;
        while (!builder.eof() && scanned++ < 32) {
            if (builder.getTokenType() == McfppTokenTypes.LEFT_PARENTHESIS) {
                parseParameters(builder, "(", ")");
                return;
            }
            if (builder.getTokenType() == McfppTokenTypes.LEFT_BRACE ||
                    builder.getTokenType() == McfppTokenTypes.SEMICOLON) return;
            builder.advanceLexer();
        }
    }

    private static void parseParameters(PsiBuilder builder, String openText, String closeText) {
        if (!openText.equals(builder.getTokenText())) return;
        builder.advanceLexer();
        boolean atParameterStart = true;
        int parentheses = 0;
        int brackets = 0;
        int braces = 0;
        int angles = 0;
        while (!builder.eof()) {
            IElementType type = builder.getTokenType();
            String text = builder.getTokenText();
            boolean topLevel = parentheses == 0 && brackets == 0 && braces == 0 && angles == 0;
            if (closeText.equals(text) && topLevel) {
                builder.advanceLexer();
                return;
            }
            if (type == McfppTokenTypes.COMMA && topLevel) {
                atParameterStart = true;
                builder.advanceLexer();
                continue;
            }
            if (type == McfppTokenTypes.LEFT_PARENTHESIS) parentheses++;
            else if (type == McfppTokenTypes.RIGHT_PARENTHESIS && parentheses > 0) parentheses--;
            else if (type == McfppTokenTypes.LEFT_BRACKET) brackets++;
            else if (type == McfppTokenTypes.RIGHT_BRACKET && brackets > 0) brackets--;
            else if (type == McfppTokenTypes.LEFT_BRACE) braces++;
            else if (type == McfppTokenTypes.RIGHT_BRACE && braces > 0) braces--;
            else if ("<".equals(text)) angles++;
            else if (">".equals(text) && angles > 0) angles--;

            if (atParameterStart && ("static".equals(text) || "var".equals(text))) {
                builder.advanceLexer();
                continue;
            }
            if (atParameterStart && type == McfppTokenTypes.IDENTIFIER) {
                PsiBuilder.Marker parameter = builder.mark();
                builder.advanceLexer();
                if ("as".equals(builder.getTokenText())) {
                    builder.advanceLexer();
                    parameter.done(McfppElementTypes.PARAMETER_DECLARATION);
                } else {
                    parameter.drop();
                }
                atParameterStart = false;
                continue;
            }
            if (type != McfppTokenTypes.MODIFIER_KEYWORD) atParameterStart = false;
            builder.advanceLexer();
        }
    }

    private static void parseForeachVariable(PsiBuilder builder) {
        builder.advanceLexer(); // 'for'
        int scanned = 0;
        while (!builder.eof() && builder.getTokenType() != McfppTokenTypes.LEFT_PARENTHESIS && scanned++ < 16) {
            builder.advanceLexer();
        }
        if (builder.getTokenType() != McfppTokenTypes.LEFT_PARENTHESIS) return;
        builder.advanceLexer();
        if (builder.getTokenType() != McfppTokenTypes.IDENTIFIER) return;
        PsiBuilder.Marker variable = builder.mark();
        builder.advanceLexer();
        variable.done(McfppElementTypes.VARIABLE_DECLARATION);
    }

    private static void parseVariable(PsiBuilder builder) {
        PsiBuilder.Marker declaration = builder.mark();
        builder.advanceLexer();
        int scanned = 0;
        while (!builder.eof() && scanned++ < 16) {
            IElementType type = builder.getTokenType();
            if (type == McfppTokenTypes.IDENTIFIER) {
                builder.advanceLexer();
                if ("as".equals(builder.getTokenText())) builder.advanceLexer();
                declaration.done(McfppElementTypes.VARIABLE_DECLARATION);
                return;
            }
            if (type == McfppTokenTypes.LEFT_BRACE || type == McfppTokenTypes.RIGHT_BRACE ||
                    type == McfppTokenTypes.SEMICOLON) {
                declaration.drop();
                return;
            }
            builder.advanceLexer();
        }
        declaration.drop();
    }

    private static void parseTypeAlias(PsiBuilder builder) {
        PsiBuilder.Marker declaration = builder.mark();
        builder.advanceLexer();
        boolean sawAs = false;
        int scanned = 0;
        while (!builder.eof() && scanned++ < 256) {
            String text = builder.getTokenText();
            IElementType type = builder.getTokenType();
            if ("as".equals(text)) {
                sawAs = true;
                builder.advanceLexer();
                continue;
            }
            if (sawAs && type == McfppTokenTypes.IDENTIFIER) {
                builder.advanceLexer();
                declaration.done(McfppElementTypes.TYPE_ALIAS_DECLARATION);
                return;
            }
            if (type == McfppTokenTypes.LEFT_BRACE || type == McfppTokenTypes.RIGHT_BRACE ||
                    type == McfppTokenTypes.SEMICOLON) {
                declaration.drop();
                return;
            }
            builder.advanceLexer();
        }
        declaration.drop();
    }

    private static void parseNamedAfter(PsiBuilder builder, int keywordCount, IElementType elementType) {
        PsiBuilder.Marker declaration = builder.mark();
        for (int index = 0; index < keywordCount && !builder.eof(); index++) builder.advanceLexer();
        int scanned = 0;
        while (!builder.eof() && scanned++ < 32) {
            IElementType type = builder.getTokenType();
            if (type == McfppTokenTypes.IDENTIFIER) {
                builder.advanceLexer();
                declaration.done(elementType);
                return;
            }
            if (type == McfppTokenTypes.LEFT_BRACE || type == McfppTokenTypes.RIGHT_BRACE ||
                    type == McfppTokenTypes.SEMICOLON) {
                declaration.drop();
                return;
            }
            builder.advanceLexer();
        }
        declaration.drop();
    }

    private record FunctionHeader(int nameOrdinal, boolean hasReadOnlyParameters) {
    }
}
