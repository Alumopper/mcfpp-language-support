package top.mcfpp.intellij.mni;

import top.mcfpp.intellij.lang.McfppFileType;
import top.mcfpp.intellij.lang.McfppFileModels;

public final class McfppMacroIntegrationTest extends McfppJavaFixtureTestCase {
    public void testNewCliProviderContributesCompletionAndJavaReference() {
        myFixture.addClass("package top.mcfpp.annotations; public @interface MNIMacro { String identifier() default \"\"; String returnType() default \"void\"; }");
        myFixture.addClass("package top.mcfpp.mni.api; public class MacroContext {}");
        myFixture.addClass("package top.mcfpp.mni.api; public interface Expr<T> {}");
        var provider = myFixture.addClass("""
            package example.mni;
            public class Example {
                @top.mcfpp.annotations.MNIMacro(identifier="answer",returnType="int")
                public static top.mcfpp.mni.api.Expr<Object> answer(top.mcfpp.mni.api.MacroContext context) { return null; }
            }
            """);
        myFixture.addFileToProject("mcfpp.toml", "schema=1\nnamespace=\"demo\"\nsourcePath=\"src/main/mcfpp\"\n[mni]\nenabled=true\n");
        myFixture.addFileToProject("src/main/mcfpp/.mcfpp", "//> injectedBy example.mni.Example\n");
        var source = myFixture.addFileToProject("src/main/mcfpp/main.mcfpp", "func main() { print(answer()); }");
        myFixture.configureFromExistingVirtualFile(source.getVirtualFile());
        assertEquals("demo", McfppFileModels.get(source).namespace());
        assertEquals(1, McfppMacroIndex.all(getProject()).size());
        int offset = source.getText().indexOf("answer()");
        var identifier = source.findElementAt(offset);
        assertNotNull(identifier);
        assertTrue(java.util.Arrays.stream(identifier.getReferences()).anyMatch(r -> r.resolve() == provider.findMethodsByName("answer",false)[0]));
        var snapshot = McfppMacroIndex.snapshot(getProject());
        assertEquals("int", snapshot.symbols.getFirst().typeName);
        assertEquals("demo", snapshot.symbols.getFirst().namespace);
        assertTrue(McfppMacroCompletionContributor.provides(source,offset,"answer","answer(): int"));
        assertFalse(McfppMacroCompletionContributor.provides(source,offset,"answer","answer(value: int): int"));
        assertFalse(McfppMacroCompletionContributor.provides(source,offset,"different","different(): int"));
        var consumer=myFixture.addFileToProject("src/main/mcfpp/consumer.mcfpp", "namespace consumer\nimport demo:answer as result\nfunc run() { result(); demo:answer(); }");
        for(String call:java.util.List.of("result()","answer()")) {
            var usage=consumer.findElementAt(consumer.getText().indexOf(call));
            assertTrue(java.util.Arrays.stream(usage.getReferences()).anyMatch(r -> r.resolve()==provider.findMethodsByName("answer",false)[0]));
        }
        assertEquals("result",McfppMacroIndex.visibleName(consumer,McfppMacroIndex.all(getProject()).getFirst(),consumer.getText().indexOf("result()")));
        assertTrue(McfppMacroCompletionContributor.provides(consumer,consumer.getText().indexOf("result()"),"result","result(): int"));
        var member=myFixture.addFileToProject("src/main/mcfpp/member.mcfpp", "func run() { value.answer(); }");
        assertFalse(McfppMacroCompletionContributor.provides(member,member.getText().indexOf("answer()"),"answer","answer(): int"));
    }
}
