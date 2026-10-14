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
    public void testFormatsAnnotationsAndBareControlConditions() {
        PsiFile file = myFixture.configureByText(McfppFileType.INSTANCE, """
                func check<T,N:int>(value:models:Counter):bool{
                dynamic count:int=0
                const ready:bool=true
                if ready{count=1}else if check<models:Counter,2>(value){count=2}
                while ready{count++}
                do{count++}while ready
                return ready}
                """);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            CodeStyleManager.getInstance(getProject()).reformat(file);
        });
        assertTrue(file.getText(), file.getText().contains("func check<T, N: int>(value: models:Counter): bool {"));
        assertTrue(file.getText(), file.getText().contains("dynamic count: int = 0"));
        assertTrue(file.getText(), file.getText().contains("if ready {"));
        assertTrue(file.getText(), file.getText().contains("while ready"));
        assertFalse(file.getText().contains(";"));
    }

    @Test
    public void testRegistersCreationLiveTemplates() {
        TemplateSettings templates = TemplateSettings.getInstance();

        for (String key : new String[]{"fn", "nfn", "data", "object", "enum", "ctor", "field", "if", "for"}) {
            assertNotNull("Missing MCFPP live template: " + key, templates.getTemplate(key, "MCFPP"));
        }
        String nativeFunctionTemplate = templates.getTemplate("nfn", "MCFPP").getString();
        assertFalse(nativeFunctionTemplate.contains("native func"));
        assertTrue(nativeFunctionTemplate.startsWith("func "));
        assertTrue(nativeFunctionTemplate.contains(": $RETURN$"));
        assertFalse(nativeFunctionTemplate.endsWith(";"));
        assertEquals("var $NAME$: $TYPE$ = $VALUE$", templates.getTemplate("field", "MCFPP").getString());

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
                func test(value: int):bool{
                var result: int=math:doubled(value)
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
                    func test(value: int): bool {
                        var result: int = math:doubled(value)
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
                func map <kind> (
                value: list <int>,
                other: int
                ):list<int>{
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
                    func map<kind>(
                            value: list<int>,
                            other: int
                    ): list<int> {
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
                func visit(values: list<Player>){
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
                func visit(values: list<Player>) {
                    for (entry : values) {
                        notify(entry)
                    }
                }
                """, file.getText());
    }
}
