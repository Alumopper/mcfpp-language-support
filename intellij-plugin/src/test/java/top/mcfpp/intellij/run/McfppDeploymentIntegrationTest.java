package top.mcfpp.intellij.run;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.NotNull;
import javax.swing.*;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

public final class McfppDeploymentIntegrationTest extends BasePlatformTestCase {
    public void testEditorPersistsDeploymentAndDisablesItForNonBuildTasks() throws Exception {
        var configuration=(McfppRunConfiguration)McfppRunConfigurations.get(getProject(),McfppTask.BUILD).getConfiguration();
        var editor=new McfppRunSettingsEditor(getProject());
        try {
            var panel=editor.createEditor();
            editor.resetEditorFrom(configuration);
            var destination=com.intellij.util.ui.UIUtil.findComponentOfType(panel,com.intellij.openapi.ui.TextFieldWithBrowseButton.class);
            assertNotNull(destination); assertFalse(destination.getTextField().isEnabled());
            JCheckBox deploy=null;
            for(var component:panel.getComponents()) if(component instanceof JCheckBox box && box.getText().startsWith("Copy datapack")) deploy=box;
            assertNotNull(deploy);
            deploy.doClick(); assertTrue(destination.getTextField().isEnabled());
            destination.setText("  ../my world/datapacks  ");
            editor.applyEditorTo(configuration);
            assertTrue(configuration.deployAfterBuild); assertEquals("../my world/datapacks",configuration.deploymentDirectory);
            panel.setSize(960,650);
            layout(panel);
            var image=new java.awt.image.BufferedImage(960,650,java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics=image.createGraphics();
            try { panel.printAll(graphics); } finally { graphics.dispose(); }
            var screenshot=java.nio.file.Path.of("build/deployment-ui/editor.png");
            java.nio.file.Files.createDirectories(screenshot.getParent());
            javax.imageio.ImageIO.write(image,"png",screenshot.toFile());
            configuration.task=McfppTask.TEST;
            editor.resetEditorFrom(configuration);
            assertFalse(deploy.isEnabled()); assertFalse(destination.getTextField().isEnabled());
            configuration.task=McfppTask.BUILD;
            editor.resetEditorFrom(configuration);
            assertTrue(deploy.isSelected()); assertTrue(destination.getTextField().isEnabled());
        } finally { editor.disposeEditor(); }
    }

    private static void layout(java.awt.Container component) {
        component.doLayout();
        for(var child:component.getComponents()) if(child instanceof java.awt.Container container) layout(container);
    }

    public void testBuildRunConfigurationDeploysCompiledDatapack() throws Exception {
        var fixture = Files.createTempDirectory("mcfpp-deployment-test-").toRealPath();
        try {
            var root = fixture.resolve("project");
            Files.createDirectories(root.resolve("src/main/mcfpp"));
            Files.writeString(root.resolve("mcfpp.toml"), "schema = 1\nname = \"deployment-demo\"\nnamespace = \"deployment_demo\"\n");
            Files.writeString(root.resolve("src/main/mcfpp/main.mcfpp"), "func main() { print(\"Deployment works!\") }\n");
            var settings = McfppRunConfigurations.get(getProject(), McfppTask.BUILD);
            var configuration = (McfppRunConfiguration) settings.getConfiguration();
            configuration.projectPath = root.toString();
            configuration.deployAfterBuild = true;
            configuration.deploymentDirectory = "../world/datapacks";
            var executor = com.intellij.execution.executors.DefaultRunExecutor.getRunExecutorInstance();
            var environment = com.intellij.execution.runners.ExecutionEnvironmentBuilder.create(executor, settings).build();
            var result = configuration.getState(executor, environment).execute(executor, new McfppProgramRunner());
            assertNotNull(result);
            try {
                var handler = result.getProcessHandler();
                var output = new StringBuilder();
                handler.addProcessListener(new ProcessAdapter() {
                    @Override public void onTextAvailable(@NotNull ProcessEvent event, @NotNull com.intellij.openapi.util.Key type) {
                        synchronized (output) { output.append(event.getText()); }
                    }
                });
                handler.startNotify();
                boolean finished = handler.waitFor(60000);
                if (!finished) handler.destroyProcess();
                assertTrue("Build timed out: " + output, finished);
                assertEquals(output.toString(), Integer.valueOf(0), handler.getExitCode());
                assertTrue(output.toString(), output.toString().contains("MCFPP datapack deployed to:"));
                assertTrue(Files.isRegularFile(fixture.resolve("world/datapacks/deployment-demo/pack.mcmeta")));
            } finally {
                if (result.getExecutionConsole() != null) com.intellij.openapi.util.Disposer.dispose(result.getExecutionConsole());
            }
        } finally {
            FileUtil.delete(fixture.toFile());
        }
    }

    public void testCompletionFailureIsReportedBeforeProcessTerminates() throws Exception {
        var completed=new AtomicBoolean();
        String executable=java.nio.file.Path.of(System.getProperty("java.home"),"bin",com.intellij.openapi.util.SystemInfo.isWindows ? "java.exe" : "java").toString();
        var handler=new McfppProcessHandler(new GeneralCommandLine(executable,"-version"),(code,process) -> {
            assertEquals(0,(int)code); completed.set(true); return 1;
        });
        var terminatedAfterCompletion=new AtomicBoolean();
        handler.addProcessListener(new ProcessAdapter() {
            @Override public void processTerminated(@NotNull ProcessEvent event) { terminatedAfterCompletion.set(completed.get()); }
        });
        handler.startNotify();
        assertTrue(handler.waitFor(30000));
        assertTrue(terminatedAfterCompletion.get()); assertEquals(Integer.valueOf(1),handler.getExitCode());
    }
}
