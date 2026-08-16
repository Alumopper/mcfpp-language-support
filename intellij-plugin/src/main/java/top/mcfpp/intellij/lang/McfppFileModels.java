package top.mcfpp.intellij.lang;

import com.intellij.psi.PsiFile;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import org.jetbrains.annotations.NotNull;

public final class McfppFileModels {
    private McfppFileModels() {
    }

    public static @NotNull McfppFileModel get(@NotNull PsiFile file) {
        return CachedValuesManager.getCachedValue(file, () -> {
            McfppFileModel model = McfppFileModel.parse(file.getViewProvider().getContents());
            McfppNamespaceResolver.Inference inference = model.namespace().isEmpty()
                    ? McfppNamespaceResolver.infer(file)
                    : McfppNamespaceResolver.Inference.NONE;
            model = model.withNamespace(inference.namespace());
            return inference.dependency() == null
                    ? CachedValueProvider.Result.create(model, file)
                    : CachedValueProvider.Result.create(model, file, inference.dependency());
        });
    }
}
