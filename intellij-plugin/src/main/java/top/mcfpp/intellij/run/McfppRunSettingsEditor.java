package top.mcfpp.intellij.run;
import com.intellij.openapi.options.SettingsEditor;
import org.jetbrains.annotations.NotNull;
import javax.swing.*;
import java.awt.*;
public final class McfppRunSettingsEditor extends SettingsEditor<McfppRunConfiguration> {
    private final JPanel panel=new JPanel(new GridLayout(0,2,8,8));
    private final JComboBox<McfppTask> task=new JComboBox<>(McfppTask.values());
    private final JTextField root=new JTextField(),args=new JTextField();
    private final JTextArea env=new JTextArea(4,30);
    private final JCheckBox offline=new JCheckBox("Offline"),locked=new JCheckBox("Locked dependencies");
    public McfppRunSettingsEditor() {
        panel.add(new JLabel("Task")); panel.add(task); panel.add(new JLabel("Project directory")); panel.add(root);
        panel.add(new JLabel("Additional CLI arguments")); panel.add(args);
        panel.add(new JLabel("Environment (NAME=value per line)")); panel.add(new JScrollPane(env)); panel.add(offline); panel.add(locked);
    }
    @Override protected void resetEditorFrom(@NotNull McfppRunConfiguration c) {
        task.setSelectedItem(c.task); root.setText(c.projectPath); args.setText(c.arguments); offline.setSelected(c.offline); locked.setSelected(c.locked);
        env.setText(c.environment.entrySet().stream().map(e -> e.getKey()+"="+e.getValue()).collect(java.util.stream.Collectors.joining("\n")));
    }
    @Override protected void applyEditorTo(@NotNull McfppRunConfiguration c) {
        c.task=(McfppTask)task.getSelectedItem(); c.projectPath=root.getText().trim(); c.arguments=args.getText();
        c.offline=offline.isSelected(); c.locked=locked.isSelected(); c.environment.clear();
        for(String line:env.getText().split("\\R")) { int index=line.indexOf('='); if(index>0) c.environment.put(line.substring(0,index).trim(),line.substring(index+1)); }
    }
    @Override protected @NotNull JComponent createEditor() { return panel; }
}

