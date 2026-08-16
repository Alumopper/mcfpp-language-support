package top.mcfpp.intellij.mni;

import com.intellij.openapi.util.TextRange;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.PsiReferenceProvider;
import com.intellij.psi.PsiReferenceRegistrar;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.lang.McfppLanguage;
import top.mcfpp.intellij.lang.McfppFileModel;
import top.mcfpp.intellij.lang.McfppFileModels;
import top.mcfpp.intellij.lang.McfppSymbol;
import top.mcfpp.intellij.lang.McfppSymbolKind;
import top.mcfpp.intellij.lang.McfppTokenTypes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class McfppMniReferenceContributor extends PsiReferenceContributor {
    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(
                PlatformPatterns.psiElement().withLanguage(McfppLanguage.INSTANCE),
                new MniReferenceProvider()
        );
    }

    private static final class MniReferenceProvider extends PsiReferenceProvider {
        @Override
        public PsiReference @NotNull [] getReferencesByElement(
                @NotNull PsiElement element,
                @NotNull ProcessingContext context
        ) {
            return referencesFor(element);
        }
    }

    static PsiReference[] referencesFor(PsiElement element) {
        if (element.getFirstChild() != null || element.getNode() == null) {
            return PsiReference.EMPTY_ARRAY;
        }
        if (element.getNode().getElementType() != McfppTokenTypes.IDENTIFIER &&
                element.getNode().getElementType() != McfppTokenTypes.STRING) {
            return PsiReference.EMPTY_ARRAY;
        }

        PsiFile file = element.getContainingFile();
        TargetIndex targetIndex = CachedValuesManager.getCachedValue(file, () ->
                CachedValueProvider.Result.create(
                        TargetIndex.create(file.getViewProvider().getContents()),
                        file
                )
        );
        TextRange elementRange = element.getTextRange();
        List<PsiReference> references = new ArrayList<>();
        for (Binding binding : targetIndex.inside(elementRange)) {
            TextRange absoluteRange = new TextRange(
                    binding.segment().startOffset(),
                    binding.segment().endOffset()
            );
            references.add(new McfppJavaReference(
                    element,
                    absoluteRange.shiftLeft(elementRange.getStartOffset()),
                    binding.target(),
                    binding.segment()
            ));
        }
        return references.toArray(PsiReference.EMPTY_ARRAY);
    }

    /** Returns the Java binding represented by an MCFPP native declaration name. */
    static Optional<MniJavaTargetParser.Target> declarationTarget(PsiElement element) {
        if (element.getFirstChild() != null || element.getNode() == null ||
                element.getNode().getElementType() != McfppTokenTypes.IDENTIFIER) {
            return Optional.empty();
        }
        PsiFile file = element.getContainingFile();
        TargetIndex targetIndex = CachedValuesManager.getCachedValue(file, () ->
                CachedValueProvider.Result.create(
                        TargetIndex.create(file.getViewProvider().getContents()),
                        file
                )
        );
        return declarationTarget(element, targetIndex);
    }

    private static Optional<MniJavaTargetParser.Target> declarationTarget(
            PsiElement element,
            TargetIndex targetIndex
    ) {
        McfppFileModel model = McfppFileModels.get(element.getContainingFile());
        McfppSymbol declaration = model.declarationAt(element.getTextOffset());
        if (declaration == null) return Optional.empty();

        if (declaration.kind() == McfppSymbolKind.FUNCTION) {
            return targetIndex.targets().stream()
                    .filter(target -> target.kind() == MniJavaTargetParser.Kind.JAVA_METHOD)
                    .filter(target -> target.declarationStartOffset() >= declaration.declarationStart())
                    .filter(target -> target.endOffset() <= declaration.endOffset())
                    .findFirst();
        }
        if (declaration.kind() != McfppSymbolKind.TYPE) return Optional.empty();

        return targetIndex.targets().stream()
                .filter(target -> target.kind() == MniJavaTargetParser.Kind.FROM)
                .filter(target -> target.endOffset() <= declaration.declarationStart())
                .filter(target -> targetIndex.onlyAnnotationTailBetween(target.endOffset(), declaration.declarationStart()))
                .max(Comparator.comparingInt(MniJavaTargetParser.Target::endOffset));
    }

    private record Binding(MniJavaTargetParser.Target target, MniJavaTargetParser.Segment segment) {
    }

    private record TargetIndex(String source, List<MniJavaTargetParser.Target> targets, List<Binding> bindings) {
        static TargetIndex create(CharSequence source) {
            String sourceText = source.toString();
            List<MniJavaTargetParser.Target> targets = MniJavaTargetParser.parse(sourceText);
            List<Binding> bindings = new ArrayList<>();
            for (MniJavaTargetParser.Target target : targets) {
                for (MniJavaTargetParser.Segment segment : target.segments()) {
                    bindings.add(new Binding(target, segment));
                }
            }
            bindings.sort(Comparator.comparingInt(binding -> binding.segment().startOffset()));
            return new TargetIndex(sourceText, targets, List.copyOf(bindings));
        }

        boolean onlyAnnotationTailBetween(int start, int end) {
            if (start < 0 || end < start || end > source.length()) return false;
            for (int offset = start; offset < end; offset++) {
                char value = source.charAt(offset);
                if (!Character.isWhitespace(value) && value != '"' && value != '\'' && value != '>') return false;
            }
            return true;
        }

        List<Binding> inside(TextRange range) {
            int low = 0;
            int high = bindings.size();
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (bindings.get(middle).segment().startOffset() < range.getStartOffset()) low = middle + 1;
                else high = middle;
            }

            List<Binding> result = new ArrayList<>();
            for (int index = low; index < bindings.size(); index++) {
                Binding binding = bindings.get(index);
                if (binding.segment().startOffset() >= range.getEndOffset()) break;
                if (binding.segment().endOffset() <= range.getEndOffset()) result.add(binding);
            }
            return result;
        }
    }
}
