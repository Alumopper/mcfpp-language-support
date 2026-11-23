package top.mcfpp.intellij.run;

import com.intellij.execution.*;
import com.intellij.execution.configurations.*;
import com.intellij.execution.process.*;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.util.execution.ParametersListUtil;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.project.McfppProjectService;
import top.mcfpp.intellij.tooling.McfppToolchain;
import java.nio.file.*;
import java.util.*;

public final class McfppRunConfiguration extends RunConfigurationBase<RunConfigurationOptions> {
    public McfppTask task=McfppTask.BUILD;
    public String projectPath="",arguments="";
    public String debugFunction="";
    public String runFunction="";
    public Integer optimizationLevel;
    public boolean render=false;
    public String renderFunction="";
    public boolean debugStopOnEntry=false;
    public boolean offline=false,locked=false;
    public boolean deployAfterBuild=false;
    public String deploymentDirectory="";
    public Map<String,String> environment=new LinkedHashMap<>();
    public McfppRunConfiguration(Project project,ConfigurationFactory factory,String name) { super(project,factory,name); }
    @Override public McfppRunConfiguration clone() {
        var copy=(McfppRunConfiguration)super.clone();
        copy.environment=new LinkedHashMap<>(environment);
        return copy;
    }
    @Override public @NotNull SettingsEditor<? extends RunConfiguration> getConfigurationEditor() { return new McfppRunSettingsEditor(getProject()); }
    @Override public void checkConfiguration() throws RuntimeConfigurationException {
        Path root=directory();
        if(!Files.isRegularFile(root.resolve("mcfpp.toml"))) throw new RuntimeConfigurationError("Select an MCFPP project containing mcfpp.toml.");
        if(task==McfppTask.UPDATE && locked) throw new RuntimeConfigurationError("Update cannot be used with --locked.");
        if(render) {
            if(task!=McfppTask.BUILD && task!=McfppTask.TEST) throw new RuntimeConfigurationError("Rendering requires Build or Test.");
            try { McfppRenderSession.target(this); }
            catch(IllegalArgumentException error) { throw new RuntimeConfigurationError(error.getMessage()); }
            var args=ParametersListUtil.parse(arguments);
            if(args.contains("--list") || args.contains("--render-info=false") || args.contains("--no-render-info")) throw new RuntimeConfigurationError("Rendering requires an executable build with render metadata.");
            if(deployAfterBuild) throw new RuntimeConfigurationError("Use a separate Build configuration to deploy the datapack.");
        }
        if(deploymentEnabled()) try { deployment(); }
        catch(Exception e) { throw new RuntimeConfigurationError("Cannot deploy datapack: "+e.getMessage()); }
    }
    private boolean deploymentEnabled() { return deployAfterBuild && (task==McfppTask.BUILD || task==McfppTask.DUMP_IR); }
    private McfppDatapackDeployment deployment() throws java.io.IOException {
        return McfppDatapackDeployment.forProject(directory(),deploymentDirectory,ParametersListUtil.parse(arguments));
    }
    public Path directory() { return Path.of(projectPath.isBlank() ? Objects.requireNonNullElse(getProject().getBasePath(),".") : projectPath).toAbsolutePath().normalize(); }
    public GeneralCommandLine commandLine() throws Exception {
        if(task==McfppTask.LINT || task==McfppTask.FORMAT_CHECK) {
            var config=top.mcfpp.language.ProjectConfiguration.read(directory().resolve("mcfpp.toml"));
            return McfppToolchain.formatterCommand(getProject(),task.command,config.sourcePath().toString()).withWorkDirectory(directory().toString()).withEnvironment(environment);
        }
        List<String> args=new ArrayList<>(List.of(task.command,"--project",directory().toString()));
        if(offline) args.add("--offline"); if(locked) args.add("--locked");
        var extra = new ArrayList<>(ParametersListUtil.parse(arguments));
        if(task.compilesSources() && optimizationLevel!=null) {
            for(int i=0;i<extra.size();i++) {
                String argument=extra.get(i);
                if(argument.equals("-O") || argument.equals("--optimization-level")) {
                    extra.remove(i);
                    if(i<extra.size() && extra.get(i).matches("[012]")) extra.remove(i);
                    i--;
                } else if(argument.matches("-O(?:=?[012])|--optimization-level=.*")) {
                    extra.remove(i--);
                }
            }
            extra.add("--optimization-level="+optimizationLevel);
        }
        if(task==McfppTask.RUN) {
            extra.removeIf(arg -> arg.equals("--reuse-build") || arg.equals("--no-reuse-build") || arg.startsWith("--reuse-build=") ||
                arg.equals("--run-info") || arg.startsWith("--run-info="));
            extra.add("--no-reuse-build");
            extra.add("--run-info");
        }
        args.addAll(extra);
        if(render && !args.contains("--render-info")) args.add("--render-info");
        if(render && task==McfppTask.BUILD) addBuildReuse(args);
        if(task==McfppTask.TEST) {
            Path sources=directory().resolve("src/test/mcfpp");
            boolean sourceTests=false;
            if(Files.isDirectory(sources)) try(var files=Files.walk(sources)) {
                sourceTests=files.anyMatch(path -> Files.isRegularFile(path) && path.toString().endsWith(".mcfpp"));
            }
            if(sourceTests && !args.contains("--list")) {
                addBuildReuse(args);
                if(args.stream().noneMatch(a -> a.equals("--dsb-jar") || a.startsWith("--dsb-jar="))) {
                    args.add("--dsb-jar"); args.add(top.mcfpp.intellij.command.McfppDatapackSandboxResource.extract().toString());
                }
                if(args.stream().noneMatch(a -> a.equals("--dsb-java") || a.startsWith("--dsb-java="))) {
                    args.add("--dsb-java"); args.add(getProject().getService(top.mcfpp.intellij.command.McfppCommandService.class).sandboxJavaExecutable());
                }
            }
            if(!args.contains("--teamcity")) args.add("--teamcity");
        }
        return McfppToolchain.cli(getProject(),directory(),args).withEnvironment(environment);
    }
    private static void addBuildReuse(List<String> args) {
        if(args.stream().noneMatch(arg -> arg.equals("--reuse-build") || arg.equals("--no-reuse-build") || arg.startsWith("--reuse-build="))) args.add("--reuse-build");
    }
    @Override public RunProfileState getState(@NotNull Executor executor,@NotNull ExecutionEnvironment environment) {
        return new CommandLineState(environment) {
            @Override public @NotNull ExecutionResult execute(@NotNull Executor executor,@NotNull com.intellij.execution.runners.ProgramRunner<?> runner) throws ExecutionException {
                boolean headless=task==McfppTask.RUN;
                if(task!=McfppTask.TEST && !render && !headless) return super.execute(executor,runner);
                var handler=startProcess();
                com.intellij.execution.ui.ConsoleView console;
                if(task==McfppTask.TEST) {
                    var properties=new com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties(McfppRunConfiguration.this,"MCFPP",executor) {
                        @Override public com.intellij.execution.testframework.sm.runner.SMTestLocator getTestLocator() { return new McfppTestLocator(); }
                    };
                    console=com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil.createAndAttachConsole("MCFPP",handler,properties);
                } else {
                    console=com.intellij.execution.filters.TextConsoleBuilderFactory.getInstance().createBuilder(getProject()).getConsole();
                    console.attachToProcess(handler);
                }
                console.addMessageFilter(new McfppConsoleFilter(getProject()));
                if(handler instanceof McfppExecutionProcessHandler preparation) {
                    var runtime=com.intellij.execution.filters.TextConsoleBuilderFactory.getInstance().createBuilder(getProject()).getConsole();
                    runtime.addMessageFilter(new McfppConsoleFilter(getProject()));
                    runtime.attachToProcess(preparation.runtime());
                    var view=new McfppExecutionConsole(console,runtime,task==McfppTask.TEST,headless);
                    if(headless) preparation.onCompilationFailed(() -> com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
                        if(!getProject().isDisposed()) view.showCompilation();
                    }));
                    else if(task!=McfppTask.TEST) preparation.onViewportStarted(() -> com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
                        if(!getProject().isDisposed()) view.showRuntime();
                    }));
                    return new DefaultExecutionResult(view,handler,view.actions());
                }
                return new DefaultExecutionResult(console,handler);
            }
            @Override protected @NotNull ProcessHandler startProcess() throws ExecutionException {
                var service=getProject().getService(McfppProjectService.class);
                service.acquire();
                try {
                    if(render || task==McfppTask.RUN) return new McfppExecutionProcessHandler(McfppRunConfiguration.this.clone(),service);
                    long startedAt=System.currentTimeMillis();
                    var deployment=deploymentEnabled() ? deployment() : null;
                    var handler=new McfppProcessHandler(commandLine(),(exitCode,process) -> {
                        if(exitCode!=0 || deployment==null) return exitCode;
                        return deployment.finish(exitCode,(message,error) -> {
                            process.notifyTextAvailable(message,error ? ProcessOutputTypes.STDERR : ProcessOutputTypes.SYSTEM);
                        });
                    });
                    handler.setShouldDestroyProcessRecursively(true);
                    handler.addProcessListener(new ProcessAdapter() {
                        @Override public void processTerminated(@NotNull ProcessEvent event) {
                            if(task==McfppTask.TEST) reportTests(handler,startedAt,event.getExitCode());
                            service.release();
                            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
                                if(getProject().isDisposed()) return;
                                service.refresh();
                                if(event.getExitCode()==0 && (task==McfppTask.INSTALL || task==McfppTask.UPDATE)) service.synchronize();
                            });
                        }
                    });
                    return handler;
                } catch(Exception e) { service.release(); throw new ExecutionException(e.getMessage(),e); }
            }
            { addConsoleFilters(new McfppConsoleFilter(getProject())); }
        };
    }
    private void reportTests(ProcessHandler handler,long startedAt,int exitCode) {
        try {
            var config=top.mcfpp.language.ProjectConfiguration.read(directory().resolve("mcfpp.toml"));
            Path output=config.targetPath().resolve(".mcfpp/mni");
            Path results=output.resolve("test-results/test");
            if(!Files.isDirectory(results)) return;
            var files=Files.list(results);
            java.util.List<Path> reports;
            try(files) { reports=files.filter(f -> f.getFileName().toString().startsWith("TEST-") && f.toString().endsWith(".xml")).toList(); }
            int total=0,failed=0,skipped=0; boolean cached=true;
            for(Path file:reports) {
                if(Files.getLastModifiedTime(file).toMillis()>=startedAt) cached=false;
                var suite=com.intellij.openapi.util.JDOMUtil.load(file);
                total+=Integer.parseInt(suite.getAttributeValue("tests","0"));
                failed+=Integer.parseInt(suite.getAttributeValue("failures","0"))+Integer.parseInt(suite.getAttributeValue("errors","0"));
                skipped+=Integer.parseInt(suite.getAttributeValue("skipped","0"));
            }
            if(reports.isEmpty() || exitCode!=0 && cached) return;
            handler.notifyTextAvailable("\nJava tests: "+(total-failed-skipped)+" passed, "+failed+" failed, "+skipped+" skipped"+(cached ? " (up-to-date)" : "")+".\n",ProcessOutputTypes.SYSTEM);
            Path html=output.resolve("reports/tests/test/index.html");
            if(Files.isRegularFile(html)) handler.notifyTextAvailable("MCFPP test report: "+html+"\n",ProcessOutputTypes.SYSTEM);
        } catch(Exception ignored) { /* The CLI output remains available for custom Gradle report layouts. */ }
    }
    @Override public void readExternal(@NotNull Element element) {
        super.readExternal(element);
        try { task=McfppTask.valueOf(element.getAttributeValue("task","BUILD")); } catch(IllegalArgumentException ignored) {}
        projectPath=element.getAttributeValue("projectPath",""); arguments=element.getAttributeValue("arguments","");
        debugFunction=element.getAttributeValue("debugFunction",""); debugStopOnEntry=Boolean.parseBoolean(element.getAttributeValue("debugStopOnEntry","false"));
        runFunction=element.getAttributeValue("runFunction","");
        String level=element.getAttributeValue("optimizationLevel","");
        optimizationLevel=level.matches("[012]") ? Integer.valueOf(level) : null;
        render=Boolean.parseBoolean(element.getAttributeValue("render","false")); renderFunction=element.getAttributeValue("renderFunction","");
        offline=Boolean.parseBoolean(element.getAttributeValue("offline","false")); locked=Boolean.parseBoolean(element.getAttributeValue("locked","false"));
        deployAfterBuild=Boolean.parseBoolean(element.getAttributeValue("deployAfterBuild","false"));
        deploymentDirectory=element.getAttributeValue("deploymentDirectory","");
        environment.clear(); for(Element entry:element.getChildren("env")) environment.put(entry.getAttributeValue("name"),entry.getAttributeValue("value",""));
    }
    @Override public void writeExternal(@NotNull Element element) {
        super.writeExternal(element);
        element.setAttribute("task",task.name()).setAttribute("projectPath",projectPath).setAttribute("arguments",arguments);
        element.setAttribute("debugFunction",debugFunction).setAttribute("debugStopOnEntry",Boolean.toString(debugStopOnEntry));
        element.setAttribute("runFunction",runFunction);
        if(optimizationLevel!=null) element.setAttribute("optimizationLevel",optimizationLevel.toString());
        element.setAttribute("render",Boolean.toString(render)).setAttribute("renderFunction",renderFunction);
        element.setAttribute("offline",Boolean.toString(offline)).setAttribute("locked",Boolean.toString(locked));
        element.setAttribute("deployAfterBuild",Boolean.toString(deployAfterBuild)).setAttribute("deploymentDirectory",deploymentDirectory);
        for(var entry:environment.entrySet()) element.addContent(new Element("env").setAttribute("name",entry.getKey()).setAttribute("value",entry.getValue()));
    }
}

