package top.mcfpp.intellij.lang;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public final class McfppEntityEditorTest extends BasePlatformTestCase {
    @Override protected void setUp() throws Exception {
        super.setUp();
        for (var root : top.mcfpp.intellij.mni.McfppLibraryRootsProvider.mcfppSourceRoots(getProject()))
            if (root.getFileSystem() instanceof com.intellij.openapi.vfs.JarFileSystem)
                com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(getTestRootDisposable(),
                    com.intellij.openapi.vfs.JarFileSystem.getInstance().getVirtualFileForJar(root).getPath());
    }
    private Set<String> complete(String receiver) {
        myFixture.configureByText(McfppFileType.INSTANCE, """
            func main() {
                const display = entityByUUID<ItemDisplay>("0-0-0-0-1")
                %s.<caret>
            }
            """.formatted(receiver));
        LookupElement[] variants = myFixture.completeBasic();
        assertNotNull(variants);
        return Arrays.stream(variants).map(LookupElement::getLookupString).collect(Collectors.toSet());
    }
    public void testTypedLookupCompletesInheritedPropertiesAndRuntimeChecks() {
        assertContainsElements(complete("display"), "transform", "item", "itemDisplayMode", "billboard", "exists", "checkType");
    }
    public void testNestedTransformationCompletionKeepsTheReceiverType() {
        var transform = complete("display.transform");
        assertContainsElements(transform, "x", "y", "z", "scale", "leftRotation", "rightRotation");
        assertFalse(transform.contains("item"));
        assertFalse(transform.contains("ItemDisplay"));
        assertFalse(transform.contains("namespace"));
        assertContainsElements(complete("display.transform.scale"), "x", "y", "z");
        assertContainsElements(complete("display.transform.leftRotation"), "x", "y", "z", "w");
        assertContainsElements(complete("display.transform.y"), "tryRead", "set");
    }
    public void testValueCompletionDoesNotListEntityInterfacesOrUnimportedSymbols() {
        myFixture.addFileToProject("src/unrelated.mcfpp", "namespace unrelated; func unrelatedOperation() {} ");
        myFixture.configureByText(McfppFileType.INSTANCE, """
            namespace demo;
            func main() {
                const height = 4.0
                const next = <caret>
            }
            """);
        com.intellij.openapi.project.DumbService.getInstance(getProject()).waitForSmartMode();
        var variants = myFixture.completeBasic();
        assertNotNull(variants);
        var names = Arrays.stream(variants).map(LookupElement::getLookupString).collect(Collectors.toSet());
        assertContainsElements(names, "height", "UUID", "true", "false");
        for (String irrelevant : java.util.List.of("ItemDisplay", "Zombie", "namespace", "func", "unrelatedOperation"))
            assertFalse(irrelevant + " in " + names, names.contains(irrelevant));
    }
    public void testEntityInterfacesAreSuggestedInTypeArguments() {
        for (String call : java.util.List.of("entityByUUID", "summon", "target.checkType")) {
            myFixture.configureByText(McfppFileType.INSTANCE,
                    "func main(target: entity) { " + call + "<<caret>> } ");
            var variants = myFixture.completeBasic();
            assertNotNull(variants);
            var names = Arrays.stream(variants).map(LookupElement::getLookupString).collect(Collectors.toSet());
            assertContainsElements(names, "ItemDisplay", "TextDisplay", "Zombie");
            assertFalse(names.contains("target"));
            assertFalse(names.contains("namespace"));
        }
    }
    public void testBuiltinSubtypeAndInheritedMethodResolve() {
        myFixture.configureByText(McfppFileType.INSTANCE, "func main() { const display: Item<caret>Display = entityByUUID<ItemDisplay>(\"0-0-0-0-1\") }");
        var reference = myFixture.getFile().findReferenceAt(myFixture.getCaretOffset());
        assertNotNull(reference);
        var target = reference.resolve();
        assertNotNull(target);
        assertEquals("entity-builtins.mcfpp", target.getContainingFile().getName());
        myFixture.configureByText(McfppFileType.INSTANCE, """
            func main() {
                const display = entityByUUID<ItemDisplay>("0-0-0-0-1")
                display.ex<caret>ists()
            }
            """);
        assertNotNull(myFixture.getFile().findReferenceAt(myFixture.getCaretOffset()).resolve());
    }
    public void testEntityTemplateSeparatesTemplateAndReferenceMembers() {
        String template = """
            import mcfpp.minecraft.entity:*
            entity StoneDisplay: BlockDisplay {
                var initialCount: int = 10
                entity var count: int = 0
                constructor() {}
                entity func consume() {}
            }
            func main() {
                var template = StoneDisplay()
                var ref = template.summon()
                %s.<caret>
            }
            """;
        myFixture.configureByText(McfppFileType.INSTANCE, template.formatted("ref"));
        var beforeCompletion = McfppFileModels.get(myFixture.getFile());
        assertEquals("EntityRef<StoneDisplay>", McfppTypes.receiverType("ref", beforeCompletion, myFixture.getCaretOffset()));
        assertTrue(beforeCompletion.symbols().toString(), beforeCompletion.symbols().stream().anyMatch(symbol ->
                symbol.name().equals("consume") && symbol.signature().contains("entity func")));
        var referenceMembers = Arrays.stream(myFixture.completeBasic())
                .map(LookupElement::getLookupString).collect(Collectors.toSet());
        assertContainsElements(referenceMembers, "count", "consume", "exists", "checkType", "blockState", "transform");
        assertFalse(referenceMembers.toString(), referenceMembers.contains("initialCount"));
        assertFalse(referenceMembers.toString(), referenceMembers.contains("summon"));

        myFixture.configureByText(McfppFileType.INSTANCE, template.formatted("template"));
        var templateMembers = Arrays.stream(myFixture.completeBasic())
                .map(LookupElement::getLookupString).collect(Collectors.toSet());
        assertContainsElements(templateMembers, "initialCount", "summon");
        assertFalse(templateMembers.toString(), templateMembers.contains("count"));
        assertFalse(templateMembers.toString(), templateMembers.contains("consume"));

        var model = McfppFileModel.parse(template.formatted("ref"));
        int offset = model.symbols().stream().filter(it -> it.name().equals("ref")).findFirst().orElseThrow().nameOffset() + 1;
        assertEquals("EntityRef<StoneDisplay>", McfppTypes.receiverType("ref", model, offset));
    }

    public void testFactoryAndCallbackTypesArePreserved() {
        var model = McfppFileModel.parse("func main() { const id = UUID(\"0-0-0-0-1\"); const display = entityByUUID<ItemDisplay>(id) }");
        assertEquals("UUID", McfppTypes.declaredType(model.symbols().stream().filter(it -> it.name().equals("id")).findFirst().orElseThrow()));
        assertEquals("ItemDisplay", McfppTypes.declaredType(model.symbols().stream().filter(it -> it.name().equals("display")).findFirst().orElseThrow()));
        var function = McfppFileModel.parse("func tick(callback: (int) -> bool) {}").symbols().getFirst();
        assertEquals(java.util.List.of("(int) -> bool"), McfppTypes.parameterTypes(function));
    }
    public void testMethodCompletionInsertsCallAndTypeArgumentSyntax() {
        myFixture.configureByText(McfppFileType.INSTANCE, """
            func main() {
                const display = entityByUUID<ItemDisplay>("0-0-0-0-1")
                display.exi<caret>
            }
            """);
        myFixture.completeBasic();
        assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().contains("display.exists()"));
        myFixture.configureByText(McfppFileType.INSTANCE, """
            func main() {
                const display = entityByUUID<ItemDisplay>("0-0-0-0-1")
                display.checkT<caret>
            }
            """);
        myFixture.completeBasic();
        assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().contains("display.checkType<>()"));
    }
}
