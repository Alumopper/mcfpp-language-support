package top.mcfpp.intellij.run;
import com.intellij.execution.runners.DefaultProgramRunner;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.executors.DefaultRunExecutor;
import org.jetbrains.annotations.NotNull;
public final class McfppProgramRunner extends DefaultProgramRunner {
    @Override public @NotNull String getRunnerId() { return "MCFPP.Runner"; }
    @Override public boolean canRun(@NotNull String executorId,@NotNull RunProfile profile) {
        return DefaultRunExecutor.EXECUTOR_ID.equals(executorId) && profile instanceof McfppRunConfiguration;
    }
}

