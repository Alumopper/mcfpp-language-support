package top.mcfpp.intellij.mni;

import top.mcfpp.intellij.lang.McfppSyntaxHighlighter;

public final class McfppNativeFunctionHighlightingTest extends McfppJavaFixtureTestCase {
    @Override protected void setUp() throws Exception {
        super.setUp();
        myFixture.addClass("package top.mcfpp.annotations; public @interface MNIMacro { String[] normalParams() default {}; String returnType() default \"void\"; }");
        myFixture.addClass("package top.mcfpp.annotations; public @interface MNIFunction { String[] normalParams() default {}; String[] readOnlyParams() default {}; String returnType() default \"void\"; }");
        myFixture.addClass("package top.mcfpp.mni.api; public class MacroContext {}");
        myFixture.addClass("package top.mcfpp.mni.api; public class NativeCallContext {}");
        myFixture.addClass("package top.mcfpp.mni.api; public interface Expr<T> {}");
        myFixture.addClass("package example; public class Provider { @top.mcfpp.annotations.MNIMacro(returnType=\"int\") public static top.mcfpp.mni.api.Expr<Object> expand(top.mcfpp.mni.api.MacroContext ctx) { return null; } @top.mcfpp.annotations.MNIFunction(returnType=\"int\") public static void nativeValue(top.mcfpp.mni.api.NativeCallContext ctx) {} }");
    }
    public void testDeclarationsCallsAliasesAndProviderTargetsHaveDistinctColors() {
        var api = myFixture.addFileToProject("api.mcfpp", "namespace api\nfunc expand(): int = example.Provider.expand\nfunc nativeValue(): int = example.Provider.nativeValue\nfunc ordinary(): int { return 0 }\n");
        var consumer = myFixture.addFileToProject("consumer.mcfpp", "namespace consumer\nimport api:expand as macroAlias\nimport api:nativeValue as nativeAlias\nfunc main() { macroAlias(); nativeAlias(); api:expand(); }\n");
        myFixture.configureFromExistingVirtualFile(consumer.getVirtualFile());
        assertSame(McfppSyntaxHighlighter.MACRO_FUNCTION, McfppNativeFunctionHighlighting.keyFor(api.findElementAt(api.getText().indexOf("expand()"))));
        assertSame(McfppSyntaxHighlighter.MNI_FUNCTION, McfppNativeFunctionHighlighting.keyFor(api.findElementAt(api.getText().indexOf("nativeValue()"))));
        assertSame(McfppSyntaxHighlighter.MACRO_FUNCTION, McfppNativeFunctionHighlighting.keyFor(api.findElementAt(api.getText().indexOf("Provider.expand") + "Provider.".length())));
        assertSame(McfppSyntaxHighlighter.MACRO_FUNCTION, McfppNativeFunctionHighlighting.keyFor(consumer.findElementAt(consumer.getText().indexOf("macroAlias()"))));
        assertSame(McfppSyntaxHighlighter.MNI_FUNCTION, McfppNativeFunctionHighlighting.keyFor(consumer.findElementAt(consumer.getText().indexOf("nativeAlias()"))));
        assertNull(McfppNativeFunctionHighlighting.keyFor(api.findElementAt(api.getText().indexOf("ordinary()"))));
    }
    public void testLocalFunctionValuesKeepTheirOrdinaryStyle() {
        var file = myFixture.addFileToProject("main.mcfpp", "namespace api\nfunc expand(): int = example.Provider.expand\nfunc main() { var expand: () -> int = { -> 0 }; expand(); }\n");
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        assertNull(McfppNativeFunctionHighlighting.keyFor(file.findElementAt(file.getText().lastIndexOf("expand()"))));
    }
    public void testDefaultStylesUseDifferentColorsAndFontStyles() {
        var scheme = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().getGlobalScheme();
        var macro = scheme.getAttributes(McfppSyntaxHighlighter.MACRO_FUNCTION);
        var nativeFunction = scheme.getAttributes(McfppSyntaxHighlighter.MNI_FUNCTION);
        assertNotNull(macro.getForegroundColor());
        assertNotNull(nativeFunction.getForegroundColor());
        assertFalse(macro.getForegroundColor().equals(nativeFunction.getForegroundColor()));
        assertEquals(java.awt.Font.BOLD, macro.getFontType());
        assertEquals(java.awt.Font.ITALIC, nativeFunction.getFontType());
    }
}
