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

/** Keeps the IDE run alive through compilation, testing and the native viewport. */
final class McfppRenderProcessHandler extends ProcessHandler implements Disposable {
    private final McfppRunConfiguration configuration;
    private final McfppProjectService service;
    private final AtomicBoolean started=new AtomicBoolean(),released=new AtomicBoolean();
    private volatile boolean cancelled;
    private boolean runtimeActive;
    private ProcessHandler child;
    private Runnable viewportStarted=() -> {};
    private final RuntimeHandler runtime=new RuntimeHandler();
    private final class RuntimeHandler extends ProcessHandler {
        @Override protected void destroyProcessImpl() { McfppRenderProcessHandler.this.destroyProcess(); }
        @Override protected void detachProcessImpl() { destroyProcessImpl(); }
        @Override public boolean detachIsDefault() { return false; }
        @Override public OutputStream getProcessInput() { return McfppRenderProcessHandler.this.getProcessInput(); }
        void finish(int code) { notifyProcessTerminated(code); }
    }
    ProcessHandler runtime() { return runtime; }
    void onViewportStarted(Runnable action) { viewportStarted=action; }

    McfppRenderProcessHandler(McfppRunConfiguration configuration,McfppProjectService service) {
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
            String java=configuration.getProject().getService(McfppCommandService.class).sandboxJavaExecutable();
            var viewport=new GeneralCommandLine(java,"--enable-native-access=ALL-UNNAMED","--sun-misc-unsafe-memory-access=allow","-Dfile.encoding=UTF-8","-jar",McfppDatapackSandboxResource.extract().toString())
                .withParameters(args).withCharset(StandardCharsets.UTF_8).withWorkDirectory(root.toString()).withEnvironment(configuration.environment);
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
            if(!cancelled) (runtimeActive ? runtime : this).notifyTextAvailable("MCFPP rendering failed: "+error.getMessage()+"\n",ProcessOutputTypes.STDERR);
            code=1;
        } finally {
            if(session!=null) try {
                session.close();
                if(Files.isRegularFile(session.lastLog())) runtime.notifyTextAvailable("DSB execution log: "+session.lastLog()+"\n",ProcessOutputTypes.SYSTEM);
            } catch(java.io.IOException cleanup) {
                runtime.notifyTextAvailable("Could not remove temporary render files: "+cleanup.getMessage()+"\n",ProcessOutputTypes.STDERR);
            }
            release();
            notifyProcessTerminated(cancelled ? 130 : code);
            runtime.finish(cancelled ? 130 : code);
            Disposer.dispose(this);
        }
    }

    private int execute(GeneralCommandLine command,ProcessHandler destination,Writer log) throws Exception {
        McfppProcessHandler handler;
        synchronized(this) {
            if(cancelled) return 130;
            handler=new McfppProcessHandler(command);
            handler.setShouldDestroyProcessRecursively(true);
            child=handler;
        }
        handler.addProcessListener(new ProcessAdapter() {
            @Override public void onTextAvailable(@NotNull ProcessEvent event,@NotNull Key type) {
                if(log!=null) synchronized(log) {
                    try { log.write(event.getText()); log.flush(); }
                    catch(java.io.IOException error) { destination.notifyTextAvailable("Cannot write DSB log: "+error.getMessage()+"\n",ProcessOutputTypes.STDERR); }
                }
                if(type!=ProcessOutputTypes.SYSTEM) destination.notifyTextAvailable(event.getText(),type);
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
            ApplicationManager.getApplication().invokeLater(() -> { if(!configuration.getProject().isDisposed()) service.refresh(); });
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
