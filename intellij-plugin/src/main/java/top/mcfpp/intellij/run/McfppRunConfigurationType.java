package top.mcfpp.intellij.run;
import com.intellij.execution.configurations.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NotNullLazyValue;
import top.mcfpp.intellij.lang.McfppIcons;
import org.jetbrains.annotations.NotNull;
public final class McfppRunConfigurationType extends SimpleConfigurationType {
    public McfppRunConfigurationType() { super("MCFPP","MCFPP","Build, test and check an MCFPP project",NotNullLazyValue.createValue(() -> McfppIcons.FILE)); }
    @Override public @NotNull RunConfiguration createTemplateConfiguration(@NotNull Project project) { return new McfppRunConfiguration(project,this,"MCFPP"); }
}

