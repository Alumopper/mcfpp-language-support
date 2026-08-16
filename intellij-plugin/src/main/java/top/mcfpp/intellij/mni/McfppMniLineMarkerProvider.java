package top.mcfpp.intellij.mni;

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.lang.McfppFile;
import top.mcfpp.intellij.lang.McfppLanguage;
import top.mcfpp.intellij.lang.McfppSymbolIndex;
import top.mcfpp.intellij.lang.McfppTokenTypes;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/** Adds bidirectional gutter navigation between MCFPP native declarations and their Java implementations. */
public final class McfppMniLineMarkerProvider extends RelatedItemLineMarkerProvider {
    @Override
    protected void collectNavigationMarkers(
            @NotNull PsiElement element,
            @NotNull Collection<? super RelatedItemLineMarkerInfo<?>> result
    ) {
        if (element instanceof PsiIdentifier identifier) {
            List<PsiElement> declarations = findMcfppTargets(identifier);
            if (!declarations.isEmpty()) {
                result.add(NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementingMethod)
                        .setTargets(declarations)
                        .setTooltipText("Navigate to MCFPP native declaration")
                        .setPopupTitle("MCFPP native declarations")
                        .createLineMarkerInfo(identifier));
            }
            return;
        }

        List<PsiElement> implementations = findJavaTargets(element);
        if (!implementations.isEmpty()) {
            result.add(NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
                    .setTargets(implementations)
                    .setTooltipText("Navigate to MNI Java implementation")
                    .setPopupTitle("MNI Java implementations")
                    .createLineMarkerInfo(element));
        }
    }

    static @NotNull List<PsiElement> findJavaTargets(@NotNull PsiElement element) {
        if (element.getLanguage() != McfppLanguage.INSTANCE || element.getFirstChild() != null ||
                element.getNode() == null ||
                (element.getNode().getElementType() != McfppTokenTypes.IDENTIFIER &&
                        element.getNode().getElementType() != McfppTokenTypes.STRING)) {
            return List.of();
        }

        int startOffset = element.getTextOffset();
        int endOffset = element.getTextRange().getEndOffset();
        for (MniJavaTargetParser.Target target : targets(element.getContainingFile())) {
            MniJavaTargetParser.Segment marker = target.segments().getLast();
            if (marker.startOffset() < startOffset || marker.endOffset() > endOffset) continue;
            Optional<MniFunctionSignature> signature = MniFunctionSignature.from(
                    element.getContainingFile().getViewProvider().getContents(), target);
            return MniJavaResolver.resolve(element.getProject(), target, marker, signature);
        }
        Optional<MniJavaTargetParser.Target> declarationTarget =
                McfppMniReferenceContributor.declarationTarget(element);
        if (declarationTarget.isPresent()) {
            MniJavaTargetParser.Target target = declarationTarget.get();
            MniJavaTargetParser.Segment marker = target.segments().getLast();
            Optional<MniFunctionSignature> signature = MniFunctionSignature.from(
                    element.getContainingFile().getViewProvider().getContents(), target);
            return MniJavaResolver.resolve(element.getProject(), target, marker, signature);
        }
        return List.of();
    }

    static @NotNull List<PsiElement> findMcfppTargets(@NotNull PsiIdentifier identifier) {
        if (DumbService.isDumb(identifier.getProject())) return List.of();
        if (identifier.getParent() instanceof PsiMethod method) return findMethodDeclarations(method);
        if (identifier.getParent() instanceof PsiClass owner) return findClassDeclarations(owner);
        return List.of();
    }

    private static List<PsiElement> findMethodDeclarations(PsiMethod method) {
        PsiClass owner = method.getContainingClass();
        String ownerName = owner == null ? null : owner.getQualifiedName();
        Optional<MniJavaAnnotation> annotation = MniJavaAnnotation.from(method);
        if (ownerName == null || annotation.isEmpty() ||
                !method.hasModifierProperty(PsiModifier.PUBLIC) ||
                !method.hasModifierProperty(PsiModifier.STATIC)) {
            return List.of();
        }

        String qualifiedMethodName = ownerName + '.' + method.getName();
        return findMcfppTargets(method.getProject(), McfppSymbolIndex.mniMethodKey(qualifiedMethodName), target -> {
            if (target.kind() != MniJavaTargetParser.Kind.JAVA_METHOD ||
                    !qualifiedMethodName.equals(target.qualifiedName())) return false;
            Optional<MniFunctionSignature> signature = MniFunctionSignature.from(
                    target.file().getViewProvider().getContents(), target.target());
            return signature.filter(value -> value.parametersMatch(annotation.get()))
                    .filter(value -> value.javaArgumentCount() == method.getParameterList().getParametersCount())
                    .isPresent();
        });
    }

    private static List<PsiElement> findClassDeclarations(PsiClass owner) {
        String qualifiedName = owner.getQualifiedName();
        if (qualifiedName == null) return List.of();
        return findMcfppTargets(owner.getProject(), McfppSymbolIndex.mniClassKey(qualifiedName), target ->
                target.kind() == MniJavaTargetParser.Kind.FROM && qualifiedName.equals(target.qualifiedName())
        );
    }

    private static List<PsiElement> findMcfppTargets(
            Project project,
            String indexKey,
            java.util.function.Predicate<FileTarget> accepts
    ) {
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        PsiManager psiManager = PsiManager.getInstance(project);
        LinkedHashSet<PsiElement> result = new LinkedHashSet<>();
        for (VirtualFile virtualFile : FileBasedIndex.getInstance()
                .getContainingFiles(McfppSymbolIndex.NAME, indexKey, scope)) {
            PsiFile file = psiManager.findFile(virtualFile);
            if (!(file instanceof McfppFile)) continue;
            for (MniJavaTargetParser.Target target : targets(file)) {
                FileTarget fileTarget = new FileTarget(file, target);
                if (!accepts.test(fileTarget)) continue;
                PsiElement leaf = file.findElementAt(target.segments().getLast().startOffset());
                if (leaf != null) result.add(leaf);
            }
        }
        return List.copyOf(result);
    }

    private static List<MniJavaTargetParser.Target> targets(PsiFile file) {
        return CachedValuesManager.getCachedValue(file, () -> CachedValueProvider.Result.create(
                MniJavaTargetParser.parse(file.getViewProvider().getContents()),
                file
        ));
    }

    private record FileTarget(PsiFile file, MniJavaTargetParser.Target target) {
        MniJavaTargetParser.Kind kind() {
            return target.kind();
        }

        String qualifiedName() {
            return target.qualifiedName();
        }
    }
}
