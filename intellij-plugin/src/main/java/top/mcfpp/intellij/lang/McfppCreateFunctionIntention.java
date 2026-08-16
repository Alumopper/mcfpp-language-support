package top.mcfpp.intellij.lang;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;

public final class McfppCreateFunctionIntention extends McfppCreateDeclarationIntention {
    @Override
    public @NotNull String getFamilyName() {
        return "Create MCFPP function";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        PsiElement identifier = unresolvedIdentifier(project, element);
        if (identifier == null || McfppCallContext.fromCallee(identifier) == null) return false;
        setText("Create MCFPP function '" + identifier.getText() + "'");
        return true;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        PsiElement identifier = unresolvedIdentifier(project, element);
        if (identifier == null || !(identifier.getContainingFile() instanceof McfppFile file)) return;
        McfppCallContext call = McfppCallContext.fromCallee(identifier);
        if (call == null) return;

        HashSet<String> usedNames = new HashSet<>();
        List<McfppArgumentInference.Parameter> readOnly = McfppArgumentInference.infer(
                file, call.readOnlyArguments(), "readOnly", usedNames);
        List<McfppArgumentInference.Parameter> normal = McfppArgumentInference.infer(
                file, call.arguments(), "arg", usedNames);
        String readOnlyParameters = declarations(readOnly);
        String header = "func " + identifier.getText() +
                (readOnlyParameters.isEmpty() ? "" : '<' + readOnlyParameters + '>') +
                '(' + declarations(normal) + ") {\n    \n}\n";
        insertTopLevelDeclaration(project, editor, file, header, header.indexOf("    \n") + 4);
    }

    private static String declarations(List<McfppArgumentInference.Parameter> parameters) {
        return parameters.stream()
                .map(McfppArgumentInference.Parameter::declaration)
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
