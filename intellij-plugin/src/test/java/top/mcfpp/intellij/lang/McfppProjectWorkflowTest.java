package top.mcfpp.intellij.lang;
import junit.framework.TestCase;
import top.mcfpp.language.ProjectConfiguration;
import java.nio.file.Path;

public final class McfppProjectWorkflowTest extends TestCase {
    public void testTomlPathsAndNamespace() {
        Path root=Path.of("build/project-model-tests").toAbsolutePath();
        var model=ProjectConfiguration.parse(root.resolve("mcfpp.toml"),"""
            schema=1
            namespace="demo"
            sourcePath="code"
            version="26.3"
            [mni]
            enabled=true
            """);
        assertEquals(root.resolve("code"),model.sourcePath());
        assertEquals("demo.library",model.namespaceFor(root.resolve("code/library/test.mcfpp")));
        assertEquals("demo",model.namespaceFor(root.resolve("code/main.mcfpp")));
        assertEquals("demo.x_m_l_models",model.namespaceFor(root.resolve("code/XMLModels/main.mcfpp")));
        assertEquals("26.3",model.version()); assertTrue(model.mniEnabled());
    }
    public void testLambdaParametersStayInsideTheirCallback() {
        String source="func run() { consume { value: int -> print(value) }; print(value) }";
        var model=McfppFileModel.parse(source);
        int inside=source.indexOf("print(value)")+6, outside=source.lastIndexOf("print(value)")+6;
        assertEquals(1,model.visibleLocalSymbols("value",inside).size());
        assertTrue(model.visibleLocalSymbols("value",outside).isEmpty());
        assertEquals("int",McfppTypes.declaredType(model.visibleLocalSymbols("value",inside).getFirst()));
    }
    public void testImplicitItAndFunctionTypes() {
        String source="func run() { var callback: (int) -> int = { it + 1 }; print(it) }";
        var model=McfppFileModel.parse(source);
        assertFalse(model.visibleLocalSymbols("it",source.indexOf("it +")).isEmpty());
        assertTrue(model.visibleLocalSymbols("it",source.lastIndexOf("it)")).isEmpty());
        var callback=model.symbols().stream().filter(s -> s.name().equals("callback")).findFirst().orElseThrow();
        assertEquals("(int) -> int",McfppTypes.declaredType(callback));
    }
}
