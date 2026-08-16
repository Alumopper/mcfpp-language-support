package top.mcfpp.intellij.mni;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.nio.file.Path;

final class MniJavaResolver {
    private MniJavaResolver() {
    }

    static List<PsiElement> resolve(
            Project project,
            MniJavaTargetParser.Target target,
            MniJavaTargetParser.Segment segment
    ) {
        return resolve(project, target, segment, Optional.empty());
    }

    static List<PsiElement> resolve(
            Project project,
            MniJavaTargetParser.Target target,
            MniJavaTargetParser.Segment segment,
            Optional<MniFunctionSignature> signature
    ) {
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        String prefix = target.segments().stream()
                .limit(segment.ordinal() + 1L)
                .map(MniJavaTargetParser.Segment::text)
                .reduce((left, right) -> left + "." + right)
                .orElse("");

        boolean methodSegment = target.kind() == MniJavaTargetParser.Kind.JAVA_METHOD &&
                segment.ordinal() == target.segments().size() - 1;
        if (methodSegment) {
            return resolveStaticMethods(project, facade, scope, target, signature);
        }

        if (!DumbService.isDumb(project)) {
            PsiPackage psiPackage = facade.findPackage(prefix);
            if (psiPackage != null) return List.of(psiPackage);
        }

        PsiDirectory packageDirectory = findPackageDirectory(project, prefix);
        if (packageDirectory != null) return List.of(packageDirectory);

        PsiClass psiClass = findClass(project, facade, scope, prefix);
        if (psiClass != null) {
            return List.of(psiClass);
        }

        return List.of();
    }

    private static List<PsiElement> resolveStaticMethods(
            Project project,
            JavaPsiFacade facade,
            GlobalSearchScope scope,
            MniJavaTargetParser.Target target,
            Optional<MniFunctionSignature> signature
    ) {
        PsiClass owner = findClass(project, facade, scope, target.ownerQualifiedName());
        if (owner == null) {
            return List.of();
        }

        List<PsiElement> members = new ArrayList<>();
        List<PsiElement> exactBindings = new ArrayList<>();
        // The MNI target names the exact owner. Asking PSI to walk inherited
        // members consults the Java class index and breaks navigation while
        // IDEA is indexing, even when we already found the source class.
        for (PsiMethod method : owner.findMethodsByName(target.memberName(), false)) {
            if (method.hasModifierProperty(PsiModifier.PUBLIC) && method.hasModifierProperty(PsiModifier.STATIC)) {
                members.add(method);
                if (signature.isPresent() && signature.get().name().equals(target.memberName()) &&
                        MniJavaAnnotation.from(method).filter(signature.get()::parametersMatch).isPresent()) {
                    exactBindings.add(method);
                }
            }
        }
        return exactBindings.isEmpty() ? List.copyOf(members) : List.copyOf(exactBindings);
    }

    private static PsiClass findClass(
            Project project,
            JavaPsiFacade facade,
            GlobalSearchScope scope,
            String qualifiedName
    ) {
        if (!DumbService.isDumb(project)) {
            PsiClass indexed = facade.findClass(qualifiedName, scope);
            if (indexed != null) return indexed;
        }

        String relativePath = qualifiedName.replace('.', '/') + ".java";
        PsiManager psiManager = PsiManager.getInstance(project);
        for (VirtualFile root : fallbackRoots(project)) {
            VirtualFile source = root.findFileByRelativePath(relativePath);
            if (source == null || !(psiManager.findFile(source) instanceof PsiJavaFile javaFile)) continue;
            for (PsiClass candidate : javaFile.getClasses()) {
                if (qualifiedName.equals(candidate.getQualifiedName())) return candidate;
            }
        }
        return null;
    }

    private static PsiDirectory findPackageDirectory(Project project, String qualifiedName) {
        String relativePath = qualifiedName.replace('.', '/');
        PsiManager psiManager = PsiManager.getInstance(project);
        for (VirtualFile root : fallbackRoots(project)) {
            VirtualFile directory = root.findFileByRelativePath(relativePath);
            if (directory != null && directory.isDirectory()) return psiManager.findDirectory(directory);
        }
        return null;
    }

    private static List<VirtualFile> fallbackRoots(Project project) {
        LinkedHashSet<VirtualFile> roots = new LinkedHashSet<>();
        for (VirtualFile contentRoot : ProjectRootManager.getInstance(project).getContentRoots()) {
            addRoot(roots, contentRoot);
            addRelativeRoot(roots, contentRoot, "src/main/java");
            addRelativeRoot(roots, contentRoot, "src/test/java");
        }
        String basePath = project.getBasePath();
        if (basePath != null) {
            VirtualFile base = LocalFileSystem.getInstance().findFileByNioFile(Path.of(basePath));
            if (base != null) {
                addRoot(roots, base);
                addRelativeRoot(roots, base, "src/main/java");
                addRelativeRoot(roots, base, "src/test/java");
            }
        }
        roots.addAll(McfppLibraryRootsProvider.sourceRoots(project));
        return List.copyOf(roots);
    }

    private static void addRelativeRoot(LinkedHashSet<VirtualFile> roots, VirtualFile base, String path) {
        VirtualFile root = base.findFileByRelativePath(path);
        if (root != null && root.isDirectory()) roots.add(root);
    }

    private static void addRoot(LinkedHashSet<VirtualFile> roots, VirtualFile root) {
        if (root.isDirectory()) roots.add(root);
    }
}
