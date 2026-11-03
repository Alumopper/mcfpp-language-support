package top.mcfpp.intellij.project;

import com.google.gson.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.*;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.ui.*;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.*;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.run.*;
import top.mcfpp.intellij.tooling.*;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

public final class McfppToolWindowFactory implements ToolWindowFactory {
    @Override public void createToolWindowContent(@NotNull Project project,@NotNull ToolWindow window) {
        var service=project.getService(McfppProjectService.class);
        var root=service.root();
        var panel=new ContentPanel(); panel.setBorder(JBUI.Borders.empty(12));
        javax.swing.Timer[] activeTimer = new javax.swing.Timer[1];
        if(root==null) {
            panel.add(new JLabel("<html>Open a project containing mcfpp.toml,<br>or choose MCFPP in File | New | Project.</html>"),BorderLayout.NORTH);
        } else {
            var heading=new JPanel(); heading.setLayout(new BoxLayout(heading,BoxLayout.Y_AXIS));
            var title=new JLabel(project.getName()); title.setFont(title.getFont().deriveFont(Font.BOLD,16)); heading.add(title);
            var path=new JLabel(root.toString()); path.setToolTipText(root.toString()); heading.add(path);
            var status=new JLabel(service.status()); heading.add(status);
            var tools=new JLabel(); heading.add(tools);
            try(var input=McfppToolWindowFactory.class.getResourceAsStream("/toolchain/toolchain.json")) {
                var versions=JsonParser.parseReader(new java.io.InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                heading.add(new JLabel("MCFPP "+versions.get("compilerVersion").getAsString()+" · Prettier "+versions.get("prettierVersion").getAsString()+" · Linter "+versions.get("linterVersion").getAsString()));
            } catch(Exception ignored) {}
            try { tools.setText("JDK 21: "+McfppToolchain.javaHome(project)); } catch(Exception error) { tools.setText("JDK 21: configure in MCFPP settings"); }
            var tasks=new JPanel(new GridLayout(0,3,6,6));
            for(var task:McfppTask.values()) {
                var button=new JButton(task.title); tasks.add(button);
                button.addActionListener(e -> McfppRunConfigurations.run(project,task));
                if(task==McfppTask.TEST) button.setEnabled(service.model()!=null && service.model().get("mniEnabled").getAsBoolean());
            }
            heading.add(Box.createVerticalStrut(12)); heading.add(tasks);
            var tableModel=new DefaultTableModel(new String[]{"Dependency","Version","Scope"},0) {
                @Override public boolean isCellEditable(int row,int col) { return false; }
            };
            var table=new JBTable(tableModel); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.setPreferredScrollableViewportSize(new Dimension(320,160));
            Runnable dependencies=() -> loadDependencies(root,tableModel);
            dependencies.run();
            var dependenciesPanel=new JPanel(new BorderLayout(0,6));
            dependenciesPanel.add(new JLabel("Dependencies"),BorderLayout.NORTH);
            dependenciesPanel.add(new JScrollPane(table),BorderLayout.CENTER);
            var actions=new JPanel(new FlowLayout(FlowLayout.LEADING,4,0));
            var add=new JButton("Add…"); var remove=new JButton("Remove"); var sync=new JButton("Synchronize");
            actions.add(add); actions.add(remove); actions.add(sync); dependenciesPanel.add(actions,BorderLayout.SOUTH);
            add.addActionListener(e -> {
                var dialog=new DependencyDialog(project);
                if(dialog.showAndGet()) {
                    List<String> args=new ArrayList<>(List.of("add",dialog.coordinate.getText().trim()));
                    if(dialog.kind.getSelectedIndex()==1) { args.add("--mni"); args.add("--configuration"); args.add(dialog.configuration.getText().trim()); }
                    dependencyCommand(project,args,dependencies);
                }
            });
            remove.addActionListener(e -> {
                int row=table.getSelectedRow(); if(row<0) return;
                String coordinate=tableModel.getValueAt(row,0).toString();
                String scope=tableModel.getValueAt(row,2).toString();
                if(scope.isBlank()) return;
                List<String> args=new ArrayList<>(List.of("remove",coordinate));
                if(!scope.equals("MCFPP")) {
                    args.set(1,coordinate.substring(0,coordinate.lastIndexOf(':')));
                    args.addAll(List.of("--mni","--configuration",scope));
                }
                dependencyCommand(project,args,dependencies);
            });
            sync.addActionListener(e -> service.synchronize());
            var bottom=new JPanel(new FlowLayout(FlowLayout.LEADING,6,0));
            var artifacts=new JButton("Data Pack");
            artifacts.addActionListener(e -> {
                Path output=service.model()!=null ? Path.of(service.model().get("targetPath").getAsString()) : root.resolve("build");
                if(service.model()!=null && service.model().has("name")) output=output.resolve(service.model().get("name").getAsString());
                var file=com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(output);
                if(file==null) Messages.showInfoMessage(project,"Build the project to generate its datapack.","MCFPP");
                else com.intellij.ide.actions.RevealFileAction.openDirectory(output);
            });
            var clientAssets=new JButton("Resource Pack");
            clientAssets.addActionListener(e -> {
                Path output=service.model()!=null && service.model().has("resourcepackPath")
                    ? Path.of(service.model().get("resourcepackPath").getAsString()) : root.resolve("build").resolve(root.getFileName()+"-resources");
                if(!Files.isDirectory(output)) Messages.showInfoMessage(project,"Add files under assetsPath and build to generate the resource pack.","MCFPP");
                else com.intellij.ide.actions.RevealFileAction.openDirectory(output);
            });
            var settings=new JButton("Settings");
            settings.addActionListener(e -> ShowSettingsUtil.getInstance().showSettingsDialog(project,McfppSettingsConfigurable.class));
            bottom.add(artifacts); bottom.add(clientAssets); bottom.add(settings);
            panel.add(heading,BorderLayout.NORTH); panel.add(dependenciesPanel,BorderLayout.CENTER); panel.add(bottom,BorderLayout.SOUTH);
            var timer=new javax.swing.Timer(1000,e -> {
                status.setText(service.status());
                for(Component component:tasks.getComponents()) if(component instanceof JButton button) {
                    boolean test="Test".equals(button.getText());
                    button.setEnabled(!service.busy() && (!test || service.model()!=null && service.model().get("mniEnabled").getAsBoolean()));
                }
            });
            timer.start(); activeTimer[0] = timer;
        }
        var scroll=new com.intellij.ui.components.JBScrollPane(panel);
        scroll.setBorder(JBUI.Borders.empty());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        var content = ContentFactory.getInstance().createContent(scroll,"",false);
        if (activeTimer[0] != null) content.setDisposer(activeTimer[0]::stop);
        window.getContentManager().addContent(content);
    }
    private static final class ContentPanel extends JPanel implements Scrollable {
        ContentPanel() { super(new BorderLayout(0,12)); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible,int orientation,int direction) { return 24; }
        @Override public int getScrollableBlockIncrement(Rectangle visible,int orientation,int direction) { return Math.max(24,visible.height-24); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
    public static void refresh(Project project) {
        var window = ToolWindowManager.getInstance(project).getToolWindow("MCFPP");
        if (window == null || window.getContentManager().getContentCount() == 0) return;
        window.getContentManager().removeAllContents(true);
        new McfppToolWindowFactory().createToolWindowContent(project,window);
    }
    private static void loadDependencies(Path root,DefaultTableModel rows) {
        rows.setRowCount(0);
        try {
            var data=JsonParser.parseString(Files.readString(root.resolve("mcfpp-packages.json"))).getAsJsonObject();
            if(data.has("mcfpp")) for(var entry:data.getAsJsonObject("mcfpp").entrySet()) rows.addRow(new Object[]{entry.getKey(),entry.getValue().getAsString(),"MCFPP"});
            if(data.has("mni")) for(var value:data.getAsJsonArray("mni")) {
                var entry=value.isJsonObject() ? value.getAsJsonObject() : null;
                String id=entry==null ? value.getAsString() : entry.get("id").getAsString();
                rows.addRow(new Object[]{id,id.substring(id.lastIndexOf(':')+1),
                    entry==null ? "implementation" : entry.get("configuration").getAsString()});
            }
        } catch(Exception error) { rows.addRow(new Object[]{"Unable to read mcfpp-packages.json",error.getMessage(),""}); }
    }
    private static void dependencyCommand(Project project,List<String> args,Runnable refresh) {
        var service=project.getService(McfppProjectService.class);
        new Task.Backgroundable(project,"Update MCFPP dependencies",true) {
            @Override public void run(@NotNull ProgressIndicator indicator) {
                boolean acquired=false,success=false;
                try {
                    service.acquire(); acquired=true;
                    args.addAll(List.of("--project",service.root().toString()));
                    var result=new CapturingProcessHandler(McfppToolchain.cli(project,service.root(),args)).runProcessWithProgressIndicator(indicator);
                    if(result.isCancelled()) return;
                    if(result.getExitCode()!=0) throw new java.io.IOException(result.getStderr()+result.getStdout());
                    success=true;
                } catch(Exception error) { McfppNotifications.error(project,error.getMessage()); }
                finally {
                    if(acquired) service.release();
                    if(success) ApplicationManager.getApplication().invokeLater(() -> { if(!project.isDisposed()) { refresh.run(); service.synchronize(); } });
                }
            }
        }.queue();
    }
    private static final class DependencyDialog extends DialogWrapper {
        private final JTextField coordinate=new JTextField(32),configuration=new JTextField("implementation");
        private final JComboBox<String> kind=new JComboBox<>(new String[]{"MCFPP package","Java / MNI"});
        DependencyDialog(Project project) { super(project); setTitle("Add MCFPP Dependency"); init(); }
        @Override protected JComponent createCenterPanel() {
            var panel=new JPanel(new GridLayout(0,2,8,8));
            panel.add(new JLabel("Kind")); panel.add(kind); panel.add(new JLabel("Coordinate")); panel.add(coordinate);
            panel.add(new JLabel("Java configuration")); panel.add(configuration);
            coordinate.setToolTipText("MCFPP: group:name@^1.0.0; Java: group:artifact:version");
            configuration.setEnabled(false); kind.addActionListener(e -> configuration.setEnabled(kind.getSelectedIndex()==1));
            return panel;
        }
        @Override protected ValidationInfo doValidate() {
            return coordinate.getText().isBlank() ? new ValidationInfo("Enter a dependency coordinate.",coordinate) : null;
        }
    }
}

