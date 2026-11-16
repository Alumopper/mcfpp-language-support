package top.mcfpp.intellij.run;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import org.jetbrains.annotations.NotNull;
import javax.swing.*;
import java.awt.*;
public final class McfppRunSettingsEditor extends SettingsEditor<McfppRunConfiguration> {
    private final JPanel panel=new JPanel(new GridLayout(0,2,8,8));
    private final JComboBox<McfppTask> task=new JComboBox<>(McfppTask.values());
    private final JTextField root=new JTextField(),args=new JTextField();
    private final JTextField debugFunction=new JTextField();
    private final JCheckBox stopOnEntry=new JCheckBox("Stop on entry");
    private final JTextArea env=new JTextArea(4,30);
    private final JCheckBox offline=new JCheckBox("Offline"),locked=new JCheckBox("Locked dependencies");
    private final JCheckBox deploy=new JCheckBox("Copy datapack after successful build");
    private final TextFieldWithBrowseButton destination=new TextFieldWithBrowseButton();
    public McfppRunSettingsEditor(Project project) {
        panel.add(new JLabel("Task")); panel.add(task); panel.add(new JLabel("Project directory")); panel.add(root);
        panel.add(new JLabel("Additional CLI arguments")); panel.add(args);
        panel.add(deploy); panel.add(new JLabel("Build / Dump IR"));
        panel.add(new JLabel("Deployment directory (e.g. world/datapacks)")); panel.add(destination);
        panel.add(new JLabel("")); panel.add(new JLabel("<html>Replaces &lt;directory&gt;/&lt;pack name&gt; after each build.<br>Relative paths use the project directory.</html>"));
        destination.addBrowseFolderListener(project,FileChooserDescriptorFactory.createSingleFolderDescriptor());
        task.addActionListener(e -> updateDeploymentControls());
        deploy.addActionListener(e -> updateDeploymentControls());
        panel.add(new JLabel("Debug function (namespace:function)")); panel.add(debugFunction);
        panel.add(stopOnEntry); panel.add(new JLabel(""));
        panel.add(new JLabel("Environment (NAME=value per line)")); panel.add(new JScrollPane(env)); panel.add(offline); panel.add(locked);
        updateDeploymentControls();
    }
    private void updateDeploymentControls() {
        boolean build=task.getSelectedItem()==McfppTask.BUILD || task.getSelectedItem()==McfppTask.DUMP_IR;
        deploy.setEnabled(build); destination.setEnabled(build && deploy.isSelected());
    }
    @Override protected void resetEditorFrom(@NotNull McfppRunConfiguration c) {
        task.setSelectedItem(c.task); root.setText(c.projectPath); args.setText(c.arguments); offline.setSelected(c.offline); locked.setSelected(c.locked);
        debugFunction.setText(c.debugFunction); stopOnEntry.setSelected(c.debugStopOnEntry);
        deploy.setSelected(c.deployAfterBuild); destination.setText(c.deploymentDirectory); updateDeploymentControls();
        env.setText(c.environment.entrySet().stream().map(e -> e.getKey()+"="+e.getValue()).collect(java.util.stream.Collectors.joining("\n")));
    }
    @Override protected void applyEditorTo(@NotNull McfppRunConfiguration c) {
        c.task=(McfppTask)task.getSelectedItem(); c.projectPath=root.getText().trim(); c.arguments=args.getText();
        c.debugFunction=debugFunction.getText().trim(); c.debugStopOnEntry=stopOnEntry.isSelected();
        c.deployAfterBuild=deploy.isSelected(); c.deploymentDirectory=destination.getText().trim();
        c.offline=offline.isSelected(); c.locked=locked.isSelected(); c.environment.clear();
        for(String line:env.getText().split("\\R")) { int index=line.indexOf('='); if(index>0) c.environment.put(line.substring(0,index).trim(),line.substring(index+1)); }
    }
    @Override protected @NotNull JComponent createEditor() { return panel; }
    @Override protected void disposeEditor() { destination.dispose(); }
}

