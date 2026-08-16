package top.mcfpp.intellij.mni;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.lang.McfppFile;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Reports native bindings that the MCFPP compiler cannot link to a Java {@code @MNIFunction}. */
public final class McfppMniBindingInspection extends LocalInspectionTool {
    @Override
    public @NotNull String getDisplayName() {
        return "Invalid MCFPP native Java binding";
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new PsiElementVisitor() {
            @Override
            public void visitFile(@NotNull PsiFile file) {
                if (!(file instanceof McfppFile)) return;
                inspectFile(file, holder);
            }
        };
    }

    private static void inspectFile(PsiFile file, ProblemsHolder holder) {
        CharSequence source = file.getViewProvider().getContents();
        JavaPsiFacade facade = JavaPsiFacade.getInstance(file.getProject());
        GlobalSearchScope scope = GlobalSearchScope.allScope(file.getProject());
        for (MniJavaTargetParser.Target target : MniJavaTargetParser.parse(source)) {
            PsiClass owner = facade.findClass(target.ownerQualifiedName(), scope);
            if (owner == null) {
                String subject = target.kind() == MniJavaTargetParser.Kind.FROM ? "@From class" : "MNI Java class";
                report(holder, file, ownerSegment(target),
                        subject + " '" + target.ownerQualifiedName() + "' cannot be resolved");
                continue;
            }
            if (target.kind() == MniJavaTargetParser.Kind.FROM) continue;
            inspectMethodBinding(file, holder, target, owner, MniFunctionSignature.from(source, target));
        }
    }

    private static void inspectMethodBinding(
            PsiFile file,
            ProblemsHolder holder,
            MniJavaTargetParser.Target target,
            PsiClass owner,
            Optional<MniFunctionSignature> parsedSignature
    ) {
        MniJavaTargetParser.Segment methodSegment = target.segments().getLast();
        if (parsedSignature.isEmpty()) {
            // Operators and accessors have different signatures in the compiler. Keep class/member resolution useful
            // without pretending that a plain function signature applies to them.
            if (publicMethods(owner, target.memberName()).isEmpty()) {
                report(holder, file, methodSegment,
                        "No public Java method '" + target.memberName() + "' found in '" +
                                target.ownerQualifiedName() + "'");
            }
            return;
        }

        MniFunctionSignature signature = parsedSignature.get();
        if (!signature.name().equals(target.memberName())) {
            report(holder, file, methodSegment,
                    "MCFPP compiler searches for Java method '" + signature.name() +
                            "'; the referenced member has a different name");
            return;
        }

        List<PsiMethod> publicMethods = publicMethods(owner, signature.name());
        if (publicMethods.isEmpty()) {
            report(holder, file, methodSegment,
                    "No public Java method '" + signature.name() + "' found in '" +
                            target.ownerQualifiedName() + "'");
            return;
        }

        List<PsiMethod> annotatedMethods = publicMethods.stream()
                .filter(method -> method.getAnnotation(MniJavaAnnotation.QUALIFIED_NAME) != null)
                .toList();
        if (annotatedMethods.isEmpty()) {
            report(holder, file, methodSegment,
                    "Java method '" + signature.name() + "' must be annotated with @MNIFunction");
            return;
        }

        List<PsiMethod> matchingMethods = annotatedMethods.stream()
                .filter(method -> MniJavaAnnotation.from(method).filter(signature::parametersMatch).isPresent())
                .toList();
        if (matchingMethods.isEmpty()) {
            report(holder, file, methodSegment,
                    "No @MNIFunction overload matches MCFPP parameters " + signature.parametersText());
            return;
        }

        List<PsiMethod> staticMethods = matchingMethods.stream()
                .filter(method -> method.hasModifierProperty(PsiModifier.STATIC))
                .toList();
        if (staticMethods.isEmpty()) {
            report(holder, file, methodSegment, "MNI Java method must be static");
            return;
        }

        int expectedCount = signature.javaArgumentCount();
        boolean validCount = staticMethods.stream()
                .anyMatch(method -> method.getParameterList().getParametersCount() == expectedCount);
        if (!validCount) {
            String actualCounts = staticMethods.stream()
                    .map(method -> Integer.toString(method.getParameterList().getParametersCount()))
                    .distinct()
                    .sorted()
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("0");
            report(holder, file, methodSegment,
                    "MNI Java method has " + actualCounts + " parameter(s); this binding passes " +
                            expectedCount);
        }
    }

    private static List<PsiMethod> publicMethods(PsiClass owner, String name) {
        return Arrays.stream(owner.findMethodsByName(name, true))
                .filter(method -> method.hasModifierProperty(PsiModifier.PUBLIC))
                .toList();
    }

    private static MniJavaTargetParser.Segment ownerSegment(MniJavaTargetParser.Target target) {
        int ownerOrdinal = target.kind() == MniJavaTargetParser.Kind.JAVA_METHOD
                ? target.segments().size() - 2
                : target.segments().size() - 1;
        return target.segments().get(ownerOrdinal);
    }

    private static void report(
            ProblemsHolder holder,
            PsiFile file,
            MniJavaTargetParser.Segment segment,
            String message
    ) {
        PsiElement element = file.findElementAt(segment.startOffset());
        if (element == null) return;
        TextRange elementRange = element.getTextRange();
        int start = Math.max(0, segment.startOffset() - elementRange.getStartOffset());
        int end = Math.min(elementRange.getLength(), segment.endOffset() - elementRange.getStartOffset());
        if (start >= end) {
            holder.registerProblem(element, message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING);
        } else {
            holder.registerProblem(
                    element,
                    message,
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    new TextRange(start, end)
            );
        }
    }
}
