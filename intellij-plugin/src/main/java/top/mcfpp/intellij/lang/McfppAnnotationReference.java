package top.mcfpp.intellij.lang;

import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiReferenceBase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.mcfpp.intellij.mni.McfppLibraryRootsProvider;

import java.util.regex.Pattern;

/** MCFPP annotations are implemented by compiler classes, including the bundled test API. */
final class McfppAnnotationReference extends PsiReferenceBase<PsiElement> {
    McfppAnnotationReference(@NotNull PsiElement element) {
        super(element, TextRange.create(1, element.getTextLength()), true);
    }

    @Override
    public @Nullable PsiElement resolve() {
        String name = getValue();
        for (VirtualFile root : McfppLibraryRootsProvider.compilerSourceRoots(myElement.getProject())) {
            VirtualFile file = root.findFileByRelativePath("top/mcfpp/mni/annotation/" + name + ".java");
            PsiElement target = declaration(file, name);
            if (target != null) return target;
            if (java.util.Set.of("Test", "BeforeEach", "AfterEach", "Disabled").contains(name)) {
                target = declaration(root.findFileByRelativePath("top/mcfpp/testing/TestAnnotations.kt"), name);
                if (target != null) return target;
            }
        }
        return null;
    }

    private @Nullable PsiElement declaration(@Nullable VirtualFile source, String name) {
        if (source == null) return null;
        var file = PsiManager.getInstance(myElement.getProject()).findFile(source);
        if (file == null) return null;
        var declaration = Pattern.compile("\\bclass\\s+(" + Pattern.quote(name) + ")\\b").matcher(file.getText());
        return declaration.find() ? file.findElementAt(declaration.start(1)) : null;
    }
}
