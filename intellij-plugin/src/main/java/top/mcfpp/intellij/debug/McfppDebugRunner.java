package top.mcfpp.intellij.debug;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.*;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.*;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.xdebugger.*;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.run.*;

public final class McfppDebugRunner extends GenericProgramRunner<RunnerSettings> {
    @Override public @NotNull String getRunnerId() { return "MCFPP.Debugger"; }
    @Override public boolean canRun(@NotNull String executor, @NotNull RunProfile profile) {
        return DefaultDebugExecutor.EXECUTOR_ID.equals(executor) && profile instanceof McfppRunConfiguration configuration &&
            (configuration.task == McfppTask.TEST || configuration.task == McfppTask.BUILD && !configuration.debugFunction.isBlank());
    }
    @Override protected RunContentDescriptor doExecute(@NotNull RunProfileState state, @NotNull ExecutionEnvironment environment) throws ExecutionException {
        FileDocumentManager.getInstance().saveAllDocuments();
        var configuration = (McfppRunConfiguration) environment.getRunProfile();
        return XDebuggerManager.getInstance(environment.getProject()).newSessionBuilder(new XDebugProcessStarter() {
            @Override public @NotNull XDebugProcess start(@NotNull XDebugSession session) throws ExecutionException {
                return new McfppDebugProcess(session, configuration);
            }
        }).environment(environment).startSession().getRunContentDescriptor();
    }
}
