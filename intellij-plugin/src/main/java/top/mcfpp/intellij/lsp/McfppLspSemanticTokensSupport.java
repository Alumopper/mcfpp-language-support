package top.mcfpp.intellij.lsp;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.mcfpp.intellij.lang.McfppFile;
import top.mcfpp.intellij.lang.McfppSyntaxHighlighter;

import java.util.ArrayList;
import java.util.List;

final class McfppLspSemanticTokensSupport extends LspSemanticTokensSupport {
    @Override
    public boolean shouldAskServerForSemanticTokens(@NotNull PsiFile file) {
        return file instanceof McfppFile;
    }

    @Override
    public @NotNull List<String> getTokenTypes() {
        var types = new ArrayList<>(super.getTokenTypes());
        types.add("mniFunction");
        return List.copyOf(types);
    }

    @Override
    public @Nullable TextAttributesKey getTextAttributesKey(@NotNull String type, @NotNull List<String> modifiers) {
        return switch (type) {
            case "mniFunction" -> McfppSyntaxHighlighter.MNI_FUNCTION;
            case "macro" -> McfppSyntaxHighlighter.MACRO_FUNCTION;
            case "function", "method" -> modifiers.contains("declaration")
                    ? McfppSyntaxHighlighter.FUNCTION_DECLARATION : McfppSyntaxHighlighter.FUNCTION_CALL;
            case "namespace" -> McfppSyntaxHighlighter.NAMESPACE;
            case "class", "enum", "struct", "type", "typeParameter" -> McfppSyntaxHighlighter.TYPE_NAME;
            case "parameter" -> McfppSyntaxHighlighter.PARAMETER;
            case "variable" -> McfppSyntaxHighlighter.VARIABLE;
            case "property" -> McfppSyntaxHighlighter.FIELD;
            case "enumMember" -> McfppSyntaxHighlighter.ENUM_MEMBER;
            default -> super.getTextAttributesKey(type, modifiers);
        };
    }
}
