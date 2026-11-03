package top.mcfpp.intellij.tooling;

import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import org.jetbrains.annotations.NotNull;
import javax.swing.*;
import java.awt.*;

public final class McfppSettingsConfigurable implements SearchableConfigurable {
    private final Project project;
    private JPanel panel;
    private final TextFieldWithBrowseButton compiler = new TextFieldWithBrowseButton();
    private final TextFieldWithBrowseButton java = new TextFieldWithBrowseButton();
    private final TextFieldWithBrowseButton node = new TextFieldWithBrowseButton();
    private final TextFieldWithBrowseButton dps = new TextFieldWithBrowseButton();
    private final TextFieldWithBrowseButton linter = new TextFieldWithBrowseButton();
    private final TextFieldWithBrowseButton prettier = new TextFieldWithBrowseButton();
    private final JCheckBox format = new JCheckBox("Format MCFPP files on save");
    private final JCheckBox fix = new JCheckBox("Apply safe Linter fixes on save");
    private final JCheckBox lint = new JCheckBox("Show Linter diagnostics while editing");
    public McfppSettingsConfigurable(Project project) { this.project = project; }
    @Override public @NotNull String getId() { return "mcfpp"; }
    @Override public String getDisplayName() { return "MCFPP"; }
    @Override public JComponent createComponent() {
        panel = new JPanel(new GridBagLayout());
        var constraints = new GridBagConstraints();
        constraints.gridx=0; constraints.gridy=0; constraints.anchor=GridBagConstraints.WEST; constraints.insets=new Insets(5,5,5,5);
        add("Compiler JAR (blank: bundled)", compiler, constraints, false);
        add("JDK 21 home (blank: automatic)", java, constraints, true);
        add("Node.js 20+ (blank: PATH)", node, constraints, false);
        add("Command service Java 25+ (blank: automatic)", dps, constraints, false);
        add("Linter package directory (blank: bundled)", linter, constraints, true);
        add("Prettier 3 package directory (blank: bundled)", prettier, constraints, true);
        for (var checkbox : new JCheckBox[]{format, fix, lint}) {
            constraints.gridx=0; constraints.gridwidth=2; panel.add(checkbox, constraints); constraints.gridy++;
        }
        constraints.weighty=1; panel.add(new JPanel(), constraints);
        reset(); return panel;
    }
    private void add(String label, TextFieldWithBrowseButton field, GridBagConstraints c, boolean directory) {
        c.gridx=0; c.weightx=0; c.fill=GridBagConstraints.NONE; panel.add(new JLabel(label), c);
        c.gridx=1; c.weightx=1; c.fill=GridBagConstraints.HORIZONTAL; panel.add(field,c); c.gridy++;
        field.addBrowseFolderListener(project, directory ? FileChooserDescriptorFactory.createSingleFolderDescriptor()
            : FileChooserDescriptorFactory.createSingleFileDescriptor());
    }
    @Override public boolean isModified() {
        var s=McfppSettings.get(project);
        return !s.compilerJar.equals(compiler.getText().trim()) || !s.javaHome.equals(java.getText().trim()) ||
            !s.nodePath.equals(node.getText().trim()) || !s.dpsJavaPath.equals(dps.getText().trim()) ||
            !s.linterPath.equals(linter.getText().trim()) || !s.prettierPath.equals(prettier.getText().trim()) ||
            s.formatOnSave!=format.isSelected() || s.fixOnSave!=fix.isSelected() || s.lintEnabled!=lint.isSelected();
    }
    @Override public void apply() {
        var s=McfppSettings.get(project); s.compilerJar=compiler.getText().trim(); s.javaHome=java.getText().trim();
        s.nodePath=node.getText().trim(); s.dpsJavaPath=dps.getText().trim(); s.formatOnSave=format.isSelected();
        s.linterPath=linter.getText().trim(); s.prettierPath=prettier.getText().trim();
        s.fixOnSave=fix.isSelected(); s.lintEnabled=lint.isSelected();
        project.getService(top.mcfpp.intellij.format.McfppNodeService.class).restart();
        McfppNotifications.clear(project);
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(() ->
            top.mcfpp.intellij.command.McfppCommandService.getInstance(project).restart());
    }
    @Override public void reset() {
        var s=McfppSettings.get(project); compiler.setText(s.compilerJar); java.setText(s.javaHome);
        node.setText(s.nodePath); dps.setText(s.dpsJavaPath); format.setSelected(s.formatOnSave);
        linter.setText(s.linterPath); prettier.setText(s.prettierPath);
        fix.setSelected(s.fixOnSave); lint.setSelected(s.lintEnabled);
    }
}

