package top.mcfpp.intellij.format;

import com.google.gson.*;
import com.intellij.lang.annotation.*;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.tooling.McfppSettings;

public final class McfppLintAnnotator extends ExternalAnnotator<McfppLintAnnotator.Input,McfppLintAnnotator.Result> {
    record Input(Project project,String path,String source,long stamp) {}
    record Result(Input input,JsonArray diagnostics) {}
    @Override public Input collectInformation(@NotNull PsiFile file) {
        if(file.getVirtualFile()==null || !McfppSettings.get(file.getProject()).lintEnabled) return null;
        var document=file.getViewProvider().getDocument();
        return document==null ? null : new Input(file.getProject(),file.getVirtualFile().getPath(),document.getText(),document.getModificationStamp());
    }
    @Override public Result doAnnotate(Input input) {
        if(input==null) return null;
        try { return new Result(input,input.project().getService(McfppNodeService.class).request("lint",input.path(),input.source(),false,0).getAsJsonArray("diagnostics")); }
        catch(Exception ignored) { return null; } // Save and explicit formatting report setup failures once through their UI.
    }
    @Override public void apply(@NotNull PsiFile file,Result result,@NotNull AnnotationHolder holder) {
        if(result==null) return;
        var document=file.getViewProvider().getDocument();
        if(document==null || document.getModificationStamp()!=result.input().stamp()) return;
        for(JsonElement value:result.diagnostics()) {
            var diagnostic=value.getAsJsonObject();
            String rule=diagnostic.get("ruleId").getAsString();
            int start=Math.max(0,Math.min(document.getTextLength(),diagnostic.get("start").getAsInt()));
            int end=Math.max(start,Math.min(document.getTextLength(),diagnostic.get("end").getAsInt()));
            var annotation=holder.newAnnotation("error".equals(diagnostic.get("severity").getAsString()) ? HighlightSeverity.ERROR : HighlightSeverity.WARNING,
                diagnostic.get("message").getAsString() + " [" + rule + "]").range(new TextRange(start,end));
            if(diagnostic.has("fix")) annotation=annotation.withFix(new SafeFix(result.input().source(),diagnostic.getAsJsonObject("fix")));
            annotation.create();
        }
    }
    private record SafeFix(String original,JsonObject fix) implements IntentionAction {
        @Override public @NotNull String getText() { return "Apply safe MCFPP Linter fix"; }
        @Override public @NotNull String getFamilyName() { return "MCFPP Linter"; }
        @Override public boolean isAvailable(@NotNull Project project,Editor editor,PsiFile file) { return editor!=null && original.equals(editor.getDocument().getText()); }
        @Override public void invoke(@NotNull Project project,Editor editor,PsiFile file) {
            if(isAvailable(project,editor,file)) editor.getDocument().replaceString(fix.get("start").getAsInt(),fix.get("end").getAsInt(),fix.get("text").getAsString());
        }
        @Override public boolean startInWriteAction() { return true; }
    }
}

