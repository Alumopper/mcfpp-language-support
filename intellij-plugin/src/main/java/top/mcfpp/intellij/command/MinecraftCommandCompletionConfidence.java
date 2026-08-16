package top.mcfpp.intellij.command;

import com.intellij.codeInsight.completion.CompletionConfidence;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.ThreeState;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.lang.McfppFile;
import top.mcfpp.intellij.lang.MinecraftFunctionFile;

/** Keeps command completion enabled in otherwise punctuation-heavy command lines. */
public final class MinecraftCommandCompletionConfidence extends CompletionConfidence {
    @Override
    public @NotNull ThreeState shouldSkipAutopopup(
            @NotNull Editor editor,
            @NotNull PsiElement contextElement,
            @NotNull PsiFile psiFile,
            int offset
    ) {
        if (psiFile instanceof MinecraftFunctionFile) return ThreeState.NO;
        if (psiFile instanceof McfppFile &&
                McfppCommandContext.at(psiFile.getViewProvider().getContents(), offset) != null) {
            return ThreeState.NO;
        }
        return ThreeState.UNSURE;
    }
}
