package top.mcfpp.intellij.lang;

import com.intellij.model.Pointer;
import com.intellij.openapi.application.ReadAction;
import com.intellij.platform.backend.documentation.DocumentationResult;
import com.intellij.platform.backend.documentation.DocumentationTarget;
import com.intellij.platform.backend.presentation.TargetPresentation;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class McfppRemoteDocumentationTarget implements DocumentationTarget {
    private final SmartPsiElementPointer<PsiElement> source;
    private final int offsetInToken;
    private final McfppRemoteDocumentation remote;
    private final DocumentationTarget fallback;

    static @Nullable DocumentationTarget at(PsiFile file, int offset, McfppRemoteDocumentation remote,
                                             @Nullable DocumentationTarget fallback) {
        PsiElement element = file.findElementAt(offset);
        if (element == null || file.getVirtualFile() == null || !file.getVirtualFile().isInLocalFileSystem()) return null;
        var token = element.getNode().getElementType();
        if (fallback == null && token != McfppTokenTypes.IDENTIFIER && token != McfppTokenTypes.TYPE_KEYWORD &&
                token != McfppTokenTypes.ANNOTATION) return null;
        return new McfppRemoteDocumentationTarget(SmartPointerManager.createPointer(element),
                offset - element.getTextRange().getStartOffset(), remote, fallback);
    }

    private McfppRemoteDocumentationTarget(SmartPsiElementPointer<PsiElement> source, int offsetInToken,
                                            McfppRemoteDocumentation remote, @Nullable DocumentationTarget fallback) {
        this.source = source;
        this.offsetInToken = offsetInToken;
        this.remote = remote;
        this.fallback = fallback;
    }

    @Override
    public @NotNull Pointer<? extends DocumentationTarget> createPointer() {
        var fallbackPointer = fallback == null ? null : fallback.createPointer();
        return () -> source.getElement() == null ? null : new McfppRemoteDocumentationTarget(source, offsetInToken,
                remote, fallbackPointer == null ? null : fallbackPointer.dereference());
    }

    @Override
    public @NotNull TargetPresentation computePresentation() {
        if (fallback != null) return fallback.computePresentation();
        var element = source.getElement();
        return TargetPresentation.builder(element == null ? "MCFPP" : element.getText()).icon(McfppIcons.FILE).presentation();
    }

    @Override
    public @Nullable Navigatable getNavigatable() {
        return fallback == null ? null : fallback.getNavigatable();
    }

    @Override
    public @Nullable String computeDocumentationHint() {
        return fallback == null ? null : fallback.computeDocumentationHint();
    }

    @Override
    public @Nullable DocumentationResult computeDocumentation() {
        var local = fallback != null && fallback.computeDocumentation() instanceof DocumentationResult.Documentation doc ? doc : null;
        var element = source.getElement();
        if (element == null) return local;
        var project = element.getProject();
        var file = element.getContainingFile();
        var document = PsiDocumentManager.getInstance(project).getDocument(file);
        var virtual = file.getVirtualFile();
        if (document == null || virtual == null) return local;
        int offset = element.getTextRange().getStartOffset() + Math.min(offsetInToken, element.getTextLength() - 1);
        if (offset < 0 || offset >= document.getTextLength()) return local;
        int line = document.getLineNumber(offset);
        int column = offset - document.getLineStartOffset(line);
        return DocumentationResult.asyncDocumentation(() -> {
            var data = remote.fetch(virtual, line, column);
            if (data == null) return local;
            return ReadAction.compute(() -> project.isDisposed() ? null : McfppQuickDocumentation.render(project, data));
        });
    }
}
