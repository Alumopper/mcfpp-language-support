package com.intellij.platform.lsp.test;

import com.intellij.platform.backend.documentation.DocumentationTarget;
import com.intellij.platform.backend.documentation.DocumentationTargetProvider;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import java.util.List;

/** Represents the platform LSP extension at the public documentation-target boundary. */
public final class TestDocumentationProvider implements DocumentationTargetProvider {
    public List<? extends DocumentationTarget> targets;
    public TestDocumentationProvider(List<? extends DocumentationTarget> targets) { this.targets = targets; }
    @Override public @NotNull List<? extends DocumentationTarget> documentationTargets(@NotNull PsiFile file, int offset) {
        return targets;
    }
}
