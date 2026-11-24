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
import java.util.regex.Pattern;
import top.mcfpp.language.MniSymbolSnapshot;

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
    private static final Pattern CALLBACK_MODE = Pattern.compile("^(ref|const|value|inline)\\s+(.+)$", Pattern.DOTALL);

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
        Optional<List<String>> readOnly = stringArray(annotation, "readOnlyParams").map(MniJavaAnnotation::nativeTypes);
        Optional<List<String>> normal = stringArray(annotation, "normalParams").map(MniJavaAnnotation::nativeTypes);
        if (readOnly.isEmpty() || normal.isEmpty()) return Optional.empty();
        String result = string(annotation, "returnType").orElse("void");
        return Optional.of(new MniJavaAnnotation(readOnly.get(), normal.get(), List.of(), result, false));
    }

    static boolean isMacro(@NotNull PsiMethod method) {
        return method.getAnnotation(MACRO_QUALIFIED_NAME) != null;
    }

    private static Optional<MniJavaAnnotation> macro(@NotNull PsiMethod method, @NotNull PsiAnnotation annotation) {
        Optional<List<String>> normal = stringArray(annotation, "normalParams")
                .map(types -> types.stream().map(MniJavaAnnotation::callbackType).toList());
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

    private static List<String> nativeTypes(List<String> types) {
        return types.stream().map(MniJavaAnnotation::annotationType).toList();
    }

    static List<MniSymbolSnapshot.Parameter> callbackParameters(PsiMethod method, int parameterIndex) {
        PsiAnnotation annotation = method.getAnnotation(MACRO_QUALIFIED_NAME);
        if (annotation == null) return List.of();
        var types = stringArray(annotation, "normalParams").orElse(List.of());
        return parameterIndex < types.size() ? callbackParameters(types.get(parameterIndex)) : List.of();
    }

    private static List<MniSymbolSnapshot.Parameter> callbackParameters(String type) {
        type = type.strip();
        int end = callbackEnd(type);
        if (end < 0) return List.of();
        List<MniSymbolSnapshot.Parameter> parameters = new ArrayList<>();
        for (String input : MniFunctionSignature.splitTopLevel(type.substring(1, end))) {
            var parameter = new MniSymbolSnapshot.Parameter();
            var matcher = CALLBACK_MODE.matcher(input.strip());
            boolean hasMode = matcher.matches();
            parameter.mode = hasMode ? matcher.group(1).toUpperCase(java.util.Locale.ROOT) : "VALUE";
            String source = hasMode ? matcher.group(2) : input;
            parameter.typeName = callbackType(source);
            parameter.macroCallbackParameters = callbackParameters(source);
            parameters.add(parameter);
        }
        return List.copyOf(parameters);
    }

    private static int callbackEnd(String type) {
        if (!type.startsWith("(")) return -1;
        int depth = 0;
        for (int index = 0; index < type.length(); index++) {
            char c = type.charAt(index);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return type.substring(index + 1).stripLeading().startsWith("->") ? index : -1;
        }
        return -1;
    }

    private static String callbackType(String type) {
        type = type.strip();
        int end = callbackEnd(type);
        if (end < 0) return MniFunctionSignature.normalizeType(type);
        return "(" + callbackParameters(type).stream().map(p -> p.typeName)
                .collect(java.util.stream.Collectors.joining(",")) + ")->" + callbackType(type.substring(end + 1).stripLeading().substring(2));
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
                values.add(constant.get());
            }
        } else {
            Optional<String> constant = stringConstant(value);
            if (constant.isEmpty()) return Optional.empty();
            values.add(constant.get());
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
