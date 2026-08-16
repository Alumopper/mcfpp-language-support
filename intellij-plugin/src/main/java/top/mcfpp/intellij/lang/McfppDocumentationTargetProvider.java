package top.mcfpp.intellij.lang;

import com.intellij.lang.documentation.DocumentationMarkup;
import com.intellij.model.Pointer;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.platform.backend.documentation.DocumentationResult;
import com.intellij.platform.backend.documentation.DocumentationTarget;
import com.intellij.platform.backend.documentation.DocumentationTargetProvider;
import com.intellij.platform.backend.presentation.TargetPresentation;
import com.intellij.platform.backend.presentation.TargetPresentationBuilder;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Quick Documentation backed by the same cached declaration model as navigation. */
public final class McfppDocumentationTargetProvider implements DocumentationTargetProvider {
    @Override
    public @NotNull List<? extends @NotNull DocumentationTarget> documentationTargets(
            @NotNull PsiFile file,
            int offset
    ) {
        if (!(file instanceof McfppFile)) return List.of();
        int safeOffset = Math.max(0, Math.min(offset, Math.max(0, file.getTextLength() - 1)));
        McfppSymbol declaration = McfppFileModels.get(file).declarationAt(safeOffset);
        if (declaration != null) return List.of(new McfppDocumentationTarget(file, declaration));

        PsiElement element = file.findElementAt(safeOffset);
        if (element == null && safeOffset > 0) element = file.findElementAt(safeOffset - 1);
        if (element == null) return List.of();
        List<DocumentationTarget> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PsiReference reference : element.getReferences()) {
            if (reference instanceof PsiPolyVariantReference polyVariantReference) {
                for (ResolveResult resolveResult : polyVariantReference.multiResolve(false)) {
                    addTarget(resolveResult.getElement(), result, seen);
                }
            } else {
                addTarget(reference.resolve(), result, seen);
            }
        }
        return result;
    }

    private static void addTarget(
            @Nullable PsiElement element,
            List<DocumentationTarget> result,
            Set<String> seen
    ) {
        if (element == null || !(element.getContainingFile() instanceof McfppFile file)) return;
        McfppSymbol symbol = McfppFileModels.get(file).declarationAt(element.getTextOffset());
        if (symbol != null && seen.add(file.getVirtualFile() + "#" + symbol.nameOffset())) {
            result.add(new McfppDocumentationTarget(file, symbol));
        }
    }

    private static final class McfppDocumentationTarget implements DocumentationTarget {
        private final SmartPsiElementPointer<PsiFile> filePointer;
        private final String symbolName;
        private final int symbolOffset;

        private McfppDocumentationTarget(PsiFile file, McfppSymbol symbol) {
            this.filePointer = SmartPointerManager.createPointer(file);
            this.symbolName = symbol.name();
            this.symbolOffset = symbol.nameOffset();
        }

        @Override
        public @NotNull Pointer<? extends DocumentationTarget> createPointer() {
            SmartPsiElementPointer<PsiFile> pointer = filePointer;
            String name = symbolName;
            int offset = symbolOffset;
            return () -> {
                PsiFile file = pointer.getElement();
                if (!(file instanceof McfppFile)) return null;
                McfppSymbol symbol = restoreSymbol(file, name, offset);
                return symbol == null ? null : new McfppDocumentationTarget(file, symbol);
            };
        }

        @Override
        public @NotNull TargetPresentation computePresentation() {
            McfppSymbol symbol = currentSymbol();
            String text = symbol == null ? symbolName : symbol.signature();
            TargetPresentationBuilder builder = TargetPresentation.builder(text)
                    .icon(symbol == null ? McfppIcons.FILE : McfppCompletionContributor.icon(symbol.kind()));
            if (symbol != null && !symbol.namespace().isEmpty()) builder.locationText(symbol.namespace());
            return builder.presentation();
        }

        @Override
        public @Nullable Navigatable getNavigatable() {
            PsiFile file = filePointer.getElement();
            McfppSymbol symbol = currentSymbol();
            return file == null || file.getVirtualFile() == null || symbol == null
                    ? null
                    : new OpenFileDescriptor(file.getProject(), file.getVirtualFile(), symbol.nameOffset());
        }

        @Override
        public @Nullable String computeDocumentationHint() {
            McfppSymbol symbol = currentSymbol();
            return symbol == null ? null : "<code>" + escape(symbol.signature()) + "</code>";
        }

        @Override
        public @Nullable DocumentationResult computeDocumentation() {
            McfppSymbol symbol = currentSymbol();
            if (symbol == null) return null;
            StringBuilder html = new StringBuilder()
                    .append(DocumentationMarkup.DEFINITION_START)
                    .append("<code>").append(escape(symbol.signature())).append("</code>")
                    .append(DocumentationMarkup.DEFINITION_END);
            if (symbol.documentation() != null && !symbol.documentation().isBlank()) {
                html.append(DocumentationMarkup.CONTENT_START)
                        .append(escape(symbol.documentation()).replace("\n", "<br>"))
                        .append(DocumentationMarkup.CONTENT_END);
            }
            html.append(DocumentationMarkup.SECTIONS_START)
                    .append(DocumentationMarkup.SECTION_HEADER_START).append("Kind:")
                    .append(DocumentationMarkup.SECTION_SEPARATOR).append(escape(symbol.kind().presentableName()))
                    .append(DocumentationMarkup.SECTION_END);
            if (!symbol.namespace().isEmpty()) {
                html.append(DocumentationMarkup.SECTION_HEADER_START).append("Namespace:")
                        .append(DocumentationMarkup.SECTION_SEPARATOR).append(escape(symbol.namespace()))
                        .append(DocumentationMarkup.SECTION_END);
            }
            if (symbol.owner() != null) {
                html.append(DocumentationMarkup.SECTION_HEADER_START).append("Owner:")
                        .append(DocumentationMarkup.SECTION_SEPARATOR).append(escape(symbol.owner()))
                        .append(DocumentationMarkup.SECTION_END);
            }
            html.append(DocumentationMarkup.SECTIONS_END);
            return DocumentationResult.documentation(html.toString());
        }

        private @Nullable McfppSymbol currentSymbol() {
            PsiFile file = filePointer.getElement();
            return file instanceof McfppFile ? restoreSymbol(file, symbolName, symbolOffset) : null;
        }

        private static @Nullable McfppSymbol restoreSymbol(PsiFile file, String name, int oldOffset) {
            McfppFileModel model = McfppFileModels.get(file);
            McfppSymbol exact = model.declarationAt(oldOffset);
            if (exact != null && exact.name().equals(name)) return exact;
            return model.symbols().stream()
                    .filter(symbol -> symbol.name().equals(name))
                    .min(java.util.Comparator.comparingInt(symbol -> Math.abs(symbol.nameOffset() - oldOffset)))
                    .orElse(null);
        }

        private static String escape(String value) {
            return StringUtil.escapeXmlEntities(value);
        }
    }
}
