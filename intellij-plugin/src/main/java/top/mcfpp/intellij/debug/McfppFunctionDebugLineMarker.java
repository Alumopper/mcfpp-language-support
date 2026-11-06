package top.mcfpp.intellij.debug;

import com.intellij.execution.lineMarker.RunLineMarkerContributor;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.lang.*;
import top.mcfpp.intellij.run.McfppRunConfigurations;
import top.mcfpp.language.ProjectConfiguration;
import java.nio.file.Path;

public final class McfppFunctionDebugLineMarker extends RunLineMarkerContributor implements DumbAware {
    @Override public Info getInfo(@NotNull PsiElement element) {
        if (element.getFirstChild() != null || !(element.getContainingFile() instanceof McfppFile file) || file.getVirtualFile() == null) return null;
        var path = Path.of(file.getVirtualFile().getPath());
        var config = ProjectConfiguration.find(path);
        if (config == null || path.startsWith(config.getParent().resolve("src/test/mcfpp"))) return null;
        var symbol = McfppFileModels.get(file).symbols().stream().filter(s -> s.kind() == McfppSymbolKind.FUNCTION && s.owner() == null && s.nameOffset() == element.getTextOffset()).findFirst().orElse(null);
        if (symbol == null || symbol.bodyStart() < 0 || !symbol.signature().matches("(?s).*\\bfunc\\s+" + java.util.regex.Pattern.quote(symbol.name()) + "\\s*\\(\\s*\\).*")) return null;
        String id = symbol.qualifiedName();
        var action = new DumbAwareAction("Debug " + id) {
            @Override public void actionPerformed(@NotNull AnActionEvent event) { McfppRunConfigurations.debugFunction(file.getProject(), config.getParent(), id); }
        };
        return new Info(AllIcons.Actions.StartDebugger, new AnAction[]{action}, e -> "Debug MCFPP function " + id);
    }
}
