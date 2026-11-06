package top.mcfpp.intellij.project;

import com.intellij.ide.wizard.*;
import com.intellij.ide.wizard.language.LanguageGeneratorNewProjectWizard;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.ui.dsl.builder.Panel;
import javax.swing.*;
import org.jetbrains.annotations.NotNull;
import kotlin.Unit;
import top.mcfpp.intellij.lang.McfppIcons;
import top.mcfpp.intellij.run.McfppRunConfigurations;
import top.mcfpp.intellij.tooling.*;
import java.nio.file.*;
import java.util.*;

public class McfppProjectWizard implements LanguageGeneratorNewProjectWizard {
    protected boolean mni() { return false; }
    @Override public @NotNull String getName() { return mni() ? "MCFPP MNI" : "MCFPP"; }
    @Override public @NotNull Icon getIcon() { return McfppIcons.FILE; }
    @Override public int getOrdinal() { return 900; }
    @Override public @NotNull NewProjectWizardStep createStep(@NotNull NewProjectWizardStep parent) {
        return new AbstractNewProjectWizardStep(parent) {
            private final TextFieldWithBrowseButton jdk=new TextFieldWithBrowseButton();
            @Override public void setupUI(@NotNull Panel panel) {
                jdk.addBrowseFolderListener(null,FileChooserDescriptorFactory.createSingleFolderDescriptor());
                jdk.getTextField().setColumns(32);
                jdk.getTextField().setToolTipText("JDK 25 home directory; leave blank to use a detected JDK 25.");
                try { jdk.setText(McfppToolchain.javaHome(getContext().getProject()==null
                    ? com.intellij.openapi.project.ProjectManager.getInstance().getDefaultProject() : getContext().getProject()).toString()); }
                catch(Exception ignored) {}
                panel.row("JDK 25:",row -> {
                    row.cell(jdk).validationOnApply((builder,field) -> {
                        String value=field.getText().trim();
                        if(value.isEmpty()) {
                            try { McfppToolchain.javaHome(getContext().getProject()==null
                                ? com.intellij.openapi.project.ProjectManager.getInstance().getDefaultProject() : getContext().getProject()); }
                            catch(Exception error) { return new ValidationInfo("Select a JDK 25 installation.",field); }
                            return null;
                        }
                        try { if(McfppToolchain.isJdk25(Path.of(value))) return null; }
                        catch(RuntimeException ignored) {}
                        return new ValidationInfo("Select a JDK 25 home directory containing bin/javac.",field);
                    });
                    return Unit.INSTANCE;
                });
                panel.row("",row -> { row.label(mni() ? "<html>Java MNI provider and JUnit sample.<br>Build and Test tasks are created automatically.</html>"
                    : "<html>A minimal datapack project.<br>Build and check tasks are created automatically.</html>"); return Unit.INSTANCE; });
            }
            @Override public void setupProject(@NotNull Project project) {
                String selected=jdk.getText().trim();
                if(!selected.isEmpty()) McfppSettings.get(project).javaHome=selected;
                Path target=NewProjectWizardBaseData.getBaseData(this).getProjectPath();
                new Task.Backgroundable(project,"Create "+getName()+" project",true) {
                    @Override public void run(@NotNull ProgressIndicator indicator) {
                        try {
                            Files.createDirectories(target);
                            List<String> args=new ArrayList<>(List.of("init","."));
                            if(mni()) args.add("--mni");
                            var result=new CapturingProcessHandler(McfppToolchain.cli(project,target,args)).runProcessWithProgressIndicator(indicator);
                            if(result.isCancelled()) return;
                            if(result.getExitCode()!=0) throw new java.io.IOException(result.getStderr()+result.getStdout());
                            ApplicationManager.getApplication().invokeLater(() -> {
                                if(project.isDisposed()) return;
                                var file=LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target.resolve("src/main/mcfpp/main.mcfpp"));
                                if(file!=null) FileEditorManager.getInstance(project).openFile(file,true);
                                McfppRunConfigurations.initialize(project,mni());
                                project.getService(McfppProjectService.class).synchronize();
                            },project.getDisposed());
                        } catch(Exception error) { McfppNotifications.error(project,error.getMessage()); }
                    }
                }.queue();
            }
        };
    }
}

