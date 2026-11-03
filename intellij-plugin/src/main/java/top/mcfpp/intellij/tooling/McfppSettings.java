package top.mcfpp.intellij.tooling;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;

@Service(Service.Level.PROJECT)
@State(name = "McfppSettings", storages = @Storage("mcfpp.xml"))
public final class McfppSettings implements PersistentStateComponent<McfppSettings> {
    public String compilerJar = "";
    public String javaHome = "";
    public String nodePath = "";
    public String dpsJavaPath = "";
    public String linterPath = "";
    public String prettierPath = "";
    public boolean formatOnSave = true;
    public boolean fixOnSave = true;
    public boolean lintEnabled = true;
    public static McfppSettings get(Project project) { return project.getService(McfppSettings.class); }
    @Override public McfppSettings getState() { return this; }
    @Override public void loadState(@NotNull McfppSettings state) { XmlSerializerUtil.copyBean(state, this); }
}

