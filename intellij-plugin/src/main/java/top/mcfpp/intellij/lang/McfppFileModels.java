package top.mcfpp.intellij.lang;

import com.intellij.psi.PsiFile;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.language.VersionPreprocessor;

public final class McfppFileModels {
    private McfppFileModels() {
    }

    public static @NotNull String activeSource(@NotNull PsiFile file) {
        String source = file.getViewProvider().getContents().toString();
        try {
            return VersionPreprocessor.process(source, McfppNamespaceResolver.infer(file).targetVersion());
        } catch (VersionPreprocessor.Error error) {
            return source;
        }
    }

    public static @NotNull McfppFileModel get(@NotNull PsiFile file) {
        return CachedValuesManager.getCachedValue(file, () -> {
            McfppNamespaceResolver.Inference inference = McfppNamespaceResolver.infer(file);
            McfppFileModel model = McfppFileModel.parse(file.getViewProvider().getContents(), inference.targetVersion());
            model = model.withNamespace(inference.namespace());
            return inference.dependency() == null
                    ? CachedValueProvider.Result.create(model, file)
                    : CachedValueProvider.Result.create(model, file, inference.dependency());
        });
    }
}
