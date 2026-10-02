package top.mcfpp.intellij.lsp;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.openapi.util.TextRange;
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport;
import org.eclipse.lsp4j.Diagnostic;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.lang.McfppUnresolvedReferenceInspection;
import top.mcfpp.intellij.lang.McfppCreateFunctionIntention;
import top.mcfpp.intellij.lang.McfppCreateTypeIntention;
import top.mcfpp.intellij.lang.McfppAddImportIntention;
import java.util.ArrayList;
import java.util.List;

/** Filter by stable diagnostic code only, and only where the enabled native inspection can take over. */
public final class McfppLspDiagnosticsSupport extends LspDiagnosticsSupport {
    @Override public void createAnnotation(@NotNull AnnotationHolder holder, @NotNull Diagnostic diagnostic,
                                          @NotNull TextRange range, @NotNull List<? extends IntentionAction> fixes) {
        var code = diagnostic.getCode();
        if (code != null && code.isLeft() &&
                McfppUnresolvedReferenceInspection.DIAGNOSTIC_CODE.equals(code.getLeft())) {
            var file = holder.getCurrentAnnotationSession().getFile();
            var element = file.findElementAt(range.getStartOffset());
            if (element != null && element.getTextRange().equals(range) &&
                    McfppUnresolvedReferenceInspection.ownsDiagnostic(element)) return;
            if (element != null && element.getTextRange().equals(range)) {
                var combined = new ArrayList<IntentionAction>(fixes);
                for (var action : List.<com.intellij.codeInsight.intention.PsiElementBaseIntentionAction>of(
                        new McfppCreateFunctionIntention(), new McfppCreateTypeIntention(),
                        new McfppAddImportIntention())) {
                    if (action.isAvailable(file.getProject(), null, element) &&
                            combined.stream().noneMatch(existing -> existing.getText().equals(action.getText()))) {
                        combined.add(action);
                    }
                }
                super.createAnnotation(holder, diagnostic, range, combined);
                return;
            }
        }
        super.createAnnotation(holder, diagnostic, range, fixes);
    }
}
