package top.mcfpp.intellij.tooling;

import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import org.jetbrains.annotations.NotNull;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;

public final class McfppSettingsConfigurable implements SearchableConfigurable {
    private final Project project;
    private JPanel panel;
    private final TextFieldWithBrowseButton compiler = new TextFieldWithBrowseButton();
    private final TextFieldWithBrowseButton java = new TextFieldWithBrowseButton();
    private final TextFieldWithBrowseButton node = new TextFieldWithBrowseButton();
    private final TextFieldWithBrowseButton dps = new TextFieldWithBrowseButton();
    private final TextFieldWithBrowseButton assets = new TextFieldWithBrowseButton();
    private final JTextArea resourcePacks = new JTextArea(3,30);
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
        constraints.gridx=0; constraints.gridy=0; constraints.anchor=GridBagConstraints.WEST; constraints.insets=com.intellij.util.ui.JBUI.insets(2,0,2,8);
        add("Compiler JAR:", "Leave blank to use the bundled compiler.", compiler, constraints, false);
        add("JDK 25 home:", "Used by the compiler, MNI, debugger and language server.", java, constraints, true);
        add("Node.js executable:", "Node.js 20 or newer. Leave blank to use PATH.", node, constraints, false);
        add("DSB Java override:", "Leave blank to use the selected JDK.", dps, constraints, false);
        label("Minecraft client JAR:", "Select a Minecraft client JAR for vanilla models, textures and fonts. An assets directory can also be entered.", assets, constraints);
        constraints.gridx=1; constraints.weightx=1; constraints.fill=GridBagConstraints.HORIZONTAL;
        panel.add(assets,constraints); constraints.gridy++;
        assets.addBrowseFolderListener(project,FileChooserDescriptorFactory.createSingleFileDescriptor("jar")
            .withTitle("Select Minecraft Client JAR")
            .withDescription("Select the client JAR from your Minecraft installation (versions/<version>/<version>.jar)."));
        label("Resource packs:", "Additional render resource packs, one directory or ZIP per line. Later packs override earlier packs.", resourcePacks, constraints);
        constraints.gridx=1; constraints.weightx=1; constraints.fill=GridBagConstraints.HORIZONTAL;
        panel.add(new JScrollPane(resourcePacks),constraints); constraints.gridy++;
        add("Linter directory:", "Leave blank to use the bundled Linter.", linter, constraints, true);
        add("Prettier directory:", "Prettier 3 package directory. Leave blank to use the bundled formatter.", prettier, constraints, true);
        for (var checkbox : new JCheckBox[]{format, fix, lint}) {
            constraints.gridx=0; constraints.gridwidth=2; panel.add(checkbox, constraints); constraints.gridy++;
        }
        constraints.weighty=1; constraints.fill=GridBagConstraints.BOTH; panel.add(new JPanel(), constraints);
        reset(); return panel;
    }
    private void label(String text,String hint,JComponent field,GridBagConstraints c) {
        c.gridx=0; c.weightx=0; c.fill=GridBagConstraints.NONE;
        var label=new JLabel(text); label.setLabelFor(field); label.setToolTipText(hint); field.setToolTipText(hint);
        panel.add(label,c);
    }
    private void add(String label,String hint, TextFieldWithBrowseButton field, GridBagConstraints c, boolean directory) {
        label(label,hint,field,c);
        c.gridx=1; c.weightx=1; c.fill=GridBagConstraints.HORIZONTAL; panel.add(field,c); c.gridy++;
        field.addBrowseFolderListener(project, directory ? FileChooserDescriptorFactory.createSingleFolderDescriptor()
            : FileChooserDescriptorFactory.createSingleFileDescriptor());
    }
    @Override public boolean isModified() {
        var s=McfppSettings.get(project);
        return !s.compilerJar.equals(compiler.getText().trim()) || !s.javaHome.equals(java.getText().trim()) ||
            !s.nodePath.equals(node.getText().trim()) || !s.dpsJavaPath.equals(dps.getText().trim()) ||
            !s.minecraftAssetsPath.equals(assets.getText().trim()) || !s.renderResourcePacks.equals(resourcePacks.getText().trim()) ||
            !s.linterPath.equals(linter.getText().trim()) || !s.prettierPath.equals(prettier.getText().trim()) ||
            s.formatOnSave!=format.isSelected() || s.fixOnSave!=fix.isSelected() || s.lintEnabled!=lint.isSelected();
    }
    @Override public void apply() throws ConfigurationException {
        var root=project.getService(top.mcfpp.intellij.project.McfppProjectService.class).root();
        if(root==null && project.getBasePath()!=null) root=Path.of(project.getBasePath());
        if(!assets.getText().isBlank()) {
            String error=McfppRenderAssets.problem(root,assets.getText());
            if(error!=null) throw new ConfigurationException(error,"Minecraft client resources");
        }
        var s=McfppSettings.get(project); s.compilerJar=compiler.getText().trim(); s.javaHome=java.getText().trim();
        s.nodePath=node.getText().trim(); s.dpsJavaPath=dps.getText().trim(); s.formatOnSave=format.isSelected();
        s.minecraftAssetsPath=assets.getText().trim(); s.renderResourcePacks=resourcePacks.getText().trim();
        s.linterPath=linter.getText().trim(); s.prettierPath=prettier.getText().trim();
        s.fixOnSave=fix.isSelected(); s.lintEnabled=lint.isSelected();
        project.getService(top.mcfpp.intellij.format.McfppNodeService.class).restart();
        McfppNotifications.clear(project);
        var resourceRoot=root;
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(() -> {
            McfppNotifications.checkRenderAssets(project,resourceRoot);
            top.mcfpp.intellij.command.McfppCommandService.getInstance(project).restart();
        });
    }
    public void focusMinecraftAssets() {
        com.intellij.util.ui.update.UiNotifyConnector.doWhenFirstShown(assets,() -> {
            if(project.isDisposed()) return;
            com.intellij.openapi.wm.IdeFocusManager.getInstance(project).requestFocus(assets.getTextField(),true);
            assets.getTextField().selectAll();
        });
    }
    @Override public void reset() {
        var s=McfppSettings.get(project); compiler.setText(s.compilerJar); java.setText(s.javaHome);
        node.setText(s.nodePath); dps.setText(s.dpsJavaPath); format.setSelected(s.formatOnSave);
        assets.setText(s.minecraftAssetsPath); resourcePacks.setText(s.renderResourcePacks);
        linter.setText(s.linterPath); prettier.setText(s.prettierPath);
        fix.setSelected(s.fixOnSave); lint.setSelected(s.lintEnabled);
    }
}

