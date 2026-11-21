package top.mcfpp.intellij.lang;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.Arrays;
import java.util.stream.Collectors;

public final class McfppNbtEditorTest extends BasePlatformTestCase {
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        var plugin = com.intellij.ide.plugins.PluginManagerCore.getPlugin(
                com.intellij.openapi.extensions.PluginId.getId("top.mcfpp.language"));
        if (plugin != null) {
            com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(
                    getTestRootDisposable(), plugin.getPluginPath().toString());
        }
    }

    public void testMergeResolvesOnNbtVariablesAndCompoundLiterals() {
        for (String receiver : java.util.List.of("d", "{ item: { id: \"minecraft:stone\", count: 1 } }",
                "{\n item: { id: \"minecraft:stone\", count: 1 }, label: \"} (\"\n}",
                "d.merge({})")) {
            myFixture.configureByText(McfppFileType.INSTANCE,
                    "func mergeData(d: nbt): nbt { return " + receiver + ".mer<caret>ge(d) }");
            var usage = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
            var targets = McfppSymbolResolver.resolve(usage);
            assertEquals(receiver + ": " + targets, 1, targets.size());
            var signatures = McfppSymbolResolver.findCallables(usage);
            assertEquals(1, signatures.size());
            assertEquals("func merge(d: nbt): nbt", signatures.getFirst().signature());
        }
    }

    public void testMergeCompletionOnVariablesLiteralsAndInferredResults() {
        for (String receiver : java.util.List.of("d", "{ item: { id: \"minecraft:stone\", count: 1 } }", "merged")) {
            myFixture.configureByText(McfppFileType.INSTANCE,
                    "func mergeData(d: nbt) { var merged = d.merge({})\n" + receiver + ".<caret> }");
            LookupElement[] variants = myFixture.completeBasic();
            assertNotNull(receiver, variants);
            var names = Arrays.stream(variants).map(LookupElement::getLookupString).collect(Collectors.toSet());
            assertContainsElements(names, "merge");
        }
    }

    public void testMergeCallInTheEditor() throws Exception {
        myFixture.configureByText(McfppFileType.INSTANCE, """
                entity StoneDisplay: BlockDisplay {
                    override func summon(d: nbt): EntityRef<BlockDisplay> {
                        return super.summon({ item: { id: "minecraft:stone", count: 1 } }.mer<caret>ge(d))
                    }
                }
                """);
        var reference = myFixture.getFile().findReferenceAt(myFixture.getCaretOffset());
        assertNotNull(reference);
        assertNotNull(reference.resolve());
        var component = myFixture.getEditor().getContentComponent();
        component.setSize(1100, 220);
        var preview = new java.awt.image.BufferedImage(1100, 220, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = preview.createGraphics();
        try { component.paint(graphics); } finally { graphics.dispose(); }
        var target = java.nio.file.Path.of("build", "nbt-merge-editor-preview.png");
        java.nio.file.Files.createDirectories(target.getParent());
        javax.imageio.ImageIO.write(preview, "png", target.toFile());
    }
}
