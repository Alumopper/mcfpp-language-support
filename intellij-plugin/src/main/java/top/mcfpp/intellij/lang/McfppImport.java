package top.mcfpp.intellij.lang;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record McfppImport(
        @NotNull String namespace,
        @NotNull String importedName,
        @Nullable String alias
) {
    public boolean isWildcard() {
        return "*".equals(importedName);
    }

    public boolean exposes(String name) {
        if (alias != null) return alias.equals(name);
        return isWildcard() || importedName.equals(name);
    }

    public String originalName(String exposedName) {
        return alias != null && alias.equals(exposedName) ? importedName : exposedName;
    }
}
