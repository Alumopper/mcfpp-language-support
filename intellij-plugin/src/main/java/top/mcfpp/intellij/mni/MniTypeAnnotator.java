package top.mcfpp.intellij.mni;

import com.intellij.lang.annotation.*;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

public final class MniTypeAnnotator implements Annotator {
    @Override public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        var context = MniTypeContext.from(element);
        if (context == null) return;
        var syntax = context.syntax();
        for (var problem : syntax.problems()) error(context, holder, problem.start(), problem.end(), problem.message());
        if (!syntax.problems().isEmpty()) return;
        var generics = context.generics();
        for (var name : syntax.names()) {
            boolean generic = name.namespace() == null && generics.containsKey(name.text());
            var range = context.range(name.identifierStart(), name.end()).shiftRight(element.getTextOffset());
            if (!range.isEmpty()) holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range)
                    .textAttributes(DefaultLanguageHighlighterColors.CLASS_NAME).create();
            if (name.text().equals("void") && context.field != MniTypeContext.Field.RETURN && context.field != MniTypeContext.Field.CALLER) {
                error(context, holder, name.start(), name.end(), "void is not an MCFPP parameter type");
                continue;
            }
            if (name.text().equals("null")) {
                if (context.field != MniTypeContext.Field.OPERATOR_PARAMETER)
                    error(context, holder, name.start(), name.end(), "null is only supported as an operator parameter type");
                continue;
            }
            if (name.builtin() || generic || context.field == MniTypeContext.Field.GENERIC || DumbService.isDumb(element.getProject())) continue;
            var reference = new MniTypeReference(context, name);
            var targets = reference.multiResolve(false);
            if (targets.length == 0) error(context, holder, name.start(), name.end(), "Unknown MCFPP type '" + name.text() + "'");
            else if (targets.length > 1) error(context, holder, name.start(), name.end(), "Ambiguous MCFPP type '" + name.text() + "'; use a namespace-qualified name");
            else {
                var arity = MniTypeSyntax.arity(targets[0].getElement());
                if (arity != null && (name.arguments() < arity.required() || name.arguments() > arity.total()) && context.field != MniTypeContext.Field.CALLER)
                    error(context, holder, name.start(), name.end(), "MCFPP type '" + name.text() + "' expects " +
                            (arity.required() == arity.total() ? Integer.toString(arity.total()) : arity.required() + ".." + arity.total()) + " type/readonly arguments, found " + name.arguments());
            }
        }
    }

    private static void error(MniTypeContext context, AnnotationHolder holder, int start, int end, String message) {
        var range = context.range(start, end).shiftRight(context.host.getTextOffset());
        if (range.isEmpty()) range = context.host.getTextRange();
        holder.newAnnotation(HighlightSeverity.ERROR, message).range(range).create();
    }
}
