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
        return versionAnalysis(file).text();
    }

    public static @NotNull VersionPreprocessor.Result versionAnalysis(@NotNull PsiFile file) {
        return CachedValuesManager.getCachedValue(file, () -> {
            var inference = McfppNamespaceResolver.infer(file);
            String source = file.getViewProvider().getContents().toString();
            VersionPreprocessor.Result result;
            try {
                result = VersionPreprocessor.preprocess(source, inference.targetVersion());
            } catch (VersionPreprocessor.Error error) {
                result = new VersionPreprocessor.Result(source, java.util.List.of(), java.util.List.of());
            }
            return inference.dependency() == null ? CachedValueProvider.Result.create(result, file)
                    : CachedValueProvider.Result.create(result, file, inference.dependency());
        });
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
