package top.mcfpp.intellij.debug;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.process.*;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.ui.ColoredTextContainer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.xdebugger.*;
import com.intellij.xdebugger.breakpoints.*;
import com.intellij.xdebugger.evaluation.*;
import com.intellij.xdebugger.frame.*;
import org.eclipse.lsp4j.debug.*;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.*;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.command.*;
import top.mcfpp.intellij.lang.McfppFileType;
import top.mcfpp.intellij.project.McfppProjectService;
import top.mcfpp.intellij.run.*;
import top.mcfpp.intellij.tooling.McfppToolchain;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native IDEA presentation over the compiler's standalone DAP adapter. */
public final class McfppDebugProcess extends XDebugProcess implements IDebugProtocolClient {
    private final McfppRunConfiguration configuration;
    private final Process process;
    private final Handler handler = new Handler();
    private final IDebugProtocolServer remote;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final Map<String, CopyOnWriteArrayList<XLineBreakpoint<McfppBreakpointProperties>>> breakpoints = new ConcurrentHashMap<>();
    private volatile boolean ready;
    private volatile Integer debugExitCode;
    private final XBreakpointHandler<XLineBreakpoint<McfppBreakpointProperties>> breakpointHandler = new XBreakpointHandler<>(McfppLineBreakpointType.class) {
        @Override public void registerBreakpoint(@NotNull XLineBreakpoint<McfppBreakpointProperties> breakpoint) {
            if (breakpoint.getSourcePosition() == null) return;
            String path = breakpoint.getSourcePosition().getFile().getPath();
            breakpoints.computeIfAbsent(path, ignored -> new CopyOnWriteArrayList<>()).addIfAbsent(breakpoint);
            if (ready) updateBreakpoints(path);
        }
        @Override public void unregisterBreakpoint(@NotNull XLineBreakpoint<McfppBreakpointProperties> breakpoint, boolean temporary) {
            if (breakpoint.getSourcePosition() == null) return;
            String path = breakpoint.getSourcePosition().getFile().getPath();
            breakpoints.computeIfAbsent(path, ignored -> new CopyOnWriteArrayList<>()).remove(breakpoint);
            if (ready) updateBreakpoints(path);
        }
    };

    public McfppDebugProcess(XDebugSession session, McfppRunConfiguration configuration) throws ExecutionException {
        super(session);
        this.configuration = configuration;
        var service = session.getProject().getService(McfppProjectService.class);
        service.acquire();
        try {
            process = McfppToolchain.cli(session.getProject(), configuration.directory(), List.of("debug", "--stdio"))
                .withEnvironment(configuration.environment).createProcess();
            var launcher = DSPLauncher.createClientLauncher(this, process.getInputStream(), process.getOutputStream());
            remote = launcher.getRemoteProxy();
            launcher.startListening();
            process.onExit().thenAccept(exited -> {
                handler.finished(debugExitCode == null ? exited.exitValue() : debugExitCode); service.release();
                ui(() -> { if (!getSession().getProject().isDisposed()) getSession().stop(); });
            });
            AppExecutorUtil.getAppExecutorService().execute(() -> {
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    reader.lines().forEach(line -> handler.notifyTextAvailable(line + "\n", ProcessOutputTypes.STDERR));
                } catch (java.io.IOException ignored) { /* The process exit is reported separately. */ }
            });
            Disposer.register(session.getProject(), this::stop);
        } catch (Exception error) { service.release(); throw new ExecutionException(error.getMessage(), error); }
        session.setPauseActionSupported(true);
    }

    @Override public void sessionInitialized() {
        if (!handler.isStartNotified()) handler.startNotify();
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                var args = new InitializeRequestArguments();
                args.setAdapterID("mcfpp"); args.setClientID("mcfpp-idea"); args.setLinesStartAt1(true); args.setColumnsStartAt1(true);
                var launch = new HashMap<String, Object>();
                launch.put("projectPath", configuration.directory().toString());
                launch.put("dsbJar", McfppDatapackSandboxResource.extract().toString());
                launch.put("dsbJava", getSession().getProject().getService(McfppCommandService.class).sandboxJavaExecutable());
                launch.put("stopOnEntry", configuration.debugStopOnEntry);
                launch.put("offline", configuration.offline); launch.put("locked", configuration.locked);
                if (configuration.task == McfppTask.TEST) launch.put("test", selectedTest());
                else launch.put("function", configuration.debugFunction);
                remote.initialize(args).thenCompose(ignored -> remote.launch(launch)).exceptionally(error -> { fail(error); return null; });
            } catch (Exception error) { fail(error); }
        });
    }

    private String selectedTest() throws ExecutionException {
        var args = com.intellij.util.execution.ParametersListUtil.parse(configuration.arguments);
        String id = null;
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equals("--tests") && i + 1 < args.size()) id = args.get(++i);
            else if (args.get(i).startsWith("--tests=")) id = args.get(i).substring(8);
        }
        if (id == null || id.contains("*")) throw new ExecutionException("Select one MCFPP test to debug using its gutter action.");
        return id;
    }

    @Override public void initialized() {
        ready = true;
        CompletableFuture.allOf(breakpoints.keySet().stream().map(this::updateBreakpoints).toArray(CompletableFuture[]::new))
            .thenCompose(ignored -> remote.configurationDone(new ConfigurationDoneArguments()))
            .exceptionally(error -> { fail(error); return null; });
    }

    private CompletableFuture<?> updateBreakpoints(String path) {
        var list = List.copyOf(breakpoints.getOrDefault(path, new CopyOnWriteArrayList<>()));
        var args = new SetBreakpointsArguments();
        var source = new Source(); source.setPath(path); args.setSource(source);
        args.setBreakpoints(list.stream().map(breakpoint -> { var point = new SourceBreakpoint(); point.setLine(breakpoint.getLine() + 1); return point; }).toArray(SourceBreakpoint[]::new));
        return remote.setBreakpoints(args).thenAccept(response -> ui(() -> {
            for (int i = 0; i < list.size() && i < response.getBreakpoints().length; i++) {
                var result = response.getBreakpoints()[i];
                if (result.isVerified()) getSession().setBreakpointVerified(list.get(i));
                else getSession().setBreakpointInvalid(list.get(i), result.getMessage());
            }
        })).exceptionally(error -> { fail(error); return null; });
    }

    @Override public void stopped(StoppedEventArguments event) {
        var args = new StackTraceArguments(); args.setThreadId(1);
        remote.stackTrace(args).thenAccept(response -> {
            List<NativeFrame> frames = Arrays.stream(response.getStackFrames()).map(NativeFrame::new).toList();
            ui(() -> getSession().positionReached(new NativeContext(frames)));
        }).exceptionally(error -> { fail(error); return null; });
    }
    @Override public void output(OutputEventArguments event) {
        handler.notifyTextAvailable(event.getOutput(), "stderr".equals(event.getCategory()) ? ProcessOutputTypes.STDERR : ProcessOutputTypes.STDOUT);
    }
    @Override public void terminated(TerminatedEventArguments event) { ui(() -> getSession().stop()); }
    @Override public void exited(ExitedEventArguments event) {
        debugExitCode = event.getExitCode();
        handler.notifyTextAvailable("\nMCFPP debug session exited with code " + event.getExitCode() + ".\n", ProcessOutputTypes.SYSTEM);
    }
    @Override public XBreakpointHandler<?> @NotNull [] getBreakpointHandlers() { return new XBreakpointHandler<?>[]{breakpointHandler}; }
    @Override protected ProcessHandler doGetProcessHandler() { return handler; }
    @Override public @NotNull com.intellij.execution.ui.ExecutionConsole createConsole() {
        var console = com.intellij.execution.filters.TextConsoleBuilderFactory.getInstance()
            .createBuilder(getSession().getProject()).getConsole();
        console.addMessageFilter(new McfppConsoleFilter(getSession().getProject()));
        console.attachToProcess(handler);
        return console;
    }
    @Override public @NotNull XDebuggerEditorsProvider getEditorsProvider() {
        return new XDebuggerEditorsProvider() {
            @Override public @NotNull FileType getFileType() { return McfppFileType.INSTANCE; }
            @Override public com.intellij.openapi.editor.Document createDocument(@NotNull Project project, @NotNull String text, XSourcePosition position, @NotNull EvaluationMode mode) {
                return EditorFactory.getInstance().createDocument(text);
            }
        };
    }
    private void command(java.util.function.Supplier<CompletableFuture<?>> request) {
        ui(() -> {
            try {
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments();
                request.get().exceptionally(error -> { fail(error); return null; });
            } catch (RuntimeException error) { fail(error); }
        });
    }
    @Override public void resume(XSuspendContext context) { var args = new ContinueArguments(); args.setThreadId(1); command(() -> remote.continue_(args)); }
    @Override public void startStepOver(XSuspendContext context) { var args = new NextArguments(); args.setThreadId(1); command(() -> remote.next(args)); }
    @Override public void startStepInto(XSuspendContext context) { var args = new StepInArguments(); args.setThreadId(1); command(() -> remote.stepIn(args)); }
    @Override public void startStepOut(XSuspendContext context) { var args = new StepOutArguments(); args.setThreadId(1); command(() -> remote.stepOut(args)); }
    @Override public void startPausing() { var args = new PauseArguments(); args.setThreadId(1); command(() -> remote.pause(args)); }
    @Override public void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        remote.disconnect(new DisconnectArguments()).orTimeout(5, TimeUnit.SECONDS).whenComplete((ignored, error) -> handler.destroyProcess());
    }
    private void fail(Throwable error) {
        String message = errorMessage(error);
        handler.notifyTextAvailable(message + "\n", ProcessOutputTypes.STDERR);
        ui(() -> { getSession().reportError(message); getSession().stop(); });
    }
    private static String errorMessage(Throwable error) {
        while ((error instanceof CompletionException || error instanceof java.util.concurrent.ExecutionException) && error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? "The debug request failed." : error.getMessage();
    }
    private void ui(Runnable action) { ApplicationManager.getApplication().invokeLater(() -> { if (!getSession().getProject().isDisposed()) action.run(); }); }

    private final class Handler extends ProcessHandler {
        private final AnsiEscapeDecoder decoder = new AnsiEscapeDecoder();
        @Override public void notifyTextAvailable(@NotNull String text, @NotNull com.intellij.openapi.util.Key outputType) {
            decoder.escapeText(text, outputType, (plain, attributes) -> super.notifyTextAvailable(plain, outputType));
        }
        @Override protected void destroyProcessImpl() { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
        @Override protected void detachProcessImpl() { destroyProcessImpl(); }
        @Override public boolean detachIsDefault() { return false; }
        @Override public OutputStream getProcessInput() { return null; }
        void finished(int exit) { notifyProcessTerminated(exit); }
    }
    private final class NativeContext extends XSuspendContext {
        private final XExecutionStack stack;
        NativeContext(List<NativeFrame> frames) {
            stack = new XExecutionStack("Datapack Sandbox") {
                @Override public XStackFrame getTopFrame() { return frames.isEmpty() ? null : frames.getFirst(); }
                @Override public void computeStackFrames(int first, @NotNull XStackFrameContainer container) { container.addStackFrames(frames.subList(Math.min(first, frames.size()), frames.size()), true); }
            };
        }
        @Override public XExecutionStack getActiveExecutionStack() { return stack; }
    }
    private final class NativeFrame extends XStackFrame {
        private final StackFrame frame;
        NativeFrame(StackFrame frame) { this.frame = frame; }
        @Override public XSourcePosition getSourcePosition() {
            if (frame.getSource() == null || frame.getSource().getPath() == null) return null;
            var file = LocalFileSystem.getInstance().findFileByNioFile(Path.of(frame.getSource().getPath()));
            return file == null ? null : XDebuggerUtil.getInstance().createPosition(file, Math.max(0, frame.getLine() - 1), Math.max(0, frame.getColumn() - 1));
        }
        @Override public Object getEqualityObject() { return frame.getId(); }
        @Override public void customizePresentation(@NotNull ColoredTextContainer component) { component.append(frame.getName(), SimpleTextAttributes.REGULAR_ATTRIBUTES); }
        @Override public void computeChildren(@NotNull XCompositeNode node) {
            var args = new ScopesArguments(); args.setFrameId(frame.getId());
            remote.scopes(args).thenCompose(response -> {
                var scopes = response.getScopes();
                if (scopes.length == 0) { node.addChildren(XValueChildrenList.EMPTY, true); return CompletableFuture.completedFuture(null); }
                var values = new VariablesArguments(); values.setVariablesReference(scopes[0].getVariablesReference());
                return remote.variables(values).thenAccept(result -> {
                    var children = children(result);
                    for (int i = 1; i < scopes.length; i++) children.add(scopes[i].getName(), new DapValue("", null, scopes[i].getVariablesReference()));
                    node.addChildren(children, true);
                });
            }).exceptionally(error -> { node.setErrorMessage(error.getMessage()); return null; });
        }
        @Override public XDebuggerEvaluator getEvaluator() {
            return new XDebuggerEvaluator() {
                @Override public boolean isCodeFragmentEvaluationSupported() { return false; }
                @Override public void evaluate(@NotNull String expression, @NotNull XEvaluationCallback callback, XSourcePosition position) {
                    var args = new EvaluateArguments(); args.setFrameId(frame.getId()); args.setExpression(expression); args.setContext("watch");
                    remote.evaluate(args).thenAccept(value -> callback.evaluated(new DapValue(value.getResult(), value.getType(), value.getVariablesReference())))
                        .exceptionally(error -> { callback.errorOccurred(errorMessage(error)); return null; });
                }
            };
        }
    }
    private XValueChildrenList children(VariablesResponse result) {
        var children = new XValueChildrenList();
        for (var variable : result.getVariables()) children.add(variable.getName(), new DapValue(variable.getValue(), variable.getType(), variable.getVariablesReference()));
        return children;
    }
    private final class DapValue extends XValue {
        private final String value, type;
        private final int reference;
        DapValue(String value, String type, int reference) { this.value = value; this.type = type; this.reference = reference; }
        @Override public void computePresentation(@NotNull XValueNode node, @NotNull XValuePlace place) { node.setPresentation(AllIcons.Debugger.Value, type, value, reference != 0); }
        @Override public void computeChildren(@NotNull XCompositeNode node) {
            var args = new VariablesArguments(); args.setVariablesReference(reference);
            remote.variables(args).thenAccept(result -> node.addChildren(children(result), true))
                .exceptionally(error -> { node.setErrorMessage(error.getMessage()); return null; });
        }
    }
}
