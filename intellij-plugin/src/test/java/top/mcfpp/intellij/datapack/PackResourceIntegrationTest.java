package top.mcfpp.intellij.datapack;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.*;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import top.mcfpp.intellij.lang.McfppNamedElement;
import top.mcfpp.intellij.mni.McfppJavaFixtureTestCase;
import java.util.Arrays;

public final class PackResourceIntegrationTest extends McfppJavaFixtureTestCase {
    private PsiFile config;
    @Override protected void setUp() throws Exception {
        super.setUp();
        config = myFixture.addFileToProject("mcfpp.toml", """
                schema = 1
                name = "demo"
                namespace = "demo"
                sourcePath = "src/main/mcfpp"
                dataPath = "src/main/resources/data"
                assetsPath = "src/main/resources/assets"
                [entrypoints]
                "demo:on_use" = "demo:handleUse"
                """);
    }
    private PsiFile raw() { return myFixture.addFileToProject("src/main/resources/data/demo/function/raw.mcfunction", "say raw\n"); }
    private PsiFile callback() { return myFixture.addFileToProject("src/main/mcfpp/main.mcfpp", "func handleUse() {}\n"); }
    private PsiReference reference(PsiFile file, String text) {
        var ref = file.findReferenceAt(file.getText().indexOf(text) + 2); assertNotNull(file.getName() + ": " + text, ref); return ref;
    }
    public void testCommandsAndJsonNavigateAcrossLanguagesAndResourceKinds() {
        var function = raw(); var callback = callback();
        var command = myFixture.addFileToProject("src/main/resources/data/demo/function/caller.mcfunction", "execute as @s run function demo:raw\nfunction demo:on_use\n");
        var index = PackResourceIndex.forFile(command); assertNotNull("No project config for " + command.getVirtualFile(), index);
        assertNotNull(index.configuration + " / " + command.getVirtualFile(), index.owner(command));
        assertFalse(index.configuration.toString(), index.definitions.isEmpty());
        assertEquals(function, reference(command, "demo:raw").resolve());
        var target = reference(command, "demo:on_use").resolve();
        assertInstanceOf(target, McfppNamedElement.class); assertEquals(callback, target.getContainingFile());
        var advancement = myFixture.addFileToProject("src/main/resources/data/demo/advancement/use.json", "{\"rewards\":{\"function\":\"demo:on_use\"}}");
        assertEquals(target, reference(advancement, "demo:on_use").resolve());
        var model = myFixture.addFileToProject("src/main/resources/assets/demo/models/item/wand.json", "{\"parent\":\"minecraft:item/generated\"}");
        var item = myFixture.addFileToProject("src/main/resources/assets/demo/items/wand.json", "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"demo:item/wand\"}}");
        assertEquals(model, reference(item, "demo:item/wand").resolve());
        var mcfpp = myFixture.addFileToProject("src/main/mcfpp/caller.mcfpp", "func caller() {\n    /function demo:raw\n}\n");
        assertEquals(function, reference(mcfpp, "demo:raw").resolve());
    }
    public void testResourceCompletionReplacesWholeNamespacedId() {
        raw(); callback();
        var file = myFixture.addFileToProject("src/main/resources/data/demo/function/caller.mcfunction", "function demo:r\n");
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(file.getText().indexOf("demo:r") + 6);
        var items = myFixture.completeBasic();
        if (items != null) {
            var item = Arrays.stream(items).filter(i -> i.getLookupString().equals("demo:raw")).findFirst().orElseThrow();
            myFixture.getLookup().setCurrentItem(item); myFixture.finishLookup('\n');
        }
        assertEquals("function demo:raw\n", myFixture.getFile().getText());
    }
    public void testFileRenameUpdatesRawMcfppAndJsonCallers() {
        var function = raw();
        var command = myFixture.addFileToProject("src/main/resources/data/demo/function/caller.mcfunction", "function demo:raw\n");
        var tag = myFixture.addFileToProject("src/main/resources/data/demo/tags/function/actions.json", "{\"values\":[\"demo:raw\"]}");
        var source = myFixture.addFileToProject("src/main/mcfpp/caller.mcfpp", "func caller() {\n/function demo:raw\n}\n");
        assertEquals(3, ReferencesSearch.search(function).findAll().size());
        myFixture.renameElement(function, "renamed.mcfunction");
        assertTrue(command.getText(), command.getText().contains("demo:renamed"));
        assertTrue(tag.getText(), tag.getText().contains("demo:renamed"));
        assertTrue(source.getText(), source.getText().contains("demo:renamed"));
    }
    public void testCallbackRenameChangesConfigTargetAndPreservesPublicId() {
        var function = callback();
        var command = myFixture.addFileToProject("src/main/resources/data/demo/function/caller.mcfunction", "function demo:on_use\n");
        var target = PsiTreeUtil.getParentOfType(function.findElementAt(function.getText().indexOf("handleUse")), McfppNamedElement.class, false);
        assertNotNull(target);
        myFixture.renameElement(target, "handleWand");
        assertTrue(config.getText(), config.getText().contains("\"demo:on_use\" = \"demo:handleWand\""));
        assertEquals("function demo:on_use\n", command.getText());
        assertNotNull(reference(command, "demo:on_use").resolve());
    }
    public void testMissingDiagnosticsRespectTypeOptionalTagAndUnsavedChanges() {
        raw();
        var file = myFixture.addFileToProject("src/main/resources/data/demo/function/caller.mcfunction", "function demo:missing\nsay function demo:ignored\n");
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        assertTrue(myFixture.doHighlighting().stream().anyMatch(info -> "Missing function resource: demo:missing".equals(info.getDescription())));
        var index = PackResourceIndex.forFile(file);
        assertTrue(index.missing(index.references(file).getFirst()));
        var definition = myFixture.addFileToProject("src/main/resources/data/demo/function/missing.mcfunction", "say created\n");
        assertEquals(definition, reference(file, "demo:missing").resolve());
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            var document = PsiDocumentManager.getInstance(getProject()).getDocument(file);
            document.setText("function demo:raw\n"); PsiDocumentManager.getInstance(getProject()).commitDocument(document);
        });
        assertFalse(PackResourceIndex.forFile(file).missing(PackResourceIndex.forFile(file).references(file).getFirst()));
    }
    public void testResourceSchemasCompleteModelFields() {
        var file = myFixture.addFileToProject("src/main/resources/assets/demo/models/item/wand.json", "{  }");
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile()); myFixture.getEditor().getCaretModel().moveToOffset(2);
        var items = myFixture.completeBasic(); assertNotNull(items);
        assertTrue(Arrays.stream(items).anyMatch(item -> item.getLookupString().replace("\"", "").equals("textures")));
    }
    public void testModelCompletionAndTextureRenameStayInTheirResourceKind() {
        var texture = myFixture.addFileToProject("src/main/resources/assets/demo/textures/item/wand.png", "fixture");
        var model = myFixture.addFileToProject("src/main/resources/assets/demo/models/item/wand.json", "{\"textures\":{\"layer0\":\"demo:item/wand\"}}");
        myFixture.addFileToProject("src/main/resources/data/demo/function/wand.mcfunction", "say other kind\n");
        assertEquals(texture, reference(model, "demo:item/wand").resolve());
        var item = myFixture.addFileToProject("src/main/resources/assets/demo/items/wand.json", "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"demo:item/\"}}");
        myFixture.configureFromExistingVirtualFile(item.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(item.getText().indexOf("demo:item/") + 10);
        var items = myFixture.completeBasic();
        if (items != null) {
            var choice = Arrays.stream(items).filter(i -> i.getLookupString().equals("demo:item/wand")).findFirst().orElseThrow();
            myFixture.getLookup().setCurrentItem(choice); myFixture.finishLookup('\n');
        }
        assertTrue(item.getText(), item.getText().contains("\"model\":\"demo:item/wand\""));
        myFixture.renameElement(texture, "staff.png");
        assertTrue(model.getText(), model.getText().contains("demo:item/staff"));
        assertTrue(item.getText(), item.getText().contains("demo:item/wand"));
    }
    public void testConflictingGeneratedFunctionIsReported() {
        callback();
        var file = myFixture.addFileToProject("src/main/resources/data/demo/function/handle_use.mcfunction", "say collision\n");
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        assertTrue(myFixture.doHighlighting().stream().anyMatch(info -> "Resource conflicts with compiler output: demo:handle_use".equals(info.getDescription())));
    }
}
