package top.mcfpp.intellij.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.KillableColoredProcessHandler;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;

final class McfppProcessHandler extends KillableColoredProcessHandler {
    private final McfppConsoleOutput output=new McfppConsoleOutput();
    McfppProcessHandler(GeneralCommandLine command) throws ExecutionException { super(command); }

    @Override public void coloredTextAvailable(@NotNull String text,@NotNull Key attributes) {
        output.accept(text,attributes,(message,type) -> super.coloredTextAvailable(message,type));
    }

    @Override protected void notifyProcessTerminated(int exitCode) {
        output.flush((message,type) -> super.coloredTextAvailable(message,type));
        super.notifyProcessTerminated(exitCode);
    }
}
