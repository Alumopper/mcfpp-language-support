package top.mcfpp.intellij.project;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.run.McfppRunConfigurations;
import top.mcfpp.language.ProjectConfiguration;
import java.nio.file.Files;

public final class McfppProjectStartup implements ProjectActivity {
    @Override public Object execute(@NotNull Project project,@NotNull Continuation<? super Unit> continuation) {
        var service=project.getService(McfppProjectService.class);
        var root=service.root();
        if(root==null) {
            var connection = project.getMessageBus().connect();
            connection.subscribe(com.intellij.openapi.fileEditor.FileEditorManagerListener.FILE_EDITOR_MANAGER, new com.intellij.openapi.fileEditor.FileEditorManagerListener() {
                @Override public void fileOpened(@NotNull com.intellij.openapi.fileEditor.FileEditorManager manager, @NotNull com.intellij.openapi.vfs.VirtualFile file) {
                    service.openedInCollection(file);
                }
            });
            for (var file : com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getOpenFiles()) service.openedInCollection(file);
            return Unit.INSTANCE;
        }
        if(!ApplicationManager.getApplication().isUnitTestMode()) ApplicationManager.getApplication().executeOnPooledThread(() ->
            top.mcfpp.intellij.tooling.McfppNotifications.checkRenderAssets(project,root));
        ApplicationManager.getApplication().invokeLater(() -> {
            if(project.isDisposed()) return;
            try {
                var configuration=ProjectConfiguration.read(root.resolve("mcfpp.toml"));
                McfppRunConfigurations.initialize(project,configuration.mniEnabled());
                if(!ApplicationManager.getApplication().isUnitTestMode()) {
                    var editors=com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project);
                    if(editors.getOpenFiles().length==0) {
                        var entry=com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(configuration.sourcePath().resolve("main.mcfpp"));
                        if(entry!=null) editors.openFile(entry,true);
                    }
                }
            }
            catch(Exception ignored) {}
            if(!ApplicationManager.getApplication().isUnitTestMode()) service.synchronize();
        },project.getDisposed());
        return Unit.INSTANCE;
    }
}

