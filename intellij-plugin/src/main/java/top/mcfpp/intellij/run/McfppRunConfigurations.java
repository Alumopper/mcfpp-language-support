package top.mcfpp.intellij.run;
import com.intellij.execution.*;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.openapi.project.Project;
import com.intellij.ide.util.PropertiesComponent;
import java.nio.file.Path;
public final class McfppRunConfigurations {
    private McfppRunConfigurations() {}
    public static RunnerAndConfigurationSettings get(Project project,McfppTask task) {
        var manager=RunManager.getInstance(project);
        for(var entry:manager.getAllSettings()) if(entry.getConfiguration() instanceof McfppRunConfiguration c && c.task==task) return entry;
        var type=ConfigurationTypeUtil.findConfigurationType(McfppRunConfigurationType.class);
        var settings=manager.createConfiguration("MCFPP "+task.title,type);
        var configuration=(McfppRunConfiguration)settings.getConfiguration();
        configuration.task=task;
        if(task==McfppTask.BUILD) configuration.arguments="-level=INFO";
        if(task==McfppTask.DUMP_IR) configuration.arguments="-level=INFO --dump-ir";
        manager.addConfiguration(settings);
        return settings;
    }
    public static void initialize(Project project,boolean mni) {
        var properties=PropertiesComponent.getInstance(project);
        if(!properties.getBoolean("mcfpp.runConfigurationsCreated",false)) {
            for(var task:McfppTask.values()) get(project,task);
            var manager=RunManager.getInstance(project);
            if(manager.getSelectedConfiguration()==null) manager.setSelectedConfiguration(get(project,McfppTask.BUILD));
            properties.setValue("mcfpp.runConfigurationsCreated",true);
        }
        if(!properties.getBoolean("mcfpp.sourceTestConfigurationCreated",false)) {
            get(project,McfppTask.TEST); properties.setValue("mcfpp.sourceTestConfigurationCreated",true);
        }
        if(!properties.getBoolean("mcfpp.irConfigurationCreated",false)) {
            get(project,McfppTask.DUMP_IR); properties.setValue("mcfpp.irConfigurationCreated",true);
        }
    }
    public static void run(Project project,McfppTask task) {
        ExecutionUtil.runConfiguration(get(project,task),DefaultRunExecutor.getRunExecutorInstance());
    }
    public static void runTest(Project project,Path directory,String id) {
        startTest(project,directory,id,false);
    }
    public static void debugTest(Project project,Path directory,String id) {
        startTest(project,directory,id,true);
    }
    private static void startTest(Project project,Path directory,String id,boolean debug) {
        var manager=RunManager.getInstance(project);
        var type=ConfigurationTypeUtil.findConfigurationType(McfppRunConfigurationType.class);
        var settings=manager.createConfiguration("MCFPP "+id,type);
        var configuration=(McfppRunConfiguration)settings.getConfiguration();
        configuration.task=McfppTask.TEST; configuration.projectPath=directory.toString();
        configuration.arguments="--tests "+com.intellij.util.execution.ParametersListUtil.join(java.util.List.of(id));
        manager.setTemporaryConfiguration(settings);
        ExecutionUtil.runConfiguration(settings,debug ? com.intellij.execution.executors.DefaultDebugExecutor.getDebugExecutorInstance() : DefaultRunExecutor.getRunExecutorInstance());
    }
    public static void debugFunction(Project project,Path directory,String id) {
        var manager=RunManager.getInstance(project);
        var type=ConfigurationTypeUtil.findConfigurationType(McfppRunConfigurationType.class);
        var settings=manager.createConfiguration("Debug "+id,type);
        var configuration=(McfppRunConfiguration)settings.getConfiguration();
        configuration.task=McfppTask.BUILD; configuration.projectPath=directory.toString(); configuration.debugFunction=id;
        manager.setTemporaryConfiguration(settings);
        ExecutionUtil.runConfiguration(settings,com.intellij.execution.executors.DefaultDebugExecutor.getDebugExecutorInstance());
    }
}

