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
    private final JComboBox<String> optimization=new JComboBox<>(new String[]{"Project default (O2)", "O0", "O1", "O2"});
    private final JTextField root=new JTextField(),args=new JTextField();
    private final JTextField debugFunction=new JTextField();
    private final JCheckBox stopOnEntry=new JCheckBox("Stop on entry");
    private final JCheckBox render=new JCheckBox("Open DSB render window");
    private final JTextField renderFunction=new JTextField();
    private final JTextField runFunction=new JTextField();
    private final JTextArea env=new JTextArea(4,30);
    private final JCheckBox offline=new JCheckBox("Offline"),locked=new JCheckBox("Locked dependencies");
    private final JCheckBox deploy=new JCheckBox("Copy datapack after successful build");
    private final TextFieldWithBrowseButton destination=new TextFieldWithBrowseButton();
    public McfppRunSettingsEditor(Project project) {
        panel.add(new JLabel("Task")); panel.add(task); panel.add(new JLabel("Project directory")); panel.add(root);
        panel.add(new JLabel("Additional CLI arguments")); panel.add(args);
        panel.add(new JLabel("Optimization level")); panel.add(optimization);
        panel.add(deploy); panel.add(new JLabel("Build / Dump IR"));
        panel.add(new JLabel("Deployment directory (e.g. world/datapacks)")); panel.add(destination);
        panel.add(new JLabel("")); panel.add(new JLabel("<html>Replaces &lt;directory&gt;/&lt;pack name&gt; after each build.<br>Relative paths use the project directory.</html>"));
        destination.addBrowseFolderListener(project,FileChooserDescriptorFactory.createSingleFolderDescriptor());
        task.addActionListener(e -> updateDeploymentControls());
        deploy.addActionListener(e -> updateDeploymentControls());
        panel.add(new JLabel("Debug function (namespace:function)")); panel.add(debugFunction);
        panel.add(stopOnEntry); panel.add(new JLabel(""));
        panel.add(render); panel.add(new JLabel("Build / single MCFPP test"));
        panel.add(new JLabel("Render function (namespace:function; Build only)")); panel.add(renderFunction);
        panel.add(new JLabel("Run function (blank for main)")); panel.add(runFunction);
        render.addActionListener(e -> updateDeploymentControls());
        panel.add(new JLabel("Environment (NAME=value per line)")); panel.add(new JScrollPane(env)); panel.add(offline); panel.add(locked);
        updateDeploymentControls();
    }
    private void updateDeploymentControls() {
        boolean build=task.getSelectedItem()==McfppTask.BUILD || task.getSelectedItem()==McfppTask.DUMP_IR;
        deploy.setEnabled(build); destination.setEnabled(build && deploy.isSelected());
        render.setEnabled(task.getSelectedItem()==McfppTask.BUILD || task.getSelectedItem()==McfppTask.TEST);
        renderFunction.setEnabled(task.getSelectedItem()==McfppTask.BUILD && render.isSelected());
        runFunction.setEnabled(task.getSelectedItem()==McfppTask.RUN);
        optimization.setEnabled(task.getSelectedItem() instanceof McfppTask selected && selected.compilesSources());
    }
    @Override protected void resetEditorFrom(@NotNull McfppRunConfiguration c) {
        task.setSelectedItem(c.task); root.setText(c.projectPath); args.setText(c.arguments); offline.setSelected(c.offline); locked.setSelected(c.locked);
        debugFunction.setText(c.debugFunction); stopOnEntry.setSelected(c.debugStopOnEntry);
        render.setSelected(c.render); renderFunction.setText(c.renderFunction);
        runFunction.setText(c.runFunction);
        optimization.setSelectedIndex(c.optimizationLevel==null ? 0 : c.optimizationLevel+1);
        deploy.setSelected(c.deployAfterBuild); destination.setText(c.deploymentDirectory); updateDeploymentControls();
        env.setText(c.environment.entrySet().stream().map(e -> e.getKey()+"="+e.getValue()).collect(java.util.stream.Collectors.joining("\n")));
    }
    @Override protected void applyEditorTo(@NotNull McfppRunConfiguration c) {
        c.task=(McfppTask)task.getSelectedItem(); c.projectPath=root.getText().trim(); c.arguments=args.getText();
        c.debugFunction=debugFunction.getText().trim(); c.debugStopOnEntry=stopOnEntry.isSelected();
        c.render=render.isEnabled() && render.isSelected(); c.renderFunction=renderFunction.getText().trim();
        c.runFunction=runFunction.getText().trim();
        c.optimizationLevel=optimization.getSelectedIndex()==0 ? null : optimization.getSelectedIndex()-1;
        c.deployAfterBuild=deploy.isSelected(); c.deploymentDirectory=destination.getText().trim();
        c.offline=offline.isSelected(); c.locked=locked.isSelected(); c.environment.clear();
        for(String line:env.getText().split("\\R")) { int index=line.indexOf('='); if(index>0) c.environment.put(line.substring(0,index).trim(),line.substring(index+1)); }
    }
    @Override protected @NotNull JComponent createEditor() { return panel; }
    @Override protected void disposeEditor() { destination.dispose(); }
}

