package top.mcfpp.intellij.datapack;

import com.google.gson.JsonParser;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.json.JsonFileType;
import com.intellij.psi.PsiFile;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public final class MinecraftDatapackJsonIntegrationTest extends BasePlatformTestCase {
    private static final List<String> SCHEMAS = List.of(
            "pack", "tag", "advancement", "recipe", "predicate", "loot-table", "item-modifier", "registry"
    );

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        Path testSandbox = Path.of(System.getProperty("user.dir"), ".intellijPlatform").toAbsolutePath();
        VfsRootAccess.allowRootAccess(getTestRootDisposable(), testSandbox.toString());
    }

    public void testBundledSchemasAreValidJson() {
        for (String schema : SCHEMAS) {
            try (var stream = getClass().getResourceAsStream("/schemas/minecraft/" + schema + ".schema.json")) {
                assertNotNull("Missing schema " + schema, stream);
                JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            } catch (Exception exception) {
                throw new AssertionError("Invalid schema " + schema, exception);
            }
        }
    }

    public void testRecipeSchemaProvidesKeyAndRecipeTypeCompletion() {
        PsiFile file = myFixture.addFileToProject("data/demo/recipe/test.json", "{  }");
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        assertEquals(JsonFileType.INSTANCE, myFixture.getFile().getFileType());
        myFixture.getEditor().getCaretModel().moveToOffset(2);

        LookupElement[] keys = myFixture.completeBasic();

        assertNotNull(keys);
        List<String> lookupStrings = Arrays.stream(keys)
                .map(item -> item.getLookupString().replace("\"", ""))
                .toList();
        assertTrue("Missing type in " + lookupStrings, lookupStrings.contains("type"));
        assertTrue("Missing ingredients in " + lookupStrings, lookupStrings.contains("ingredients"));
        assertTrue("Missing result in " + lookupStrings, lookupStrings.contains("result"));
    }

    public void testPackMcmetaUsesJsonEditorAndSchema() {
        PsiFile file = myFixture.addFileToProject("pack.mcmeta", "{  }");
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        assertEquals(JsonFileType.INSTANCE, myFixture.getFile().getFileType());
        myFixture.getEditor().getCaretModel().moveToOffset(2);

        LookupElement[] keys = myFixture.completeBasic();

        assertNotNull(keys);
        assertTrue(Arrays.stream(keys)
                .map(item -> item.getLookupString().replace("\"", ""))
                .anyMatch("pack"::equals));
    }
}
