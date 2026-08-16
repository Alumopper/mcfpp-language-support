package top.mcfpp.intellij.mni;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.util.List;

public final class MniJavaResolverTest extends LightJavaCodeInsightFixtureTestCase {
    public void testResolvesOnlyStaticJavaMniMethods() {
        myFixture.addClass("""
                package example.mni;

                public final class Bridge {
                    public static void invoke(String value) {}
                    private static void invoke(long value) {}
                    public void invoke(int value) {}
                }
                """);
        MniJavaTargetParser.Target target = MniJavaTargetParser
                .parse("func invoke(value as string) = example.mni.Bridge.invoke;")
                .getFirst();
        MniJavaTargetParser.Segment methodSegment = target.segments().getLast();

        List<PsiElement> resolved = MniJavaResolver.resolve(getProject(), target, methodSegment);

        assertSize(1, resolved);
        PsiMethod method = assertInstanceOf(resolved.getFirst(), PsiMethod.class);
        assertEquals("invoke", method.getName());
        assertTrue(method.hasModifierProperty(PsiModifier.STATIC));
        assertEquals("String", method.getParameterList().getParameter(0).getType().getPresentableText());
    }
}
