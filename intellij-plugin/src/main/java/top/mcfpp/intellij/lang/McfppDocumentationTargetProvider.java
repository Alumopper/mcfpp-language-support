package top.mcfpp.intellij.lang;

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
import top.mcfpp.language.McfppDocumentationData;

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
        if (!(file instanceof McfppFile)) {
            return referenceTargets(file.findReferenceAt(offset));
        }
        var remote = file.getProject().getService(McfppRemoteDocumentation.class);
        if (remote == null || !remote.isAvailable()) {
            // A different language server may also support this file.
            for (DocumentationTargetProvider provider : DocumentationTargetProvider.EP_NAME.getExtensionList()) {
                if (provider.getClass().getName().startsWith("com.intellij.platform.lsp.") &&
                        !provider.documentationTargets(file, offset).isEmpty()) return List.of();
            }
            remote = null;
        }
        int safeOffset = Math.max(0, Math.min(offset, Math.max(0, file.getTextLength() - 1)));
        var targets = localTargets(file, safeOffset);
        if (remote == null) return targets;
        var target = McfppRemoteDocumentationTarget.at(file, safeOffset, remote, targets.isEmpty() ? null : targets.getFirst());
        return target == null ? targets : List.of(target);
    }

    private static List<DocumentationTarget> referenceTargets(@Nullable PsiReference reference) {
        if (reference == null) return List.of();
        List<DocumentationTarget> targets = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (reference instanceof PsiPolyVariantReference poly) {
            for (ResolveResult resolved : poly.multiResolve(false)) addTarget(resolved.getElement(), targets, seen);
        } else addTarget(reference.resolve(), targets, seen);
        return targets;
    }

    private static List<DocumentationTarget> localTargets(PsiFile file, int safeOffset) {
        List<PsiElement> fields = McfppTemplateLiterals.fieldTargets(file, safeOffset);
        if (fields != null) {
            List<DocumentationTarget> targets = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            fields.forEach(field -> addTarget(field, targets, seen));
            return targets;
        }
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
            return McfppQuickDocumentation.render(filePointer.getProject(), new McfppDocumentationData(
                    symbol.signature(), symbol.documentation(), symbol.namespace(), symbol.owner(), null, null));
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
