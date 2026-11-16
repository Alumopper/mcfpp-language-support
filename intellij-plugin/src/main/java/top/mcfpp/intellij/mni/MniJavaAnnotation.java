package top.mcfpp.intellij.mni;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Compiler-facing values from {@code @MNIFunction} and {@code @MNIMacro}, read without
 * loading compiler classes. Macro parameter modes are inferred from the JVM wrapper
 * types, matching the compiler's annotation contract.
 */
record MniJavaAnnotation(
        @NotNull List<String> readOnlyTypes,
        @NotNull List<String> normalTypes,
        @NotNull List<String> parameterModes,
        @NotNull String returnType,
        boolean macro
) {
    static final String FUNCTION_QUALIFIED_NAME = "top.mcfpp.annotations.MNIFunction";
    static final String MACRO_QUALIFIED_NAME = "top.mcfpp.annotations.MNIMacro";
    /** Retained for callers that validate native-function bindings. */
    static final String QUALIFIED_NAME = FUNCTION_QUALIFIED_NAME;
    private static final String API = "top.mcfpp.mni.api.";

    MniJavaAnnotation {
        readOnlyTypes = List.copyOf(readOnlyTypes);
        normalTypes = List.copyOf(normalTypes);
        parameterModes = List.copyOf(parameterModes);
    }

    static Optional<MniJavaAnnotation> from(@NotNull PsiMethod method) {
        PsiAnnotation macro = method.getAnnotation(MACRO_QUALIFIED_NAME);
        if (macro != null) return macro(method, macro);
        PsiAnnotation annotation = method.getAnnotation(FUNCTION_QUALIFIED_NAME);
        if (annotation == null) return Optional.empty();
        Optional<List<String>> readOnly = stringArray(annotation, "readOnlyParams");
        Optional<List<String>> normal = stringArray(annotation, "normalParams");
        if (readOnly.isEmpty() || normal.isEmpty()) return Optional.empty();
        String result = string(annotation, "returnType").orElse("void");
        return Optional.of(new MniJavaAnnotation(readOnly.get(), normal.get(), List.of(), result, false));
    }

    static boolean isMacro(@NotNull PsiMethod method) {
        return method.getAnnotation(MACRO_QUALIFIED_NAME) != null;
    }

    private static Optional<MniJavaAnnotation> macro(@NotNull PsiMethod method, @NotNull PsiAnnotation annotation) {
        Optional<List<String>> normal = stringArray(annotation, "normalParams");
        if (normal.isEmpty()) return Optional.empty();
        PsiParameter[] parameters = method.getParameterList().getParameters();
        if (parameters.length == 0 || !parameters[0].getType().getCanonicalText()
                .equals(API + "MacroContext")) return Optional.empty();
        List<String> modes = new ArrayList<>();
        for (int index = 1; index < parameters.length; index++) {
            modes.add(mode(parameters[index].getType().getCanonicalText()));
        }
        String result = string(annotation, "returnType").orElse("void");
        return Optional.of(new MniJavaAnnotation(List.of(), normal.get(), modes, result, true));
    }

    String parametersText() {
        String readOnly = readOnlyTypes.isEmpty() ? "" : "<" + String.join(", ", readOnlyTypes) + ">";
        return readOnly + "(" + String.join(", ", normalTypes) + ")";
    }

    private static String mode(String canonicalType) {
        if (canonicalType.startsWith(API + "Ref<")) return "REF";
        if (canonicalType.startsWith(API + "Const<")) return "CONST";
        if (canonicalType.startsWith(API + "InlineFn<")) return "INLINE";
        return "VALUE";
    }

    private static Optional<String> string(PsiAnnotation annotation, String attributeName) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attributeName);
        if (value == null) return Optional.empty();
        return stringConstant(value);
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
