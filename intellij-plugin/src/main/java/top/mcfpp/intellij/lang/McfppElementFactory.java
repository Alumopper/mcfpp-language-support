package top.mcfpp.intellij.lang;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import org.jetbrains.annotations.NotNull;

public final class McfppElementFactory {
    private McfppElementFactory() {
    }

    public static @NotNull PsiElement createIdentifier(@NotNull Project project, @NotNull String name) {
        if (!McfppNames.isIdentifier(name)) throw new IllegalArgumentException("Invalid MCFPP identifier: " + name);
        PsiFile file = PsiFileFactory.getInstance(project).createFileFromText(
                "identifier.mcfpp",
                McfppFileType.INSTANCE,
                "var " + name + ";"
        );
        PsiElement identifier = file.findElementAt(4);
        if (identifier == null) throw new IllegalStateException("MCFPP lexer did not create an identifier");
        return identifier;
    }
}
