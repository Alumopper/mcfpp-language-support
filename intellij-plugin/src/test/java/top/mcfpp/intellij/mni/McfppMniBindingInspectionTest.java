package top.mcfpp.intellij.mni;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import top.mcfpp.intellij.lang.McfppFileType;

import java.util.List;

public final class McfppMniBindingInspectionTest extends LightJavaCodeInsightFixtureTestCase {
    public void testInactiveNativeBindingsDoNotProduceJavaDiagnostics() {
        myFixture.configureByText(McfppFileType.INSTANCE, """
                #if MC >= 26.3
                func modern() = missing.Bridge.modern;
                #else
                func legacy() {}
                #endif
                """);
        assertNoMniProblems(myFixture.doHighlighting());
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addClass("""
                package top.mcfpp.annotations;
                public @interface MNIFunction {
                    String[] readOnlyParams() default {};
                    String[] normalParams() default {};
                    String caller() default "void";
                    String returnType() default "void";
                }
                """);
        myFixture.enableInspections(McfppMniBindingInspection.class);
    }

    public void testAcceptsMatchingAnnotatedStaticMethod() {
        myFixture.addClass("""
                package example.mni;
                import top.mcfpp.annotations.MNIFunction;
                public final class Bridge {
                    @MNIFunction(readOnlyParams = {"type"}, normalParams = {"int", "string"})
                    public static void convert(Object type, Object value, Object text) {}
                }
                """);
        myFixture.configureByText(
                McfppFileType.INSTANCE,
                "func convert<kind as type>(value as int, text as string) = example.mni.Bridge.convert;"
        );

        assertNoMniProblems(myFixture.doHighlighting());
    }

    public void testReportsAnnotationParameterMismatch() {
        myFixture.addClass("""
                package example.mni;
                import top.mcfpp.annotations.MNIFunction;
                public final class Bridge {
                    @MNIFunction(normalParams = {"string"})
                    public static void convert(Object value) {}
                }
                """);
        myFixture.configureByText(
                McfppFileType.INSTANCE,
                "func convert(value as int) = example.mni.Bridge.convert;"
        );

        assertMniProblem("No @MNIFunction overload matches MCFPP parameters (int)");
    }

    public void testReportsCompilerMethodNameRule() {
        myFixture.addClass("""
                package example.mni;
                import top.mcfpp.annotations.MNIFunction;
                public final class Bridge {
                    @MNIFunction public static void javaName() {}
                }
                """);
        myFixture.configureByText(
                McfppFileType.INSTANCE,
                "func mcfppName() = example.mni.Bridge.javaName;"
        );

        assertMniProblem("MCFPP compiler searches for Java method 'mcfppName'");
    }

    public void testReportsNonStaticAndWrongJavaArgumentCount() {
        myFixture.addClass("""
                package example.mni;
                import top.mcfpp.annotations.MNIFunction;
                public final class Bridge {
                    @MNIFunction(normalParams = {"int"}) public void nonStatic(Object value) {}
                    @MNIFunction(normalParams = {"int"}) public static void returnsValue(Object value) {}
                }
                """);

        myFixture.configureByText(
                McfppFileType.INSTANCE,
                "func nonStatic(value as int) = example.mni.Bridge.nonStatic;"
        );
        assertMniProblem("MNI Java method must be static");

        myFixture.configureByText(
                McfppFileType.INSTANCE,
                "func returnsValue(value as int) -> int = example.mni.Bridge.returnsValue;"
        );
        assertMniProblem("this binding passes 2");
    }

    public void testReportsUnresolvedFromClass() {
        myFixture.configureByText(
                McfppFileType.INSTANCE,
                "@From<example.mni.Missing> data Example {}"
        );

        assertMniProblem("@From class 'example.mni.Missing' cannot be resolved");
    }

    private void assertMniProblem(String messagePart) {
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        assertTrue(
                highlights.toString(),
                highlights.stream().anyMatch(info -> info.getDescription() != null &&
                        info.getDescription().contains(messagePart))
        );
    }

    private static void assertNoMniProblems(List<HighlightInfo> highlights) {
        assertFalse(
                highlights.toString(),
                highlights.stream().anyMatch(info -> info.getDescription() != null &&
                        (info.getDescription().contains("MNI Java") ||
                                info.getDescription().contains("@MNIFunction") ||
                                info.getDescription().contains("@From class") ||
                                info.getDescription().contains("MCFPP compiler searches")))
        );
    }
}
