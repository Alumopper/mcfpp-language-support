package top.mcfpp.intellij.mni;

import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.psi.search.searches.DefinitionsScopedSearch;
import com.intellij.testFramework.ServiceContainerUtil;
import top.mcfpp.intellij.lang.McfppFileModels;

public final class McfppMacroIntegrationTest extends McfppJavaFixtureTestCase {
    public void testExplicitDeclarationLinksToKotlinCompatibleMacroProvider() {
        myFixture.addClass("package top.mcfpp.annotations; public @interface MNIMacro { String identifier() default \"\"; String[] normalParams() default {}; String returnType() default \"void\"; }");
        myFixture.addClass("package top.mcfpp.mni.api; public class MacroContext {}");
        myFixture.addClass("package top.mcfpp.mni.api; public interface Expr<T> {}");
        var provider = myFixture.addClass("""
            package example.mni;
            public class Example {
                @top.mcfpp.annotations.MNIMacro(normalParams={},returnType="int")
                public static top.mcfpp.mni.api.Expr<Object> answer(top.mcfpp.mni.api.MacroContext context) { return null; }
            }
            """);
        myFixture.addFileToProject("mcfpp.toml", "schema=1\nnamespace=\"demo\"\nsourcePath=\"src/main/mcfpp\"\n[mni]\nenabled=true\n");
        var declaration = myFixture.addFileToProject("src/main/mcfpp/api.mcfpp",
                "namespace demo\nfunc answer(): int = example.mni.Example.answer\n");
        var source = myFixture.addFileToProject("src/main/mcfpp/main.mcfpp",
                "namespace demo\nfunc main() { print(answer()); }");
        myFixture.configureFromExistingVirtualFile(source.getVirtualFile());
        assertEquals("demo", McfppFileModels.get(source).namespace());
        var macros = McfppMacroIndex.all(getProject());
        assertEquals(macros.toString(), 1, macros.size());
        assertEquals("answer", macros.getFirst().name());
        assertEquals("int", macros.getFirst().returnType());
        assertEquals("func answer(): int", macros.getFirst().signature());

        int offset = source.getText().indexOf("answer()");
        var identifier = source.findElementAt(offset);
        assertNotNull(identifier);
        var declaredName = declaration.findElementAt(declaration.getText().indexOf("answer()"));
        assertTrue(java.util.Arrays.stream(identifier.getReferences()).anyMatch(r -> r.resolve() == declaredName));
        assertEquals(java.util.List.of(provider.findMethodsByName("answer", false)[0]),
                McfppMniLineMarkerProvider.findJavaTargets(declaredName));
        assertEquals(java.util.List.of(provider.findMethodsByName("answer", false)[0]),
                com.intellij.psi.search.searches.DefinitionsScopedSearch.search(declaredName).findAll().stream().toList());
        assertEquals(java.util.List.of(provider.findMethodsByName("answer", false)[0]),
                DefinitionsScopedSearch.search(identifier).findAll().stream().toList());

        var snapshot = McfppMacroIndex.snapshot(getProject());
        assertEquals("int", snapshot.symbols.getFirst().typeName);
        assertEquals("demo", snapshot.symbols.getFirst().namespace);
        assertTrue(McfppMacroCompletionContributor.provides(source, offset, "answer", "answer(): int"));
        assertFalse(McfppMacroCompletionContributor.provides(source, offset, "answer", "answer(value: int): int"));
        assertFalse(McfppMacroCompletionContributor.provides(source, offset, "different", "different(): int"));

        var consumer = myFixture.addFileToProject("src/main/mcfpp/consumer.mcfpp",
                "namespace consumer\nimport demo:answer as result\nfunc run() { result(); demo:answer(); }");
        for (String call : java.util.List.of("result()", "answer()")) {
            var usage = consumer.findElementAt(consumer.getText().indexOf(call));
            assertTrue(java.util.Arrays.stream(usage.getReferences())
                    .anyMatch(r -> r.resolve() == declaredName));
            assertEquals(java.util.List.of(provider.findMethodsByName("answer", false)[0]),
                    DefinitionsScopedSearch.search(usage).findAll().stream().toList());
        }
        assertEquals("result", McfppMacroIndex.visibleName(consumer, McfppMacroIndex.all(getProject()).getFirst(),
                consumer.getText().indexOf("result()")));
        assertTrue(McfppMacroCompletionContributor.provides(consumer, consumer.getText().indexOf("result()"),
                "result", "result(): int"));

        var member = myFixture.addFileToProject("src/main/mcfpp/member.mcfpp", "func run() { value.answer(); }");
        assertFalse(McfppMacroCompletionContributor.provides(member, member.getText().indexOf("answer()"),
                "answer", "answer(): int"));
        assertNotNull(declaration);
    }

    public void testCallbackParameterModesSurviveInLiveSnapshot() {
        myFixture.addClass("package top.mcfpp.annotations; public @interface MNIMacro { String[] normalParams(); String returnType() default \"void\"; }");
        myFixture.addClass("package top.mcfpp.mni.api; public class MacroContext {}");
        myFixture.addClass("package top.mcfpp.mni.api; public interface InlineFn<T> {}");
        myFixture.addClass("""
                package example.mni;
                public class Example {
                    @top.mcfpp.annotations.MNIMacro(normalParams={"(const int, ref int) -> void"})
                    public static void invoke(top.mcfpp.mni.api.MacroContext ctx, top.mcfpp.mni.api.InlineFn<Object> fn) {}
                }
                """);
        myFixture.configureByText(top.mcfpp.intellij.lang.McfppFileType.INSTANCE,
                "func expand(callback: (int, int) -> void) = example.mni.Example.invoke");
        var snapshot = McfppMacroIndex.snapshot(getProject());
        var callback = snapshot.symbols.getFirst().parameters.getFirst();
        assertEquals("INLINE", callback.mode);
        assertEquals(java.util.List.of("CONST", "REF"), callback.macroCallbackParameters.stream().map(p -> p.mode).toList());
    }

    public void testReceiverGenericMacroIndexesAndResolvesMemberCalls() {
        myFixture.addClass("package top.mcfpp.annotations; public @interface MNIMacro { String[] normalParams(); String returnType() default \"void\"; }");
        myFixture.addClass("package top.mcfpp.mni.api; public class MacroContext {}");
        myFixture.addClass("package top.mcfpp.mni.api; public interface Value<T> {}");
        myFixture.addClass("package top.mcfpp.mni.api; public interface InlineFn<T> {}");
        myFixture.addClass("package top.mcfpp.mni.api; public interface Expr<T> {}");
        var provider = myFixture.addClass("""
            package example.mni;
            public class Provider {
                @top.mcfpp.annotations.MNIMacro(normalParams={"(E)->R"},returnType="map<R>")
                public static top.mcfpp.mni.api.Expr<Object> map(top.mcfpp.mni.api.MacroContext context,
                        top.mcfpp.mni.api.Value<Object> receiver, top.mcfpp.mni.api.InlineFn<Object> callback) { return null; }
            }
            """);
        myFixture.addFileToProject("mcfpp.toml", "schema=1\nnamespace=\"demo\"\nsourcePath=\"src/main/mcfpp\"\n[mni]\nenabled=true\n");
        var declaration = myFixture.addFileToProject("src/main/mcfpp/api.mcfpp",
                "namespace demo\nfunc list< E >.map<E, R>(callback: (E) -> R): map<R> = example.mni.Provider.map");
        var consumer = myFixture.addFileToProject("src/main/mcfpp/main.mcfpp",
                "namespace demo\nfunc main(items: list<int>) { items.map({ item -> item.toString() }); }");
        myFixture.configureFromExistingVirtualFile(consumer.getVirtualFile());

        var macro = McfppMacroIndex.all(getProject()).stream().filter(value -> value.name().equals("map")).findFirst().orElseThrow();
        assertEquals("list<E>", macro.receiverType());
        assertEquals(java.util.List.of("E", "R"), macro.typeParameters());
        assertEquals("INLINE", macro.parameters().getFirst().mode);
        var snapshot = McfppMacroIndex.snapshot(getProject()).symbols.getFirst();
        assertEquals("list<E>", snapshot.receiverType);
        assertEquals(java.util.List.of("E", "R"), snapshot.typeParameters);
        assertEquals(java.util.List.of("E"), snapshot.parameters.getFirst().macroCallbackParameters.stream().map(p -> p.typeName).toList());
        assertEquals("list<int>", McfppMacroIndex.receiverTypeAt(consumer, consumer.getText().indexOf("items.map") + "items.".length()));
        assertTrue(McfppMacroCompletionContributor.provides(consumer, consumer.getText().indexOf("items.map") + "items.map".length(),
                "map", "map<R>(callback: (int)->R): map<R>"));

        var declaredName = declaration.findElementAt(declaration.getText().indexOf("map<E"));
        var usage = consumer.findElementAt(consumer.getText().indexOf("map({"));
        assertNotNull(usage);
        assertTrue(java.util.Arrays.stream(usage.getReferences()).anyMatch(reference -> reference.resolve() == declaredName));
        assertEquals(java.util.List.of(provider.findMethodsByName("map", false)[0]),
                com.intellij.psi.search.searches.DefinitionsScopedSearch.search(declaredName).findAll().stream().toList());
        assertEquals(java.util.List.of(provider.findMethodsByName("map", false)[0]),
                DefinitionsScopedSearch.search(usage).findAll().stream().toList());
        var target = declaration.findElementAt(declaration.getText().lastIndexOf("map"));
        assertTrue(java.util.Arrays.stream(target.getReferences())
                .anyMatch(reference -> reference.resolve() == provider.findMethodsByName("map", false)[0]));
    }

    public void testImplementationSearchUsesRemoteKotlinTargetsForUnresolvedReceiverCalls() {
        var provider = myFixture.addFileToProject("src/main/kotlin/example/mni/AcceptanceCollections.kt", """
                package example.mni
                object AcceptanceCollections {
                    fun mapList() {}
                    fun foldList() {}
                }
                """);
        var consumer = myFixture.addFileToProject("src/main/mcfpp/main.mcfpp", """
                namespace demo
                func main(items: list<int>, lookup: map<int>) {
                    items.map({ value -> value + 1 });
                    items.fold(0, { total, value -> total + value });
                }
                """);
        myFixture.configureFromExistingVirtualFile(consumer.getVirtualFile());
        var requested = new java.util.ArrayList<String>();
        // Light PSI fixtures keep the LSP process disabled. Supply its resolved Kotlin targets here.
        ServiceContainerUtil.registerOrReplaceServiceInstance(getProject(), McfppRemoteImplementationLookup.class, call -> {
            assertEquals(consumer, call.getContainingFile());
            requested.add(call.getText());
            int offset = provider.getText().indexOf(call.getText() + "List");
            assertTrue(offset >= 0);
            return java.util.List.of(provider.findElementAt(offset));
        }, getTestRootDisposable());
        assertSame(top.mcfpp.intellij.lang.McfppTokenTypes.TYPE_KEYWORD,
                consumer.findElementAt(consumer.getText().indexOf("map<int>")).getNode().getElementType());
        for (String name : java.util.List.of("map", "fold")) {
            int offset = consumer.getText().indexOf("items." + name) + "items.".length();
            var call = consumer.findElementAt(offset);
            myFixture.getEditor().getCaretModel().moveToOffset(offset + 1);
            assertSame(call, TargetElementUtil.findTargetElement(myFixture.getEditor(),
                    TargetElementUtil.getInstance().getDefinitionSearchFlags()));
            assertFalse(TargetElementUtil.getInstance().includeSelfInGotoImplementation(call));
            var targets = DefinitionsScopedSearch.search(call).findAll().stream().toList();
            assertEquals(name + " " + targets, 1, targets.size());
            var target = targets.getFirst();
            assertEquals(provider.getVirtualFile(), target.getContainingFile().getVirtualFile());
            assertEquals(name + "List", provider.findElementAt(target.getTextOffset()).getText());
        }
        assertEquals(java.util.List.of("map", "fold"), requested);
    }
}
