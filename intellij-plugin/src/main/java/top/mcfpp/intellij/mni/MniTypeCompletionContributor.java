package top.mcfpp.intellij.mni;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.lang.*;

import java.util.*;

public final class MniTypeCompletionContributor extends CompletionContributor {
    public MniTypeCompletionContributor() {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), new CompletionProvider<>() {
            @Override protected void addCompletions(@NotNull CompletionParameters parameters,
                    @NotNull ProcessingContext processing, @NotNull CompletionResultSet result) {
                // Kotlin UAST resolves annotations on the physical file, not completion's temporary PSI copy.
                var leaf = parameters.getOriginalFile().findElementAt(Math.max(0, parameters.getOffset() - 1));
                var host = PsiTreeUtil.getParentOfType(leaf, PsiLanguageInjectionHost.class, false);
                var context = MniTypeContext.from(host);
                if (context == null || context.field == MniTypeContext.Field.GENERIC) return;
                int offset = context.decodedOffset(parameters.getOffset() - host.getTextOffset());
                String before = context.text.substring(0, Math.min(offset, context.text.length()));
                if (context.field == MniTypeContext.Field.PARAMETER && before.contains("=")) return;
                int start = before.length();
                while (start > 0 && (Character.isUnicodeIdentifierPart(before.charAt(start - 1)) || ".:".indexOf(before.charAt(start - 1)) >= 0)) start--;
                String prefix = before.substring(start);
                CompletionResultSet matches = result.withPrefixMatcher(prefix);
                Set<String> added = new LinkedHashSet<>();
                for (String type : MniTypeSyntax.BUILTINS) {
                    if (type.equals("void") && context.field != MniTypeContext.Field.RETURN && context.field != MniTypeContext.Field.CALLER && !before.contains("->")) continue;
                    matches.addElement(LookupElementBuilder.create(type).withTypeText("MCFPP type", true));
                    added.add(type);
                }
                if (context.field == MniTypeContext.Field.OPERATOR_PARAMETER)
                    matches.addElement(LookupElementBuilder.create("null").withTypeText("MCFPP null type", true));
                for (String type : context.generics().keySet())
                    matches.addElement(LookupElementBuilder.create(type).withTypeText("MCFPP type parameter", true));
                for (String type : McfppAnnotationTypeIndex.names(host.getProject())) {
                    for (var target : McfppAnnotationTypeIndex.find(host.getProject(), type)) {
                        var symbol = McfppFileModels.get(target.getContainingFile()).declarationAt(target.getTextOffset());
                        if (symbol == null) continue;
                        String qualified = symbol.qualifiedName();
                        if (added.add(qualified)) matches.addElement(LookupElementBuilder.create(target, qualified).withLookupString(type)
                                .withPresentableText(qualified).withTypeText("MCFPP type", true));
                    }
                }
            }
        });
    }
}
