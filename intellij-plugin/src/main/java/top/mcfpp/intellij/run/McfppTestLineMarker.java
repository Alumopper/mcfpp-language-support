package top.mcfpp.intellij.run;

import com.intellij.execution.lineMarker.RunLineMarkerContributor;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.lang.*;
import top.mcfpp.language.ProjectConfiguration;
import java.nio.file.Path;

public final class McfppTestLineMarker extends RunLineMarkerContributor implements DumbAware {
    @Override public Info getInfo(@NotNull PsiElement element) {
        if(element.getFirstChild()!=null || !(element.getContainingFile() instanceof McfppFile file)) return null;
        var virtual=file.getVirtualFile();
        if(virtual==null) return null;
        var configuration=ProjectConfiguration.find(Path.of(virtual.getPath()));
        if(configuration==null) return null;
        var tests=configuration.getParent().resolve("src/test/mcfpp");
        if(!Path.of(virtual.getPath()).startsWith(tests)) return null;
        var model=McfppFileModels.get(file);
        var symbol=model.symbols().stream().filter(s -> s.kind()==McfppSymbolKind.FUNCTION && s.owner()==null && s.nameOffset()==element.getTextOffset()).findFirst().orElse(null);
        if(symbol==null || !isTest(file,model,symbol)) return null;
        String id=symbol.qualifiedName();
        var action=new DumbAwareAction("Run "+id) {
            @Override public void actionPerformed(@NotNull AnActionEvent event) {
                McfppRunConfigurations.runTest(file.getProject(),configuration.getParent(),id);
            }
        };
        var debug=new DumbAwareAction("Debug "+id) {
            @Override public void actionPerformed(@NotNull AnActionEvent event) {
                McfppRunConfigurations.debugTest(file.getProject(),configuration.getParent(),id);
            }
        };
        return new Info(AllIcons.RunConfigurations.TestState.Run,new AnAction[]{action,debug},e -> "Run or debug MCFPP test "+id);
    }

    private boolean isTest(McfppFile file,McfppFileModel model,McfppSymbol symbol) {
        int start=model.symbols().stream().filter(s -> s.endOffset()<=symbol.declarationStart()).mapToInt(McfppSymbol::endOffset).max().orElse(0);
        var lexer=new McfppLexer();
        var text=McfppFileModels.activeSource(file);
        lexer.start(text,start,symbol.declarationStart(),0);
        while(lexer.getTokenType()!=null) {
            if(lexer.getTokenType()==McfppTokenTypes.ANNOTATION && text.substring(lexer.getTokenStart(),lexer.getTokenEnd()).equals("@Test")) return true;
            lexer.advance();
        }
        return false;
    }
}
