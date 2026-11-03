package top.mcfpp.intellij.lang;

import com.intellij.execution.RunManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jdom.Element;
import top.mcfpp.intellij.format.McfppSaveListener;
import top.mcfpp.intellij.run.*;

public final class McfppWorkflowIntegrationTest extends BasePlatformTestCase {
    public void testBothProjectWizardsRenderTheirSettings() {
        var context=new com.intellij.ide.util.projectWizard.WizardContext(null,getTestRootDisposable());
        for(var wizard:java.util.List.of(new top.mcfpp.intellij.project.McfppProjectWizard(),new top.mcfpp.intellij.project.McfppMniProjectWizard())) {
            var step=wizard.createStep(new com.intellij.ide.wizard.RootNewProjectWizardStep(context));
            var panel=com.intellij.ui.dsl.builder.BuilderKt.panel(builder -> { step.setupUI(builder); return kotlin.Unit.INSTANCE; });
            assertNotNull(com.intellij.util.ui.UIUtil.findComponentOfType(panel,com.intellij.openapi.ui.TextFieldWithBrowseButton.class));
        }
    }
    public void testGeneratedTasksPreserveUserSettingsAndAddMniTestOnce() {
        McfppRunConfigurations.initialize(getProject(),false);
        var manager=RunManager.getInstance(getProject());
        assertEquals(8,manager.getAllSettings().stream().filter(s -> s.getConfiguration() instanceof McfppRunConfiguration).count());
        var settings=McfppRunConfigurations.get(getProject(),McfppTask.BUILD);
        assertSame(settings,manager.getSelectedConfiguration());
        var build=(McfppRunConfiguration)settings.getConfiguration();
        assertEquals("-level=INFO",build.arguments);
        build.arguments="--output \"custom output\""; build.locked=true; build.environment.put("EXAMPLE","one=two");
        McfppRunConfigurations.initialize(getProject(),true);
        McfppRunConfigurations.initialize(getProject(),true);
        assertEquals(8,manager.getAllSettings().stream().filter(s -> s.getConfiguration() instanceof McfppRunConfiguration).count());
        assertEquals("-level=INFO --dump-ir",((McfppRunConfiguration)McfppRunConfigurations.get(getProject(),McfppTask.DUMP_IR).getConfiguration()).arguments);
        assertEquals("--output \"custom output\"",build.arguments);
        var state=new Element("configuration"); build.writeExternal(state);
        var restored=(McfppRunConfiguration)build.clone(); restored.arguments=""; restored.environment.clear();
        assertEquals("one=two",build.environment.get("EXAMPLE"));
        restored.readExternal(state);
        assertEquals(build.arguments,restored.arguments); assertTrue(restored.locked);
        assertEquals("one=two",restored.environment.get("EXAMPLE"));
    }
    public void testFormattingIsOneUndoableEditorOperation() {
        myFixture.configureByText(McfppFileType.INSTANCE,"func run(){print(1)}");
        var document=myFixture.getEditor().getDocument();
        WriteCommandAction.runWriteCommandAction(getProject(),"Format MCFPP on save",null,
            () -> McfppSaveListener.replace(document,"func run() {\n    print(1)\n}\n"));
        assertEquals("func run() {\n    print(1)\n}\n",document.getText());
        var editor=TextEditorProvider.getInstance().getTextEditor(myFixture.getEditor());
        assertTrue(UndoManager.getInstance(getProject()).isUndoAvailable(editor));
        UndoManager.getInstance(getProject()).undo(editor);
        assertEquals("func run(){print(1)}",document.getText());
    }
}
