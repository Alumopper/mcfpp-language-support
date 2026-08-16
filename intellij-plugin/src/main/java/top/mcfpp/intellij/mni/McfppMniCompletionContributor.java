package top.mcfpp.intellij.mni;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.completion.PrioritizedLookupElement;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.lang.McfppLanguage;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public final class McfppMniCompletionContributor extends CompletionContributor {
    public McfppMniCompletionContributor() {
        extend(
                CompletionType.BASIC,
                PlatformPatterns.psiElement().withLanguage(McfppLanguage.INSTANCE),
                new MniCompletionProvider()
        );
    }

    private static final class MniCompletionProvider extends CompletionProvider<CompletionParameters> {
        private static final int RESULT_LIMIT = 256;

        @Override
        protected void addCompletions(
                @NotNull CompletionParameters parameters,
                @NotNull ProcessingContext context,
                @NotNull CompletionResultSet result
        ) {
            if (DumbService.isDumb(parameters.getPosition().getProject())) {
                return;
            }

            Optional<MniJavaTargetParser.CompletionContext> completion = MniJavaTargetParser.completionAt(
                    parameters.getEditor().getDocument().getCharsSequence(),
                    parameters.getOffset()
            );
            if (completion.isEmpty()) {
                return;
            }

            MniJavaTargetParser.CompletionContext mniContext = completion.get();
            CompletionResultSet prefixedResult = result.withPrefixMatcher(mniContext.namePrefix());
            JavaPsiFacade facade = JavaPsiFacade.getInstance(parameters.getPosition().getProject());
            GlobalSearchScope scope = GlobalSearchScope.allScope(parameters.getPosition().getProject());
            Set<String> seen = new HashSet<>();

            PsiClass qualifierClass = mniContext.qualifier().isEmpty()
                    ? null
                    : facade.findClass(mniContext.qualifier(), scope);
            if (qualifierClass != null) {
                if (mniContext.kind() == MniJavaTargetParser.Kind.JAVA_METHOD) {
                    addStaticMembers(prefixedResult, qualifierClass, mniContext.namePrefix(), seen);
                }
                addInnerClasses(prefixedResult, qualifierClass, mniContext.namePrefix(), seen);
                return;
            }

            PsiPackage psiPackage = facade.findPackage(mniContext.qualifier());
            if (psiPackage == null) {
                return;
            }
            addPackageChildren(prefixedResult, psiPackage, scope, mniContext.namePrefix(), seen);
        }

        private static void addPackageChildren(
                CompletionResultSet result,
                PsiPackage psiPackage,
                GlobalSearchScope scope,
                String prefix,
                Set<String> seen
        ) {
            int count = 0;
            for (PsiPackage child : psiPackage.getSubPackages(scope)) {
                ProgressManager.checkCanceled();
                String name = child.getName();
                if (name != null && StringUtil.startsWithIgnoreCase(name, prefix) && seen.add("p:" + name)) {
                    result.addElement(prioritized(LookupElementBuilder.create(child, name)
                            .withIcon(AllIcons.Nodes.Package)
                            .withTypeText("package", true)));
                    if (++count >= RESULT_LIMIT) return;
                }
            }
            for (PsiClass psiClass : psiPackage.getClasses(scope)) {
                ProgressManager.checkCanceled();
                String name = psiClass.getName();
                if (name != null && StringUtil.startsWithIgnoreCase(name, prefix) && seen.add("c:" + name)) {
                    result.addElement(prioritized(LookupElementBuilder.create(psiClass, name)
                            .withIcon(psiClass.getIcon(0))
                            .withTypeText(psiClass.getQualifiedName(), true)));
                    if (++count >= RESULT_LIMIT) return;
                }
            }
        }

        private static void addStaticMembers(
                CompletionResultSet result,
                PsiClass owner,
                String prefix,
                Set<String> seen
        ) {
            int count = 0;
            for (PsiMethod method : owner.getAllMethods()) {
                ProgressManager.checkCanceled();
                if (!isPublicStatic(method) ||
                        !StringUtil.startsWithIgnoreCase(method.getName(), prefix)) {
                    continue;
                }
                String signature = method.getName() + method.getParameterList().getText();
                if (!seen.add("m:" + signature)) {
                    continue;
                }
                Optional<MniJavaAnnotation> annotation = MniJavaAnnotation.from(method);
                LookupElementBuilder lookup = memberLookup(method, method.getName())
                        .withTailText(method.getParameterList().getText(), true)
                        .withTypeText(annotation
                                .map(value -> "@MNIFunction " + value.parametersText())
                                .orElseGet(() -> method.getReturnType() == null
                                        ? "void"
                                        : method.getReturnType().getPresentableText()), true);
                result.addElement(PrioritizedLookupElement.withPriority(lookup, annotation.isPresent() ? 2_000 : 1_000));
                if (++count >= RESULT_LIMIT) return;
            }
            for (PsiField field : owner.getAllFields()) {
                ProgressManager.checkCanceled();
                if (!isPublicStatic(field) ||
                        !StringUtil.startsWithIgnoreCase(field.getName(), prefix) ||
                        !seen.add("f:" + field.getName())) {
                    continue;
                }
                result.addElement(memberLookup(field, field.getName())
                        .withTypeText(field.getType().getPresentableText(), true));
                if (++count >= RESULT_LIMIT) return;
            }
        }

        private static void addInnerClasses(
                CompletionResultSet result,
                PsiClass owner,
                String prefix,
                Set<String> seen
        ) {
            for (PsiClass innerClass : owner.getAllInnerClasses()) {
                ProgressManager.checkCanceled();
                String name = innerClass.getName();
                if (name == null || !StringUtil.startsWithIgnoreCase(name, prefix) || !seen.add("c:" + name)) {
                    continue;
                }
                result.addElement(prioritized(LookupElementBuilder.create(innerClass, name)
                        .withIcon(innerClass.getIcon(0))
                        .withTypeText(innerClass.getQualifiedName(), true)));
            }
        }

        private static LookupElementBuilder memberLookup(PsiMember member, String lookupText) {
            return LookupElementBuilder.create(member, lookupText).withIcon(member.getIcon(0));
        }

        private static boolean isPublicStatic(PsiMember member) {
            return member.hasModifierProperty(PsiModifier.PUBLIC) && member.hasModifierProperty(PsiModifier.STATIC);
        }

        private static LookupElement prioritized(LookupElementBuilder element) {
            return PrioritizedLookupElement.withPriority(element, 1_000.0);
        }
    }
}
