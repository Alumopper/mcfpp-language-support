package top.mcfpp.intellij.lang;

import com.intellij.codeInsight.template.TemplateActionContext;
import com.intellij.codeInsight.template.TemplateContextType;
import org.jetbrains.annotations.NotNull;

public final class McfppTemplateContext extends TemplateContextType {
    public McfppTemplateContext() {
        super("MCFPP");
    }

    @Override
    public boolean isInContext(@NotNull TemplateActionContext context) {
        return context.getFile().getLanguage().isKindOf(McfppLanguage.INSTANCE);
    }
}
