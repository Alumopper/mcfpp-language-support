package top.mcfpp.intellij.lang;

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.openapi.editor.Document;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

record McfppCompletionContext(@NotNull Kind kind, @Nullable String qualifier) {
    enum Kind { GLOBAL, MEMBER, NAMESPACE }

    static @NotNull McfppCompletionContext create(
            @NotNull CompletionParameters parameters,
            @NotNull CompletionResultSet result,
            @NotNull McfppFileModel model
    ) {
        Document document = parameters.getEditor().getDocument();
        CharSequence source = document.getCharsSequence();
        int start = Math.max(0, parameters.getOffset() - result.getPrefixMatcher().getPrefix().length());
        int separator = previousNonWhitespace(source, start - 1);
        if (separator < 0) return new McfppCompletionContext(Kind.GLOBAL, null);

        char value = source.charAt(separator);
        if (value != '.' && value != ':') return new McfppCompletionContext(Kind.GLOBAL, null);
        String qualifier = identifierBefore(source, separator - 1, value == ':');
        if (qualifier.isEmpty()) return new McfppCompletionContext(Kind.GLOBAL, null);
        if (value == ':') {
            for (McfppImport imported : model.imports()) {
                if (imported.isWildcard() && qualifier.equals(imported.alias())) {
                    return new McfppCompletionContext(Kind.NAMESPACE, imported.namespace());
                }
            }
            return new McfppCompletionContext(Kind.NAMESPACE, qualifier);
        }

        String owner = qualifier;
        var variables = model.visibleLocalSymbols(qualifier, parameters.getOffset());
        if (!variables.isEmpty()) {
            String declaredType = McfppTypes.declaredType(variables.getFirst());
            if (declaredType != null) owner = declaredType;
        }
        return new McfppCompletionContext(Kind.MEMBER, owner);
    }

    private static int previousNonWhitespace(CharSequence source, int offset) {
        while (offset >= 0 && Character.isWhitespace(source.charAt(offset))) offset--;
        return offset;
    }

    private static String identifierBefore(CharSequence source, int offset, boolean allowDots) {
        offset = previousNonWhitespace(source, offset);
        int end = offset + 1;
        while (offset >= 0) {
            char value = source.charAt(offset);
            if (!(Character.isUnicodeIdentifierPart(value) || value == '_' || value == '$' ||
                    allowDots && value == '.')) break;
            offset--;
        }
        return source.subSequence(offset + 1, end).toString();
    }
}
