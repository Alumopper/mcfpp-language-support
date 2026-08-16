package top.mcfpp.intellij.mni;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Compiler-facing values from {@code top.mcfpp.annotations.MNIFunction}, read without loading compiler classes. */
record MniJavaAnnotation(
        @NotNull List<String> readOnlyTypes,
        @NotNull List<String> normalTypes
) {
    static final String QUALIFIED_NAME = "top.mcfpp.annotations.MNIFunction";

    MniJavaAnnotation {
        readOnlyTypes = List.copyOf(readOnlyTypes);
        normalTypes = List.copyOf(normalTypes);
    }

    static Optional<MniJavaAnnotation> from(@NotNull PsiMethod method) {
        PsiAnnotation annotation = method.getAnnotation(QUALIFIED_NAME);
        if (annotation == null) return Optional.empty();
        Optional<List<String>> readOnly = stringArray(annotation, "readOnlyParams");
        Optional<List<String>> normal = stringArray(annotation, "normalParams");
        if (readOnly.isEmpty() || normal.isEmpty()) return Optional.empty();
        return Optional.of(new MniJavaAnnotation(readOnly.get(), normal.get()));
    }

    String parametersText() {
        String readOnly = readOnlyTypes.isEmpty() ? "" : "<" + String.join(", ", readOnlyTypes) + ">";
        return readOnly + "(" + String.join(", ", normalTypes) + ")";
    }

    private static Optional<List<String>> stringArray(PsiAnnotation annotation, String attributeName) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attributeName);
        if (value == null) return Optional.of(List.of());
        List<String> values = new ArrayList<>();
        if (value instanceof PsiArrayInitializerMemberValue array) {
            for (PsiAnnotationMemberValue initializer : array.getInitializers()) {
                Optional<String> constant = stringConstant(initializer);
                if (constant.isEmpty()) return Optional.empty();
                values.add(annotationType(constant.get()));
            }
        } else {
            Optional<String> constant = stringConstant(value);
            if (constant.isEmpty()) return Optional.empty();
            values.add(annotationType(constant.get()));
        }
        return Optional.of(List.copyOf(values));
    }

    private static Optional<String> stringConstant(PsiAnnotationMemberValue value) {
        if (!(value instanceof PsiExpression expression)) return Optional.empty();
        Object constant = JavaPsiFacade.getInstance(value.getProject())
                .getConstantEvaluationHelper()
                .computeConstantExpression(expression);
        return constant instanceof String text ? Optional.of(text) : Optional.empty();
    }

    /** Mirrors the explicit native-function compiler path, which uses the last whitespace-delimited token. */
    private static String annotationType(String declaration) {
        String beforeDefault = declaration.split("=", 2)[0].strip();
        if (beforeDefault.isEmpty()) return "";
        String[] words = beforeDefault.split("\\s+");
        return MniFunctionSignature.normalizeType(words[words.length - 1]);
    }
}
