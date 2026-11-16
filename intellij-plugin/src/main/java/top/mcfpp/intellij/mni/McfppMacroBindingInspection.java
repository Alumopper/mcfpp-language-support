package top.mcfpp.intellij.mni;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/** Validates the compiler-facing shape of {@code @MNIMacro} provider methods. */
public final class McfppMacroBindingInspection extends AbstractBaseJavaLocalInspectionTool {
    private static final String API = "top.mcfpp.mni.api.";

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean onTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                PsiAnnotation annotation = method.getAnnotation(McfppMacroIndex.ANNOTATION);
                if (annotation == null) return;
                String problem = null;
                Optional<MniJavaAnnotation> parsed = MniJavaAnnotation.from(method);
                PsiParameter[] parameters = method.getParameterList().getParameters();
                if (!method.hasModifierProperty(PsiModifier.PUBLIC) ||
                        !method.hasModifierProperty(PsiModifier.STATIC)) {
                    problem = "An MNI macro entry must be public static.";
                } else if (parameters.length == 0 || !parameters[0].getType().getCanonicalText().equals(API + "MacroContext")) {
                    problem = "The first MNI macro parameter must be MacroContext.";
                } else if (parsed.isEmpty()) {
                    problem = "@MNIMacro normalParams and the JVM wrapper parameters could not be read.";
                } else {
                    MniJavaAnnotation value = parsed.get();
                    List<String> modes = value.parameterModes();
                    for (int index = 0; index < modes.size(); index++) {
                        String wrapper = switch (modes.get(index)) {
                            case "REF" -> "Ref";
                            case "CONST" -> "Const";
                            case "INLINE" -> "InlineFn";
                            default -> "Value";
                        };
                        String actual = parameters[index + 1].getType().getCanonicalText();
                        if (!actual.startsWith(API + wrapper + "<")) {
                            problem = "Macro parameter " + (index + 1) + " requires " + wrapper + "<…> for mode " +
                                    modes.get(index) + ".";
                            break;
                        }
                    }
                    if (problem == null) {
                        String result = value.returnType();
                        String javaType = method.getReturnType() == null ? "" : method.getReturnType().getCanonicalText();
                        if ((result.isEmpty() || result.equals("void")) && !javaType.equals(API + "Block")) {
                            problem = "A void MNI macro returns Block.";
                        } else if (!result.isEmpty() && !result.equals("void") && !javaType.startsWith(API + "Expr<")) {
                            problem = "An MNI macro with a value result returns Expr<T>.";
                        }
                    }
                }
                if (problem != null) holder.registerProblem(annotation, problem);
            }
        };
    }
}
