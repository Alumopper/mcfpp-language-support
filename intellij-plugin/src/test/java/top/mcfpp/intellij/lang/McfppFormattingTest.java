package top.mcfpp.intellij.lang;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.codeInsight.template.impl.TemplateSettings;
import com.intellij.ide.fileTemplates.FileTemplateManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixture4TestCase;
import org.junit.Test;

public final class McfppFormattingTest extends LightPlatformCodeInsightFixture4TestCase {
    @Test
    public void testRegistersCreationLiveTemplates() {
        TemplateSettings templates = TemplateSettings.getInstance();

        for (String key : new String[]{"fn", "nfn", "data", "object", "enum", "ctor", "field", "if", "for"}) {
            assertNotNull("Missing MCFPP live template: " + key, templates.getTemplate(key, "MCFPP"));
        }
        String nativeFunctionTemplate = templates.getTemplate("nfn", "MCFPP").getString();
        assertFalse(nativeFunctionTemplate.contains("native func"));
        assertTrue(nativeFunctionTemplate.startsWith("func "));

        FileTemplateManager fileTemplates = FileTemplateManager.getInstance(getProject());
        for (String template : new String[]{
                "MCFPP Data Type.mcfpp", "MCFPP Object Data.mcfpp", "MCFPP Enum.mcfpp", "MCFPP Function.mcfpp"
        }) {
            assertNotNull("Missing MCFPP file template: " + template, fileTemplates.getTemplate(template));
        }
    }

    @Test
    public void testFormatsSpacingAndNestedIndentation() {
        PsiFile file = myFixture.configureByText(McfppFileType.INSTANCE, """
                data Example:Base{
                func test(value as int)->bool{
                var result as int=math:doubled(value)
                if(value>0){
                return true
                }
                }
                }
                """);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            CodeStyleManager.getInstance(getProject()).reformat(file);
        });

        assertEquals("""
                data Example: Base {
                    func test(value as int) -> bool {
                        var result as int = math:doubled(value)
                        if (value > 0) {
                            return true
                        }
                    }
                }
                """, file.getText());
    }

    @Test
    public void testFormatsGenericAnglesContinuationIndentAndElseSpacing() {
        PsiFile file = myFixture.configureByText(McfppFileType.INSTANCE, """
                @From <example.mni.Bridge>
                data Generic{
                func map <kind as type> (
                value as list <int>,
                other as int
                )->list<int>{
                if(value<other){
                return value
                }else{
                return other
                }
                }
                }
                """);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            CodeStyleManager.getInstance(getProject()).reformat(file);
        });

        assertEquals("""
                @From<example.mni.Bridge>
                data Generic {
                    func map<kind as type>(
                            value as list<int>,
                            other as int
                    ) -> list<int> {
                        if (value < other) {
                            return value
                        } else {
                            return other
                        }
                    }
                }
                """, file.getText());
    }

    @Test
    public void testFormatsForeachColonWithoutChangingNamespaceColons() {
        PsiFile file = myFixture.configureByText(McfppFileType.INSTANCE, """
                import demo.models:Player
                func visit(values as list<Player>){
                for(entry:values){
                notify(entry)
                }
                }
                """);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            CodeStyleManager.getInstance(getProject()).reformat(file);
        });

        assertEquals("""
                import demo.models:Player
                func visit(values as list<Player>) {
                    for (entry : values) {
                        notify(entry)
                    }
                }
                """, file.getText());
    }
}
