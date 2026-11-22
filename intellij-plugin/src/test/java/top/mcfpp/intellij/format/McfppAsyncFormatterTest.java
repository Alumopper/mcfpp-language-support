package top.mcfpp.intellij.format;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixture4TestCase;
import org.junit.Test;
import top.mcfpp.intellij.lang.McfppFileType;
import top.mcfpp.intellij.tooling.McfppSettings;
import static org.junit.Assert.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class McfppAsyncFormatterTest extends LightPlatformCodeInsightFixture4TestCase {
    @Test
    public void unavailableNodeUsesTheBuiltInPsiFormatter() throws Exception {
        String oldFormatterPath = System.getProperty("mcfpp.formatter.path");
        Path formatterScript = Files.createTempFile("mcfpp-missing-node", ".mjs");
        McfppSettings.get(getProject()).nodePath = Path.of(getProject().getBasePath(), "missing-node").toString();
        System.setProperty("mcfpp.formatter.path", formatterScript.toString());
        PsiFile file = myFixture.configureByText(McfppFileType.INSTANCE, "func run(){\nvar value:int=1\n}");

        try {
            McfppNodeService service = getProject().getService(McfppNodeService.class);
            assertThrows(McfppNodeService.FormatterUnavailableException.class,
                    () -> service.request("format", file.getVirtualFile().getPath(), file.getText(), true, 0));
            assertFalse(new McfppAsyncFormatter().canFormat(file));
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                CodeStyleManager.getInstance(getProject()).reformat(file);
            });

            assertEquals("func run() {\n    var value: int = 1\n}", file.getText());
        } finally {
            if (oldFormatterPath == null) System.clearProperty("mcfpp.formatter.path");
            else System.setProperty("mcfpp.formatter.path", oldFormatterPath);
            Files.deleteIfExists(formatterScript);
        }
    }
}
