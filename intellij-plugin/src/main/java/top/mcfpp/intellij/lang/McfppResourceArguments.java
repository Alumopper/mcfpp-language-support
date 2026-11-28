package top.mcfpp.intellij.lang;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import top.mcfpp.api.resources.PackResources;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Resource string literals in calls resolved against MCFPP declarations. */
public final class McfppResourceArguments {
    private static final Pattern STRING_PARAMETER = Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*\\s*:\\s*string(?:\\s*=.*)?", Pattern.DOTALL);

    public record Use(String kind, String value, int start, int end) {
        public PackResources.Reference reference() {
            var key = PackResources.key(kind, value);
            return key == null ? null : new PackResources.Reference(key, start, end, false, "");
        }
    }

    private McfppResourceArguments() {}

    public static List<Use> uses(PsiFile file) {
        if (!(file instanceof McfppFile)) return List.of();
        if (DumbService.isDumb(file.getProject())) return collect(file);
        return CachedValuesManager.getCachedValue(file, () -> CachedValueProvider.Result.create(collect(file),
                PsiModificationTracker.MODIFICATION_COUNT, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                ProjectRootModificationTracker.getInstance(file.getProject())));
    }

    public static Use atCaret(PsiFile file, int caret) {
        if (!(file instanceof McfppFile) || caret <= 0) return null;
        return use(file.findElementAt(caret - 1), caret);
    }

    public static Use forLiteral(PsiElement literal) {
        return use(literal, -1);
    }

    private static List<Use> collect(PsiFile file) {
        var result = new ArrayList<Use>();
        for (var leaf = PsiTreeUtil.getDeepestFirst(file); leaf != null; leaf = PsiTreeUtil.nextLeaf(leaf, true)) {
            ProgressManager.checkCanceled();
            var use = use(leaf, -1);
            if (use != null) result.add(use);
        }
        return List.copyOf(result);
    }

    private static Use use(PsiElement leaf, int caret) {
        if (leaf == null || leaf.getNode().getElementType() != McfppTokenTypes.STRING) return null;
        var previous = McfppCallContext.previousCodeLeaf(leaf);
        if (previous == null || !"(".equals(previous.getText()) && !",".equals(previous.getText())) return null;
        String literal = leaf.getText();
        char quote = literal.charAt(0);
        if (quote != '"' && quote != '\'') return null;
        int quotes = literal.startsWith("\"\"\"") ? 3 : 1;
        boolean closingQuote = literal.length() >= 2 * quotes && literal.endsWith(String.valueOf(quote).repeat(quotes));
        var next = McfppCallContext.nextCodeLeaf(leaf);
        if (caret < 0 && (!closingQuote || next == null)) return null;
        if (next != null && next.getNode().getElementType() == McfppTokenTypes.INTERPOLATION_DELIMITER) return null;
        if (closingQuote && next != null && !",".equals(next.getText()) && !")".equals(next.getText())) return null;
        int start = leaf.getTextOffset() + quotes;
        int end = leaf.getTextRange().getEndOffset() - (closingQuote ? quotes : 0);
        if (caret >= 0 && (caret < start || caret > end)) return null;
        PsiFile file = leaf.getContainingFile();
        if (McfppFileModels.versionAnalysis(file).inactiveRanges().stream().anyMatch(range ->
                range.start() <= start && start < range.end())) return null;
        var call = McfppCallContext.atCaret(file, Math.max(start, caret));
        if (call == null) return null;
        int position = 0;
        boolean found = false;
        for (var argument : call.arguments()) {
            if (argument.endOffset() <= leaf.getTextOffset()) {
                if (!argument.isNamed()) position++;
                continue;
            }
            if (argument.startOffset() > leaf.getTextOffset() || previous.getTextOffset() >= argument.startOffset() ||
                    next != null && next.getTextOffset() < argument.endOffset()) return null;
            found = true;
            break;
        }
        if (!found) return null;
        String kind = resourceKind(call, position);
        return kind == null ? null : new Use(kind, literal.substring(quotes, end - leaf.getTextOffset()), start, end);
    }

    private static String resourceKind(McfppCallContext call, int position) {
        String kind = null;
        for (var callable : McfppSymbolResolver.findCallables(call.callee())) {
            if (position >= callable.parameters().size()) continue;
            var parameter = McfppParameterSyntax.declaration(callable.parameters().get(position).substring(callable.signature()));
            if (!STRING_PARAMETER.matcher(parameter.text().strip()).matches()) continue;
            if (parameter.resourceKind() == null || kind != null && !kind.equals(parameter.resourceKind())) return null;
            kind = parameter.resourceKind();
        }
        return kind;
    }
}
