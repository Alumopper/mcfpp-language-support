package top.mcfpp.intellij.run;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.*;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.command.*;
import top.mcfpp.intellij.project.McfppProjectService;
import top.mcfpp.intellij.tooling.McfppSettings;
import top.mcfpp.language.ProjectConfiguration;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Keeps one IDE session alive through compilation and Datapack Sandbox execution. */
final class McfppExecutionProcessHandler extends ProcessHandler implements Disposable {
    private final McfppRunConfiguration configuration;
    private final McfppProjectService service;
    private final AtomicBoolean started=new AtomicBoolean(),released=new AtomicBoolean();
    private volatile boolean cancelled;
    private boolean runtimeActive;
    private ProcessHandler child;
    private Runnable viewportStarted=() -> {};
    private Runnable compilationFailed=() -> {};
    private final RuntimeHandler runtime=new RuntimeHandler();
    private final class RuntimeHandler extends ProcessHandler {
        @Override protected void destroyProcessImpl() { McfppExecutionProcessHandler.this.destroyProcess(); }
        @Override protected void detachProcessImpl() { destroyProcessImpl(); }
        @Override public boolean detachIsDefault() { return false; }
        @Override public OutputStream getProcessInput() { return McfppExecutionProcessHandler.this.getProcessInput(); }
        void finish(int code) { notifyProcessTerminated(code); }
    }
    ProcessHandler runtime() { return runtime; }
    void onViewportStarted(Runnable action) { viewportStarted=action; }
    void onCompilationFailed(Runnable action) { compilationFailed=action; }

    McfppExecutionProcessHandler(McfppRunConfiguration configuration,McfppProjectService service) {
        this.configuration=configuration; this.service=service;
        Disposer.register(service,this);
    }

    @Override public void startNotify() {
        if(!started.compareAndSet(false,true)) return;
        super.startNotify();
        runtime.startNotify();
        AppExecutorUtil.getAppExecutorService().execute(this::run);
    }

    private void run() {
        int code=1;
        McfppRenderSession session=null;
        try {
            if(cancelled) return;
            boolean test=configuration.task==McfppTask.TEST;
            Path root=configuration.directory();
            Path output=ProjectConfiguration.read(root.resolve("mcfpp.toml")).targetPath();
            if(configuration.task==McfppTask.RUN) {
                code=runHeadless(root,output);
                return;
            }
            String target=McfppRenderSession.target(configuration);
            // Test results must be fresh; validated compilation metadata can be reused.
            if(test) Files.deleteIfExists(output.resolve("tests/mcfpp/results.json"));
            message(test ? "Running the test, then opening a separate visual replay.\n" : "Building the render entry point.\n");
            code=execute(configuration.commandLine(),this,null);
            if(cancelled) return;
            if(test) {
                if(!Files.isRegularFile(output.resolve("tests/mcfpp/results.json"))) return;
                var selected=McfppRenderSession.selectedTest(output,target);
                if(selected.has("disabled") && !selected.get("disabled").isJsonNull()) {
                    message("Disabled test: no render window opened.\n"); return;
                }
            } else if(code!=0) return;
            session=McfppRenderSession.prepare(root,output,test,target);
            if(session==null || cancelled) return;
            var settings=McfppSettings.get(configuration.getProject());
            var args=session.viewportArguments(root,settings.minecraftAssetsPath,settings.renderResourcePacks);
            var viewport=sandbox(root,args);
            release();
            if(cancelled) return;
            message("Preparation complete. DSB output is available in the DSB tab.\n");
            runtimeActive=true;
            runtime.notifyTextAvailable("Opening DSB render window (paused). Play advances ticks; Reset replays the entry point.\nRender files: "+session.directory+"\nDSB execution log: "+session.directory.resolve("dsb.log")+"\n",ProcessOutputTypes.SYSTEM);
            viewportStarted.run();
            int viewportCode;
            try(var log=Files.newBufferedWriter(session.directory.resolve("dsb.log"))) {
                log.write(viewport.getCommandLineString()+"\n"); log.flush();
                viewportCode=execute(viewport,runtime,log);
            }
            if(viewportCode!=0) code=viewportCode;
        } catch(Exception error) {
            if(!cancelled) (runtimeActive ? runtime : this).notifyTextAvailable("MCFPP execution failed: "+error.getMessage()+"\n",ProcessOutputTypes.STDERR);
            code=1;
        } finally {
            if(session!=null) try {
                session.close();
                if(Files.isRegularFile(session.lastLog())) runtime.notifyTextAvailable("DSB execution log: "+session.lastLog()+"\n",ProcessOutputTypes.SYSTEM);
            } catch(java.io.IOException cleanup) {
                runtime.notifyTextAvailable("Could not remove temporary render files: "+cleanup.getMessage()+"\n",ProcessOutputTypes.STDERR);
            }
            if(configuration.task==McfppTask.RUN && code!=0 && !runtimeActive && !cancelled) compilationFailed.run();
            release();
            notifyProcessTerminated(cancelled ? 130 : code);
            runtime.finish(cancelled ? 130 : code);
            Disposer.dispose(this);
        }
    }

    private int runHeadless(Path root,Path output) throws Exception {
        Path index=output.resolve("mcfpp.run.json");
        Files.deleteIfExists(index);
        int code=execute(configuration.commandLine(),this,null);
        if(code!=0 || cancelled) return code;
        var metadata=McfppRenderSession.read(index);
        String function=McfppRenderSession.resolveEntry(metadata,configuration.runFunction.trim());
        Path outputs=Files.createTempFile("mcfpp-run-output-",".jsonl");
        try {
            var args=java.util.List.of("run","--version",metadata.get("version").getAsString(),
                "--pack",metadata.get("pack").getAsString(),"--load","--outputs-file",outputs.toString(),
                "--command","data modify storage mcfpp:system stack_frame prepend value {}",
                "--command","function "+function,
                "--command","data remove storage mcfpp:system stack_frame[0]","--strict");
            runtimeActive=true;
            var diagnostics=new StringBuilder();
            code=execute(sandbox(root,args),runtime,null,text -> {
                diagnostics.append(text);
                if(diagnostics.length()>8192) diagnostics.delete(0,diagnostics.length()-8192);
            });
            if(!cancelled) {
                McfppRunOutput.publish(outputs,text -> runtime.notifyTextAvailable(text,ProcessOutputTypes.STDOUT));
                // DSB reports command failures on stdout even though it exits with a failure code.
                if(code!=0 && !diagnostics.isEmpty()) runtime.notifyTextAvailable(diagnostics.toString(),ProcessOutputTypes.STDERR);
            }
            return code;
        } finally { Files.deleteIfExists(outputs); }
    }

    private GeneralCommandLine sandbox(Path root,java.util.List<String> arguments) throws Exception {
        String java=configuration.getProject().getService(McfppCommandService.class).sandboxJavaExecutable();
        return new GeneralCommandLine(java,"--enable-native-access=ALL-UNNAMED","--sun-misc-unsafe-memory-access=allow",
            "-Dfile.encoding=UTF-8","-jar",McfppDatapackSandboxResource.extract().toString())
            .withParameters(arguments).withCharset(StandardCharsets.UTF_8).withWorkDirectory(root.toString()).withEnvironment(configuration.environment);
    }

    private int execute(GeneralCommandLine command,ProcessHandler destination,Writer log) throws Exception {
        return execute(command,destination,log,null);
    }

    private int execute(GeneralCommandLine command,ProcessHandler destination,Writer log,Consumer<String> captureStdout) throws Exception {
        KillableColoredProcessHandler handler;
        synchronized(this) {
            if(cancelled) return 130;
            handler=captureStdout==null ? new McfppProcessHandler(command) : new KillableColoredProcessHandler(command);
            handler.setShouldDestroyProcessRecursively(true);
            child=handler;
        }
        handler.addProcessListener(new ProcessAdapter() {
            @Override public void onTextAvailable(@NotNull ProcessEvent event,@NotNull Key type) {
                if(log!=null) synchronized(log) {
                    try { log.write(event.getText()); log.flush(); }
                    catch(java.io.IOException error) { destination.notifyTextAvailable("Cannot write DSB log: "+error.getMessage()+"\n",ProcessOutputTypes.STDERR); }
                }
                if(type==ProcessOutputTypes.SYSTEM) return;
                if(captureStdout!=null && !ProcessOutputType.isStderr(type)) captureStdout.accept(event.getText());
                else destination.notifyTextAvailable(event.getText(),type);
            }
        });
        handler.startNotify();
        while(!handler.waitFor(200)) if(cancelled && !handler.isProcessTerminating()) handler.destroyProcess();
        synchronized(this) { child=null; }
        return handler.getExitCode()==null ? 1 : handler.getExitCode();
    }

    private void message(String message) { notifyTextAvailable(message,ProcessOutputTypes.SYSTEM); }
    private void release() {
        if(released.compareAndSet(false,true)) {
            service.release();
            ApplicationManager.getApplication().invokeLater(() -> {
                if(!configuration.getProject().isDisposed()) service.refresh(configuration.directory());
            });
        }
    }
    @Override protected synchronized void destroyProcessImpl() {
        cancelled=true;
        if(child!=null && !child.isProcessTerminated() && !child.isProcessTerminating()) child.destroyProcess();
    }
    @Override protected void detachProcessImpl() { destroyProcessImpl(); }
    @Override public boolean detachIsDefault() { return false; }
    @Override public synchronized OutputStream getProcessInput() { return child==null ? null : child.getProcessInput(); }
    @Override public void dispose() {
        if(!isProcessTerminated()) destroyProcess();
        if(!started.get()) release();
    }
}
