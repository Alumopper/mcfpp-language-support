package top.mcfpp.intellij.lang;

import com.intellij.lang.parameterInfo.CreateParameterInfoContext;
import com.intellij.lang.parameterInfo.ParameterInfoHandler;
import com.intellij.lang.parameterInfo.ParameterInfoUIContext;
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class McfppParameterInfoHandler
        implements ParameterInfoHandler<PsiElement, McfppCallableInfo>, DumbAware {
    @Override
    public @Nullable PsiElement findElementForParameterInfo(@NotNull CreateParameterInfoContext context) {
        McfppCallContext call = McfppCallContext.atCaret(context.getFile(), context.getOffset());
        if (call == null) return null;
        List<McfppCallableInfo> callables = McfppSymbolResolver.findCallables(call.callee());
        if (callables.isEmpty()) return null;
        context.setItemsToShow(callables.toArray());
        return call.openParenthesis();
    }

    @Override
    public void showParameterInfo(@NotNull PsiElement element, @NotNull CreateParameterInfoContext context) {
        context.showHint(element, element.getTextOffset() + element.getTextLength(), this);
    }

    @Override
    public @Nullable PsiElement findElementForUpdatingParameterInfo(@NotNull UpdateParameterInfoContext context) {
        McfppCallContext call = McfppCallContext.atCaret(context.getFile(), context.getOffset());
        return call == null ? null : call.openParenthesis();
    }

    @Override
    public void updateParameterInfo(@NotNull PsiElement owner, @NotNull UpdateParameterInfoContext context) {
        McfppCallContext call = McfppCallContext.atCaret(context.getFile(), context.getOffset());
        if (call == null || !owner.isEquivalentTo(call.openParenthesis())) {
            context.removeHint();
            return;
        }
        context.setParameterOwner(owner);
        context.setCurrentParameter(call.currentArgument());
    }

    @Override
    public void updateUI(@NotNull McfppCallableInfo info, @NotNull ParameterInfoUIContext context) {
        int index = context.getCurrentParameterIndex();
        List<TextRange> parameters = info.parameters();
        TextRange highlighted = index >= 0 && index < parameters.size() ? parameters.get(index) : null;
        context.setupUIComponentPresentation(
                info.signature(),
                highlighted == null ? -1 : highlighted.getStartOffset(),
                highlighted == null ? -1 : highlighted.getEndOffset(),
                highlighted == null,
                false,
                false,
                context.getDefaultParameterColor()
        );
    }
}
