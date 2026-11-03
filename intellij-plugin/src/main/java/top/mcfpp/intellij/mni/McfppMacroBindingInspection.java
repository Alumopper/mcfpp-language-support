package top.mcfpp.intellij.mni;
import com.intellij.codeInspection.*;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import java.util.*;
public final class McfppMacroBindingInspection extends AbstractBaseJavaLocalInspectionTool {
    @Override public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,boolean onTheFly) {
        return new JavaElementVisitor() {
            @Override public void visitMethod(@NotNull PsiMethod method) {
                var annotation=method.getAnnotation(McfppMacroIndex.ANNOTATION); if(annotation==null) return;
                var parameters=method.getParameterList().getParameters();
                var macroParameters=McfppMacroIndex.parameters(annotation);
                String problem=null;
                if(!method.hasModifierProperty(PsiModifier.PUBLIC) || !method.hasModifierProperty(PsiModifier.STATIC))
                    problem="An MNI macro entry must be public static.";
                else if(parameters.length==0 || !parameters[0].getType().equalsToText("top.mcfpp.mni.api.MacroContext"))
                    problem="The first MNI macro parameter must be MacroContext.";
                else if(parameters.length!=macroParameters.size()+1)
                    problem="Java parameter count does not match @MNIMacro params.";
                else {
                    for(int i=0;i<macroParameters.size();i++) {
                        var parameter=macroParameters.get(i);
                        String wrapper=switch(Objects.requireNonNullElse(parameter.mode,"VALUE")) {
                            case "REF" -> "Ref"; case "CONST" -> "Const"; case "INLINE" -> "InlineFn"; default -> "Value";
                        };
                        String actual=parameters[i+1].getType().getCanonicalText();
                        if(!actual.startsWith("top.mcfpp.mni.api."+wrapper+"<")) { problem="Macro parameter '"+parameter.name+"' requires "+wrapper+"<…>."; break; }
                    }
                }
                String result=McfppMacroIndex.constant(annotation.findAttributeValue("returnType"));
                String javaType=method.getReturnType()==null ? "" : method.getReturnType().getCanonicalText();
                if(problem==null && (result.isEmpty() || result.equals("void")) && !javaType.equals("top.mcfpp.mni.api.Block"))
                    problem="A void MNI macro returns Block.";
                if(problem==null && !result.isEmpty() && !result.equals("void") && !javaType.startsWith("top.mcfpp.mni.api.Expr<"))
                    problem="An MNI macro with a value result returns Expr<T>.";
                if(problem!=null) holder.registerProblem(annotation,problem);
            }
        };
    }
}

