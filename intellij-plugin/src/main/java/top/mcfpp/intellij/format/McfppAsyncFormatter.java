package top.mcfpp.intellij.format;

import com.intellij.formatting.service.*;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.lang.McfppFile;
import java.util.Set;

public final class McfppAsyncFormatter extends AsyncDocumentFormattingService {
    @Override public boolean canFormat(@NotNull PsiFile file) {
        if (!(file instanceof McfppFile) || file.getVirtualFile() == null || file.getProject().isDisposed()) return false;
        try {
            return file.getProject().getService(McfppNodeService.class).isFormatterAvailable();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
    @Override public @NotNull Set<Feature> getFeatures() { return Set.of(Feature.AD_HOC_FORMATTING); }
    @Override protected @NotNull String getName() { return "MCFPP Prettier"; }
    @Override protected @NotNull String getNotificationGroupId() { return "MCFPP"; }
    @Override protected FormattingTask createFormattingTask(@NotNull AsyncFormattingRequest request) {
        return new FormattingTask() {
            private volatile boolean cancelled;
            @Override public void run() {
                try {
                    var file=request.getContext().getContainingFile();
                    var response=file.getProject().getService(McfppNodeService.class).request("format",
                        file.getVirtualFile().getPath(),request.getDocumentText(),true,0);
                    if(!cancelled) request.onTextReady(response.get("source").getAsString());
                } catch (McfppNodeService.FormatterUnavailableException unavailable) {
                    // The built-in PSI formatter is selected when the async service is unavailable.
                } catch(Exception error) { if(!cancelled) request.onError("MCFPP formatting",error.getMessage()); }
            }
            @Override public boolean cancel() { cancelled=true; return true; }
        };
    }
}

