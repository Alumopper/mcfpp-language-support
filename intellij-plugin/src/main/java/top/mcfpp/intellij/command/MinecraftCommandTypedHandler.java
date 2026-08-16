package top.mcfpp.intellij.command;

import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.lang.McfppFile;
import top.mcfpp.intellij.lang.MinecraftFunctionFile;

/** Reopens completion at Brigadier separators where IDEA's identifier autopopup does not run. */
public final class MinecraftCommandTypedHandler extends TypedHandlerDelegate {
    private static final String TRIGGERS = " @[,:=";

    @Override
    public @NotNull Result checkAutoPopup(
            char charTyped,
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull PsiFile file
    ) {
        if (TRIGGERS.indexOf(charTyped) < 0 || !isCommandPosition(file, editor.getCaretModel().getOffset())) {
            return Result.CONTINUE;
        }
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor);
        return Result.STOP;
    }

    private static boolean isCommandPosition(PsiFile file, int caretOffset) {
        if (file instanceof MinecraftFunctionFile) {
            return MinecraftFunctionCommandContext.at(file.getViewProvider().getContents(), caretOffset) != null;
        }
        return file instanceof McfppFile &&
                McfppCommandContext.at(file.getViewProvider().getContents(), caretOffset) != null;
    }
}
