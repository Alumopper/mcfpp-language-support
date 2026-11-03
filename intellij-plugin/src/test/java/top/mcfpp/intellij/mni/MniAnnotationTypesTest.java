package top.mcfpp.intellij.mni;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.*;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.rename.RenameProcessor;
import top.mcfpp.intellij.lang.McfppNamedElement;

import java.util.*;

public final class MniAnnotationTypesTest extends McfppJavaFixtureTestCase {
    @Override protected void setUp() throws Exception {
        super.setUp();
        myFixture.addClass("package top.mcfpp.annotations; public @interface MNIFunction { String[] normalParams() default {}; String[] readOnlyParams() default {}; String returnType() default \"void\"; String caller() default \"void\"; String[] genericType() default {}; String identifier() default \"\"; String[] tag() default {}; }");
        myFixture.addClass("package top.mcfpp.annotations; public @interface MNIOperator { String paramType() default \"\"; String returnType(); String operator(); }");
        myFixture.addClass("package top.mcfpp.annotations; public @interface MNIMacro { MacroParam[] params() default {}; String returnType() default \"void\"; String identifier() default \"\"; }");
        myFixture.addClass("package top.mcfpp.annotations; public @interface MacroParam { String name(); String type(); }");
        myFixture.addFileToProject("mcfpp.toml", "schema=1\nnamespace=\"demo\"\nsourcePath=\"src/main/mcfpp\"\n");
        myFixture.addFileToProject("src/main/mcfpp/types.mcfpp", "namespace demo;\n/** A test type. */\ndata Widget {}\ndata Box<T> {}\n");
    }

    public void testEveryTypeAttributeResolvesAndUnrelatedStringsAreIgnored() {
        var file = myFixture.configureByText("Provider.java", """
                import top.mcfpp.annotations.*;
                class Provider {
                    @MNIFunction(normalParams={"list<demo:Widget>", "Widget = something"}, readOnlyParams="Widget",
                        caller="demo:Widget", returnType="Widget", genericType="E", identifier="Widget", tag="Widget")
                    void nativeEntry() {}
                    @MNIMacro(params=@MacroParam(name="value", type="NBTRef<Widget>"), returnType="Widget", identifier="Widget")
                    void macro() {}
                    @MNIOperator(operator="Widget", paramType="Widget", returnType="Widget") void operator() {}
                }
                """);
        int references = 0;
        for (var literal : PsiTreeUtil.findChildrenOfType(file, PsiLiteralExpression.class)) {
            var context = MniTypeContext.from(literal);
            if (context != null && context.field != MniTypeContext.Field.GENERIC) {
                var refs = Arrays.stream(literal.getReferences()).filter(MniTypeReference.class::isInstance).toList();
                assertEquals(literal.getText(), 1, refs.size());
                var target = refs.getFirst().resolve();
                assertInstanceOf(target, McfppNamedElement.class);
                assertEquals("Widget", ((McfppNamedElement) target).getName());
                references++;
            } else if (context == null) assertFalse(Arrays.stream(literal.getReferences()).anyMatch(MniTypeReference.class::isInstance));
        }
        assertEquals(9, references);
    }

    public void testNestedCallbackTypesAndNativeDefaults() {
        var parsed = MniTypeSyntax.parse("(list<demo:Widget>, ref NBTRef<Widget>, const int)->void", false, true, false);
        assertEquals(List.of(), parsed.problems());
        assertEquals(List.of("list", "demo:Widget", "NBTRef", "Widget", "int"), parsed.names().stream().map(MniTypeSyntax.Name::text).toList());
        assertEquals(List.of(), MniTypeSyntax.parse("int = 1", true, false, false).problems());
        assertFalse(MniTypeSyntax.parse("list<int, string>", false, false, false).problems().isEmpty());
        assertFalse(MniTypeSyntax.parse("int!", false, false, false).problems().isEmpty());
    }

    public void testCompletionInsideGenericAndAfterNamespace() {
        myFixture.configureByText("Provider.java", """
                import top.mcfpp.annotations.*;
                class Provider { @MNIFunction(normalParams="list<<caret>>", genericType="E") void run() {} }
                """);
        myFixture.completeBasic();
        assertContainsElements(myFixture.getLookupElementStrings(), "int", "E", "demo:Widget");
        myFixture.configureByText("Provider.java", """
                import top.mcfpp.annotations.*;
                class Provider { @MNIOperator(operator="+", paramType="demo:<caret>", returnType="int") void run() {} }
                """);
        myFixture.completeBasic();
        assertContainsElements(myFixture.getLookupElementStrings(), "demo:Widget", "demo:Box");
        var item = Arrays.stream(myFixture.getLookupElements()).filter(it -> it.getLookupString().equals("demo:Widget")).findFirst().orElseThrow();
        myFixture.getLookup().setCurrentItem(item);
        myFixture.finishLookup('\n');
        assertTrue(myFixture.getFile().getText().contains("paramType=\"demo:Widget\""));
        assertFalse(myFixture.getFile().getText().contains("import demo"));
    }

    public void testUnknownTypesSyntaxAndArityAreHighlighted() {
        myFixture.configureByText("Provider.java", """
                import top.mcfpp.annotations.*;
                class Provider {
                    @MNIFunction(normalParams={"Missing", "list<int", "void", "Box<int, bool>"}, returnType="int") void run() {}
                    @MNIOperator(operator="+", paramType="", returnType="void") void unary() {}
                }
                """);
        var errors = myFixture.doHighlighting(HighlightSeverity.ERROR).stream().map(it -> it.getDescription()).filter(Objects::nonNull).toList();
        assertTrue(errors.toString(), errors.stream().anyMatch(it -> it.contains("Unknown MCFPP type 'Missing'")));
        assertTrue(errors.toString(), errors.stream().anyMatch(it -> it.contains("Invalid MCFPP type")));
        assertTrue(errors.toString(), errors.stream().anyMatch(it -> it.contains("void is not")));
        assertTrue(errors.toString(), errors.stream().anyMatch(it -> it.contains("expects 1 type/readonly arguments, found 2")));
        assertFalse(errors.toString(), errors.stream().anyMatch(it -> it.contains("Missing MCFPP type")));
    }

    public void testRenameUpdatesAnnotationReferencesAndPreservesDefaultsAndIdentifiers() {
        var java = myFixture.addFileToProject("Provider.java", """
                import top.mcfpp.annotations.*;
                class Provider {
                    @MNIFunction(normalParams="list<demo:Widget> = Widget", returnType="Widget", identifier="Widget") void run() {}
                    @MNIMacro(params=@MacroParam(name="Widget", type="Widget"), returnType="Widget") void macro() {}
                    @MNIOperator(operator="+", paramType="Widget", returnType="Widget") void op() {}
                }
                """);
        var declaration = myFixture.findFileInTempDir("src/main/mcfpp/types.mcfpp");
        var source = PsiManager.getInstance(getProject()).findFile(declaration);
        var target = PsiTreeUtil.findChildrenOfType(source, McfppNamedElement.class).stream()
                .filter(it -> "Widget".equals(it.getName())).findFirst().orElseThrow();
        assertTrue(ReferencesSearch.search(target).findAll().stream().anyMatch(ref -> ref.getElement().getContainingFile().equals(java)));
        new RenameProcessor(getProject(), target, "Gadget", false, false).run();
        String text = java.getText().replaceAll("=\\s*\"", "=\"");
        assertTrue(text, text.contains("list<demo:Gadget> = Widget"));
        assertTrue(text, text.contains("returnType=\"Gadget\""));
        assertTrue(text, text.contains("name=\"Widget\", type=\"Gadget\""));
        assertTrue(text, text.contains("identifier=\"Widget\""));
        assertTrue(text, text.contains("paramType=\"Gadget\""));
    }

    public void testGenericParameterResolvesOnlyWithinItsMethod() {
        var file = myFixture.configureByText("Provider.java", """
                import top.mcfpp.annotations.*;
                class Provider {
                    @MNIFunction(normalParams="list<E>", returnType="E", genericType="E") void generic() {}
                    @MNIFunction(normalParams="E") void other() {}
                }
                """);
        var literals = PsiTreeUtil.findChildrenOfType(file, PsiLiteralExpression.class).stream()
                .filter(it -> "list<E>".equals(it.getValue())).toList();
        var reference = Arrays.stream(literals.getFirst().getReferences()).filter(MniTypeReference.class::isInstance).findFirst().orElseThrow();
        assertInstanceOf(reference.resolve(), MniTypeParameter.class);
        var errors = myFixture.doHighlighting(HighlightSeverity.ERROR).stream().map(it -> it.getDescription()).filter(Objects::nonNull).toList();
        assertEquals(errors.toString(), 1L, errors.stream().filter(it -> it.contains("Unknown MCFPP type 'E'")).count());
    }

    public void testGenericParameterRenameUpdatesItsDeclarationAndUses() {
        var file = myFixture.configureByText("Provider.java", """
                import top.mcfpp.annotations.*;
                class Provider {
                    @MNIFunction(normalParams="list<E>", returnType="E", genericType="E") void generic() {}
                    @MNIFunction(normalParams="E") void other() {}
                }
                """);
        var literal = PsiTreeUtil.findChildrenOfType(file, PsiLiteralExpression.class).stream()
                .filter(it -> "list<E>".equals(it.getValue())).findFirst().orElseThrow();
        var target = Arrays.stream(literal.getReferences()).filter(MniTypeReference.class::isInstance).findFirst().orElseThrow().resolve();
        new RenameProcessor(getProject(), target, "T", false, false).run();
        String text = file.getText().replaceAll("=\\s*\"", "=\"");
        assertTrue(text, text.contains("normalParams=\"list<T>\", returnType=\"T\", genericType=\"T\""));
        assertTrue(text, text.contains("normalParams=\"E\") void other"));
    }

    public void testKotlinReferencesCompletionAndRename() {
        var file = myFixture.configureByText("Provider.kt", """
                import top.mcfpp.annotations.*
                class Provider {
                    @MNIFunction(normalParams=["list<demo:Wid<caret>get>"], genericType=["E"])
                    fun run() {}
                }
                """);
        var reference = file.findReferenceAt(myFixture.getCaretOffset());
        assertNotNull(reference);
        assertInstanceOf(reference.resolve(), McfppNamedElement.class);
        assertFalse(new top.mcfpp.intellij.lang.McfppDocumentationTargetProvider().documentationTargets(file, myFixture.getCaretOffset()).isEmpty());
        new RenameProcessor(getProject(), reference.resolve(), "Gadget", false, false).run();
        assertTrue(file.getText(), file.getText().contains("list<demo:Gadget>"));
        myFixture.configureByText("Provider.kt", """
                import top.mcfpp.annotations.*
                class Provider {
                    @MNIFunction(normalParams=["list<<caret>>"], genericType=["E"])
                    fun run() {}
                }
                """);
        var host = PsiTreeUtil.getParentOfType(myFixture.getFile().findElementAt(myFixture.getCaretOffset() - 1), PsiLanguageInjectionHost.class, false);
        assertNotNull("Kotlin string host", host);
        assertNotNull("Kotlin annotation context", MniTypeContext.from(host));
        var contributors = com.intellij.codeInsight.completion.CompletionContributor.forLanguage(myFixture.getFile().getLanguage());
        assertTrue("Kotlin type contributor registered", contributors.stream().anyMatch(MniTypeCompletionContributor.class::isInstance));
        myFixture.completeBasic();
        assertContainsElements(myFixture.getLookupElementStrings(), "int", "E", "demo:Gadget");
    }

    public void testEscapedJavaTypeNamesMapBackToTheCorrectCharacters() {
        var file = myFixture.configureByText("Provider.java", """
                import top.mcfpp.annotations.*;
                class Provider { @MNIFunction(returnType="demo:Wid\\u0067et") void run() {} }
                """);
        var literal = PsiTreeUtil.findChildrenOfType(file, PsiLiteralExpression.class).iterator().next();
        var reference = Arrays.stream(literal.getReferences()).filter(MniTypeReference.class::isInstance).findFirst().orElseThrow();
        assertNotNull(reference.resolve());
        assertEquals("Wid\\u0067et", reference.getRangeInElement().substring(literal.getText()));
        new RenameProcessor(getProject(), reference.resolve(), "Gadget", false, false).run();
        assertTrue(file.getText(), file.getText().replaceAll("=\\s*\"", "=\"").contains("returnType=\"demo:Gadget\""));
    }

    public void testUnqualifiedTypeUsesTheNativeDeclarationNamespace() {
        myFixture.addFileToProject("src/main/mcfpp/binding.mcfpp", """
                namespace bound;
                data Widget {}
                func run(): Widget = sample.Provider.run;
                """);
        var file = myFixture.configureByText("Provider.java", """
                package sample;
                import top.mcfpp.annotations.*;
                public class Provider { @MNIFunction(returnType="Wid<caret>get") public static void run() {} }
                """);
        var reference = file.findReferenceAt(myFixture.getCaretOffset());
        assertNotNull(reference);
        assertNotNull(reference.resolve());
        assertEquals("bound", top.mcfpp.intellij.lang.McfppFileModels.get(reference.resolve().getContainingFile()).namespace());
    }

    public void testPairedObjectDoesNotMakeItsDataTypeAmbiguous() {
        myFixture.addFileToProject("src/main/mcfpp/paired.mcfpp", "namespace demo; data Paired<T> {} object data Paired {}");
        var file = myFixture.configureByText("Provider.java", """
                import top.mcfpp.annotations.*;
                class Provider { @MNIFunction(returnType="demo:Pair<caret>ed<int>") void run() {} }
                """);
        var reference = file.findReferenceAt(myFixture.getCaretOffset());
        assertNotNull(reference);
        assertNotNull(reference.resolve());
        assertFalse(myFixture.doHighlighting(HighlightSeverity.ERROR).stream().anyMatch(it ->
                it.getDescription() != null && (it.getDescription().contains("Ambiguous MCFPP") || it.getDescription().contains("expects"))));
    }
}
