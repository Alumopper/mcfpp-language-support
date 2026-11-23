package top.mcfpp.intellij.run;

import com.intellij.execution.console.DuplexConsoleView;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ExecutionConsole;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;
import java.util.Arrays;

/** Presents compilation and program output on separate tabs. */
final class McfppExecutionConsole implements ExecutionConsole {
    private final DuplexConsoleView<ConsoleView,ConsoleView> consoles;
    private final JPanel component=new JPanel(new BorderLayout());
    private final JBTabbedPane tabs=new JBTabbedPane();
    private final int runtimeTab;

    McfppExecutionConsole(ConsoleView compilation,ConsoleView runtime,boolean test,boolean headless) {
        consoles=new DuplexConsoleView<>(compilation,runtime);
        consoles.enableConsole(!headless);
        Disposer.register(this,consoles);
        runtimeTab=headless ? 0 : 1;
        if(headless) tabs.addTab("Program Output",new JPanel());
        tabs.addTab(test ? "Compilation / Tests" : "Compilation",new JPanel());
        if(!headless) tabs.addTab("DSB",new JPanel());
        tabs.setPreferredSize(new Dimension(0,JBUI.scale(32)));
        tabs.addChangeListener(event -> consoles.enableConsole(tabs.getSelectedIndex()!=runtimeTab));
        component.add(tabs,BorderLayout.NORTH);
        component.add(consoles.getComponent(),BorderLayout.CENTER);
    }

    void showRuntime() { tabs.setSelectedIndex(runtimeTab); }
    void showCompilation() { tabs.setSelectedIndex(1-runtimeTab); }
    AnAction[] actions() {
        return Arrays.stream(consoles.createConsoleActions())
            .filter(action -> action.getTemplatePresentation()!=consoles.getSwitchConsoleActionPresentation()).toArray(AnAction[]::new);
    }
    @Override public JComponent getComponent() { return component; }
    @Override public JComponent getPreferredFocusableComponent() { return consoles.getPreferredFocusableComponent(); }
    @Override public void dispose() {}
}
