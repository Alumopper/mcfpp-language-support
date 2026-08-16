package top.mcfpp.intellij.lang;

import com.intellij.formatting.FormattingContext;
import com.intellij.formatting.FormattingModel;
import com.intellij.formatting.FormattingModelBuilder;
import com.intellij.formatting.FormattingModelProvider;
import org.jetbrains.annotations.NotNull;

public final class McfppFormattingModelBuilder implements FormattingModelBuilder {
    @Override
    public @NotNull FormattingModel createModel(@NotNull FormattingContext context) {
        McfppFormattingBlock root = McfppFormattingBlock.root(
                context.getNode(),
                context.getCodeStyleSettings()
        );
        return FormattingModelProvider.createFormattingModelForPsiFile(
                context.getContainingFile(),
                root,
                context.getCodeStyleSettings()
        );
    }
}
