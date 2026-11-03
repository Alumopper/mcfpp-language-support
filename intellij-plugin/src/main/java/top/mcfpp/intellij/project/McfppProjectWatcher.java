package top.mcfpp.intellij.project;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import org.jetbrains.annotations.NotNull;
import java.util.List;
public final class McfppProjectWatcher implements BulkFileListener {
    private final Project project;
    public McfppProjectWatcher(Project project) { this.project=project; }
    @Override public void after(@NotNull List<? extends VFileEvent> events) {
        var service=project.getService(McfppProjectService.class); var root=service.root();
        if(root==null || service.busy()) return;
        if(events.stream().anyMatch(e -> {
            String path=e.getPath().replace('\\','/');
            return path.equals(root.resolve("mcfpp.toml").toString().replace('\\','/')) ||
                path.equals(root.resolve("mcfpp-packages.json").toString().replace('\\','/')) ||
                path.endsWith(".gradle.kts") && path.startsWith(root.toString().replace('\\','/')) && !path.contains("/.mcfpp/");
        })) service.synchronize();
    }
}

