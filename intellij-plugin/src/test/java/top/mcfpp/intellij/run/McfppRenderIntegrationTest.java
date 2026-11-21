package top.mcfpp.intellij.run;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jdom.Element;
import javax.swing.*;

public final class McfppRenderIntegrationTest extends BasePlatformTestCase {
    public void testRenderConfigurationPersistsAndBuildEnablesMetadata() throws Exception {
        var configuration=(McfppRunConfiguration)McfppRunConfigurations.get(getProject(),McfppTask.BUILD).getConfiguration();
        configuration.render=true; configuration.renderFunction="demo:main";
        configuration.projectPath=java.nio.file.Path.of("../../MCFPP/examples/display-animation").toAbsolutePath().normalize().toString();
        var state=new Element("configuration"); configuration.writeExternal(state);
        var restored=configuration.clone(); restored.render=false; restored.renderFunction=""; restored.readExternal(state);
        assertTrue(restored.render); assertEquals("demo:main",restored.renderFunction);
        assertTrue(restored.commandLine().getParametersList().getList().contains("--render-info"));
        assertFalse(restored.commandLine().getParametersList().getList().contains("--debug-info"));
        assertTrue(restored.commandLine().getParametersList().getList().contains("--reuse-build"));
        restored.arguments="--no-reuse-build";
        assertFalse(restored.commandLine().getParametersList().getList().contains("--reuse-build"));
        assertFalse(new top.mcfpp.intellij.debug.McfppDebugRunner().canRun("Debug",restored));
    }

    public void testRenderOutputChannelsRemainSeparate() throws Exception {
        var configuration=(McfppRunConfiguration)McfppRunConfigurations.get(getProject(),McfppTask.BUILD).getConfiguration();
        var service=getProject().getService(top.mcfpp.intellij.project.McfppProjectService.class);
        service.acquire();
        var handler=new McfppRenderProcessHandler(configuration,service);
        var compiler=new StringBuilder(); var dsb=new StringBuilder();
        handler.addProcessListener(new com.intellij.execution.process.ProcessAdapter() {
            @Override public void onTextAvailable(com.intellij.execution.process.ProcessEvent event,com.intellij.openapi.util.Key type) { compiler.append(event.getText()); }
        });
        handler.runtime().addProcessListener(new com.intellij.execution.process.ProcessAdapter() {
            @Override public void onTextAvailable(com.intellij.execution.process.ProcessEvent event,com.intellij.openapi.util.Key type) { dsb.append(event.getText()); }
        });
        handler.notifyTextAvailable("compiled",com.intellij.execution.process.ProcessOutputTypes.STDOUT);
        handler.runtime().notifyTextAvailable("tick",com.intellij.execution.process.ProcessOutputTypes.STDOUT);
        assertEquals("compiled",compiler.toString()); assertEquals("tick",dsb.toString());
    }

    public void testTestRenderingRequiresOneCase() {
        var configuration=(McfppRunConfiguration)McfppRunConfigurations.get(getProject(),McfppTask.TEST).getConfiguration();
        configuration.arguments="--tests demo:sample";
        assertEquals("demo:sample",McfppRenderSession.target(configuration));
        configuration.arguments="--tests=demo:*";
        try { McfppRenderSession.target(configuration); fail("Wildcard test was accepted"); }
        catch(IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("Select one")); }
    }

    public void testEditorUpdatesRenderControlsAndPersistsTarget() throws Exception {
        var configuration=(McfppRunConfiguration)McfppRunConfigurations.get(getProject(),McfppTask.BUILD).getConfiguration();
        var editor=new McfppRunSettingsEditor(getProject());
        try {
            var panel=editor.createEditor(); editor.resetEditorFrom(configuration);
            JCheckBox render=null;
            for(var child:panel.getComponents()) if(child instanceof JCheckBox box && box.getText().equals("Open DSB render window")) render=box;
            assertNotNull(render); assertTrue(render.isEnabled()); render.doClick();
            editor.applyEditorTo(configuration); assertTrue(configuration.render);
            configuration.task=McfppTask.LINT; editor.resetEditorFrom(configuration); assertFalse(render.isEnabled());
        } finally { editor.disposeEditor(); }
    }
}
