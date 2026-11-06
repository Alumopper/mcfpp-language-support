package top.mcfpp.intellij.project;

import com.google.gson.*;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.openapi.application.*;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.*;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.roots.libraries.*;
import com.intellij.openapi.vfs.*;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.tooling.*;
import top.mcfpp.intellij.mni.McfppLibraryRootsProvider;
import top.mcfpp.language.ProjectConfiguration;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service(Service.Level.PROJECT)
public final class McfppProjectService {
    private final Project project;
    private final Semaphore commands=new Semaphore(1,true);
    private volatile JsonObject model;
    private volatile String status="Ready";
    public McfppProjectService(Project project) {
        this.project=project;
        try {
            Path root=root();
            if(root!=null && Files.isRegularFile(root.resolve(".mcfpp/ide-model.json")))
                model=readModel(Files.readString(root.resolve(".mcfpp/ide-model.json")),root);
        } catch(Exception ignored) {}
    }
    public Path root() {
        if(project.getBasePath()==null) return null;
        Path config=ProjectConfiguration.find(Path.of(project.getBasePath()));
        return config!=null && config.getFileName().toString().equals("mcfpp.toml") ? config.getParent() : null;
    }
    public JsonObject model() { return model; }
    public String status() { return status; }
    public boolean busy() { return commands.availablePermits()==0; }
    public void acquire() throws ExecutionException {
        if(ApplicationManager.getApplication().isDispatchThread() && busy()) throw new ExecutionException("An MCFPP operation is running. Wait for it to finish.");
        try { while(!commands.tryAcquire(100,TimeUnit.MILLISECONDS)) ProgressManager.checkCanceled(); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new ExecutionException("MCFPP operation interrupted",e); }
    }
    public void release() { commands.release(); }
    public static JsonObject readModel(String text,Path root) {
        var model=JsonParser.parseString(text).getAsJsonObject();
        if(model.get("schema").getAsInt()!=1 || !Path.of(model.get("root").getAsString()).toAbsolutePath().normalize().equals(root.toAbsolutePath().normalize()))
            throw new IllegalArgumentException("The compiler returned an incompatible project model. Select the bundled compiler.");
        return model;
    }
    public void synchronize() {
        if(root()==null || project.isDisposed()) return;
        new Task.Backgroundable(project,"Synchronize MCFPP project",true) {
            @Override public void run(@NotNull ProgressIndicator indicator) {
                boolean acquired=false;
                try {
                    acquire(); acquired=true; status="Synchronizing dependencies…";
                    Path directory=root();
                    var handler=new CapturingProcessHandler(McfppToolchain.cli(project,directory,List.of("ide-model","--json","--project",directory.toString())));
                    var output=handler.runProcessWithProgressIndicator(indicator);
                    if(output.isCancelled()) return;
                    if(output.getExitCode()!=0) throw new ExecutionException(output.getStderr().isBlank() ? output.getStdout() : output.getStderr());
                    JsonObject next=readModel(output.getStdout(),directory);
                    ApplicationManager.getApplication().invokeAndWait(() -> {
                        if(!project.isDisposed()) { importModel(next); model=next; status="Ready"; refresh(); McfppToolWindowFactory.refresh(project); McfppNotifications.clear(project); }
                    });
                } catch(com.intellij.openapi.progress.ProcessCanceledException cancelled) { status="Synchronization cancelled"; }
                catch(Exception error) { status="Synchronization failed — previous model retained"; McfppNotifications.error(project,error.getMessage()); }
                finally { if(acquired) release(); }
            }
        }.queue();
    }
    public void refresh() {
        if(project.isDisposed()) return;
        VirtualFile directory=LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root());
        if(directory!=null) directory.refresh(true,true);
    }
    private void importModel(JsonObject next) {
        WriteAction.run(() -> {
            var manager=ModuleManager.getInstance(project);
            var properties=com.intellij.ide.util.PropertiesComponent.getInstance(project);
            String moduleName=properties.getValue("mcfpp.managedModule",project.getName()+"-mcfpp");
            var module=Arrays.stream(manager.getModules()).filter(m -> m.getName().equals(moduleName)).findFirst().orElse(null);
            if(module==null) module=Arrays.stream(manager.getModules()).filter(candidate -> {
                var external=ExternalSystemModulePropertyManager.getInstance(candidate);
                var existing=ModuleRootManager.getInstance(candidate);
                var entries=existing.getContentEntries();
                return !external.isMavenized() && external.getExternalSystemId()==null && entries.length==1 &&
                    entries[0].getUrl().equals(VfsUtilCore.pathToUrl(root().toString())) &&
                    entries[0].getSourceFolders().length==0 && Arrays.stream(existing.getOrderEntries())
                        .allMatch(entry -> entry instanceof ModuleSourceOrderEntry || entry instanceof JdkOrderEntry);
            }).findFirst().orElse(null);
            if(module==null) module=manager.newModule(root().resolve(".idea/"+moduleName+".iml"),"JAVA_MODULE");
            properties.setValue("mcfpp.managedModule",module.getName());
            var roots=ModuleRootManager.getInstance(module).getModifiableModel();
            try {
                for(var entry:roots.getContentEntries()) roots.removeContentEntry(entry);
                var content=roots.addContentEntry(VfsUtilCore.pathToUrl(root().toString()));
                for(var library:roots.getModuleLibraryTable().getLibraries()) if(library.getName()!=null && library.getName().startsWith("MCFPP: ")) roots.getModuleLibraryTable().removeLibrary(library);
                addSource(content,Path.of(next.get("sourcePath").getAsString()),false,false);
                Path data=Path.of(next.get("dataPath").getAsString());
                addSource(content,data,false,true);
                if(next.has("assetsPath")) addSource(content,Path.of(next.get("assetsPath").getAsString()),false,true);
                content.addExcludeFolder(VfsUtilCore.pathToUrl(root().resolve(".mcfpp").toString()));
                content.addExcludeFolder(VfsUtilCore.pathToUrl(next.get("targetPath").getAsString()));
                try {
                    Path home=McfppToolchain.javaHome(project);
                    Sdk sdk=sdkForJavaHome(home);
                    roots.setSdk(sdk);
                    if(ProjectRootManager.getInstance(project).getProjectSdk()==null) ProjectRootManager.getInstance(project).setProjectSdk(sdk);
                } catch(Exception error) { status=error.getMessage(); }
                Set<String> seen=new HashSet<>();
                if(next.has("mni") && next.getAsJsonObject("mni").has("sourceSets")) {
                    Path compiler;
                    try { compiler=McfppToolchain.compiler(project).toAbsolutePath().normalize(); }
                    catch(java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
                    var compilerSources=McfppLibraryRootsProvider.compilerSourceRoots(project);
                    for(var value:next.getAsJsonObject("mni").getAsJsonArray("sourceSets")) {
                        var set=value.getAsJsonObject(); boolean test=!"main".equals(set.get("name").getAsString());
                        for(var source:set.getAsJsonArray("sources")) addSource(content,Path.of(source.getAsString()),test,false);
                        for(var source:set.getAsJsonArray("resources")) addSource(content,Path.of(source.getAsString()),test,true);
                        for(var cp:set.getAsJsonArray("compileClasspath")) addLibrary(roots,cp.getAsString(),test ? DependencyScope.TEST : DependencyScope.COMPILE,seen,compiler,compilerSources);
                        for(var cp:set.getAsJsonArray("runtimeClasspath")) addLibrary(roots,cp.getAsString(),test ? DependencyScope.TEST : DependencyScope.RUNTIME,seen,compiler,compilerSources);
                    }
                }
                roots.commit();
            } finally { if(!roots.isDisposed()) roots.dispose(); }
        });
    }
    private void addSource(ContentEntry entry,Path path,boolean test,boolean resource) {
        if(!path.normalize().startsWith(root())) return;
        String url=VfsUtilCore.pathToUrl(path.toString());
        if(Arrays.stream(entry.getSourceFolders()).anyMatch(f -> f.getUrl().equals(url))) return;
        if(resource) entry.addSourceFolder(url,test ? org.jetbrains.jps.model.java.JavaResourceRootType.TEST_RESOURCE : org.jetbrains.jps.model.java.JavaResourceRootType.RESOURCE);
        else entry.addSourceFolder(url,test);
    }
    static Sdk sdkForJavaHome(Path home) {
        var table=ProjectJdkTable.getInstance();
        for(var sdk:table.getAllJdks()) {
            try { if(sdk.getHomePath()!=null && Files.isSameFile(home,Path.of(sdk.getHomePath()))) return sdk; }
            catch(java.io.IOException | RuntimeException ignored) {}
        }
        String name="MCFPP JDK 25";
        for(int suffix=2;table.findJdk(name)!=null;suffix++) name="MCFPP JDK 25 ("+suffix+")";
        var sdk=JavaSdk.getInstance().createJdk(name,home.toString(),false);
        table.addJdk(sdk);
        return sdk;
    }
    private static void addLibrary(ModifiableRootModel roots,String path,DependencyScope scope,Set<String> seen,Path compiler,List<VirtualFile> compilerSources) {
        if(!Files.exists(Path.of(path)) || !seen.add(path)) return;
        String name="MCFPP: "+Path.of(path).getFileName()+"-"+Integer.toHexString(path.hashCode());
        var table=roots.getModuleLibraryTable();
        Library library=Arrays.stream(table.getLibraries()).filter(l -> name.equals(l.getName())).findFirst().orElse(null);
        if(library==null) library=table.createLibrary(name);
        var edit=library.getModifiableModel();
        for(String url:edit.getUrls(OrderRootType.CLASSES)) edit.removeRoot(url,OrderRootType.CLASSES);
        edit.addRoot(Files.isDirectory(Path.of(path)) ? VfsUtilCore.pathToUrl(path) : "jar://"+path.replace('\\','/')+"!/",OrderRootType.CLASSES);
        if(Path.of(path).toAbsolutePath().normalize().equals(compiler)) {
            for(var source:compilerSources) edit.addRoot(source,OrderRootType.SOURCES);
        }
        edit.commit();
        var order=roots.findLibraryOrderEntry(library);
        if(order!=null) order.setScope(scope);
    }
}

