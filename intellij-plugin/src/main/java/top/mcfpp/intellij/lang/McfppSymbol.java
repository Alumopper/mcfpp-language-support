package top.mcfpp.intellij.lang;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record McfppSymbol(
        @NotNull McfppSymbolKind kind,
        @NotNull String name,
        @NotNull String namespace,
        @Nullable String owner,
        int declarationStart,
        int nameOffset,
        int headerEnd,
        int bodyStart,
        int endOffset,
        int scopeStart,
        int scopeEnd,
        @NotNull String signature,
        @Nullable String documentation
) {
    public boolean contains(int offset) {
        return declarationStart <= offset && offset < endOffset;
    }

    public boolean scopeContains(int offset) {
        return scopeStart <= offset && offset <= scopeEnd;
    }

    public @NotNull String qualifiedName() {
        String prefix = namespace.isEmpty() ? "" : namespace + ":";
        return owner == null ? prefix + name : prefix + owner + "." + name;
    }

    public boolean isTopLevel() {
        return owner == null && kind != McfppSymbolKind.PARAMETER && kind != McfppSymbolKind.VARIABLE;
    }
}
