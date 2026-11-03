package top.mcfpp.intellij.mni;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.uast.*;
import org.jetbrains.uast.expressions.UInjectionHost;

import java.util.*;

/** Exact annotation/attribute matching shared by Java and Kotlin editors. */
final class MniTypeContext {
    private static final String PACKAGE = "top.mcfpp.annotations.";
    enum Field { TYPE, PARAMETER, RETURN, CALLER, OPERATOR_PARAMETER, GENERIC }
    final PsiLanguageInjectionHost host;
    final String text;
    final Field field;
    final UAnnotation annotation;
    final UMethod method;
    final boolean macro;
    final LiteralTextEscaper<?> escaper;
    final TextRange valueRange;

    private MniTypeContext(PsiLanguageInjectionHost host, String text, Field field, UAnnotation annotation,
                           UMethod method, LiteralTextEscaper<?> escaper, TextRange valueRange) {
        this.host = host; this.text = text; this.field = field; this.annotation = annotation;
        this.method = method; this.escaper = escaper; this.valueRange = valueRange;
        this.macro = Set.of(PACKAGE + "MNIMacro", PACKAGE + "MacroParam").contains(annotation.getQualifiedName());
    }

    static MniTypeContext from(PsiElement element) {
        if (!(element instanceof PsiLanguageInjectionHost host) || !host.isValidHost()) return null;
        if (!Set.of("JAVA", "kotlin").contains(host.getLanguage().getID())) return null;
        UExpression expression = UastContextKt.toUElement(host, UExpression.class);
        if (!(expression instanceof UInjectionHost injection) || injection.evaluateToString() == null) return null;
        UAnnotation annotation = null;
        String attribute = null;
        for (UElement parent = expression.getUastParent(); parent != null; parent = parent.getUastParent()) {
            if (parent instanceof UNamedExpression named) attribute = named.getName();
            if (parent instanceof UAnnotation found) { annotation = found; break; }
            // A string nested in an arbitrary computation is not itself an annotation type expression.
            if (parent instanceof UBinaryExpression) return null;
        }
        if (annotation == null) return null;
        Field field = field(annotation.getQualifiedName(), attribute == null ? "value" : attribute);
        if (field == null) return null;
        UMethod method = null;
        for (UElement parent = annotation.getUastParent(); parent != null; parent = parent.getUastParent())
            if (parent instanceof UMethod found) { method = found; break; }
        TextRange valueRange = ElementManipulators.getValueTextRange(host);
        var escaper = host.createLiteralTextEscaper();
        var decoded = new StringBuilder();
        if (!escaper.decode(valueRange, decoded)) return null;
        return new MniTypeContext(host, decoded.toString(), field, annotation, method, escaper, valueRange);
    }

    private static Field field(String annotation, String attribute) {
        if (annotation == null) return null;
        return switch (annotation) {
            case PACKAGE + "MNIFunction" -> switch (attribute) {
                case "normalParams", "readOnlyParams" -> Field.PARAMETER;
                case "caller" -> Field.CALLER;
                case "returnType" -> Field.RETURN;
                case "genericType" -> Field.GENERIC;
                default -> null;
            };
            case PACKAGE + "MNIMacro" -> attribute.equals("returnType") ? Field.RETURN : null;
            case PACKAGE + "MacroParam" -> attribute.equals("type") ? Field.TYPE : null;
            case PACKAGE + "MNIOperator" -> switch (attribute) {
                case "paramType" -> Field.OPERATOR_PARAMETER;
                case "returnType" -> Field.RETURN;
                default -> null;
            };
            default -> null;
        };
    }

    MniTypeSyntax.Result syntax() {
        if (field == Field.GENERIC) {
            String name = text.trim(); int start = text.indexOf(name);
            boolean valid = name.matches("[\\p{L}_][\\p{L}\\p{N}_]*") && !MniTypeSyntax.BUILTINS.contains(name);
            return new MniTypeSyntax.Result(valid ? List.of(new MniTypeSyntax.Name(name, start, start + name.length(), 0, false)) : List.of(),
                    valid ? List.of() : List.of(new MniTypeSyntax.Problem(0, text.length(), "Invalid MCFPP generic parameter name")), text.length());
        }
        return MniTypeSyntax.parse(text, field == Field.PARAMETER, macro, field == Field.OPERATOR_PARAMETER);
    }

    TextRange range(int start, int end) {
        int from = escaper.getOffsetInHost(Math.clamp(start, 0, text.length()), valueRange);
        int to = escaper.getOffsetInHost(Math.clamp(end, 0, text.length()), valueRange);
        if (from < 0) from = valueRange.getStartOffset();
        if (to < from) to = from;
        if (to == from && from < valueRange.getEndOffset()) to++;
        return new TextRange(from, to);
    }

    int decodedOffset(int hostOffset) {
        for (int i = 0; i <= text.length(); i++)
            if (escaper.getOffsetInHost(i, valueRange) >= hostOffset) return i;
        return text.length();
    }

    Map<String, MniTypeParameter> generics() {
        if (method == null || method.getSourcePsi() == null) return Map.of();
        Map<String, MniTypeParameter> result = new LinkedHashMap<>();
        for (var literal : PsiTreeUtil.findChildrenOfType(method.getSourcePsi(), PsiLanguageInjectionHost.class)) {
            MniTypeContext declaration = from(literal);
            if (declaration != null && declaration.field == Field.GENERIC && declaration.method != null &&
                    Objects.equals(declaration.method.getSourcePsi(), method.getSourcePsi()) && declaration.syntax().problems().isEmpty())
                result.putIfAbsent(declaration.text.trim(), new MniTypeParameter(declaration));
        }
        return result;
    }

    PsiReference[] references() {
        return syntax().names().stream().filter(name -> !name.builtin() && !name.text().equals("null"))
                .map(name -> new MniTypeReference(this, name)).toArray(PsiReference[]::new);
    }
}
