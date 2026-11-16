package top.mcfpp.intellij.lang;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public final class McfppQuoteTypingTest extends BasePlatformTestCase {
    public void testDoubleQuotesPairAndClosingQuoteIsSkipped() {
        myFixture.configureByText(McfppFileType.INSTANCE, "func main() { var value = <caret> }");
        myFixture.type('"');
        myFixture.checkResult("func main() { var value = \"<caret>\" }");
        myFixture.type("stone\"");
        myFixture.checkResult("func main() { var value = \"stone\"<caret> }");
    }

    public void testSingleQuotesPairInNbt() {
        myFixture.configureByText(McfppFileType.INSTANCE, "func main() { var value = {id: <caret>} }");
        myFixture.type('\'');
        myFixture.checkResult("func main() { var value = {id: '<caret>'} }");
    }

    public void testUnfinishedStringDoesNotDisablePairingOnTheNextLine() {
        myFixture.configureByText(McfppFileType.INSTANCE, "var first = \"unfinished\nvar second = <caret>");
        myFixture.type('"');
        myFixture.checkResult("var first = \"unfinished\nvar second = \"<caret>\"");
    }

    public void testEntityTemplateEditorRenderingAndTyping() throws Exception {
        myFixture.configureByText(McfppFileType.INSTANCE, """
                entity StoneDisplay: BlockDisplay {
                    override func summon(d: nbt): EntityRef<StoneDisplay> {
                        return super.summon({block_state: {Name: <caret>}}.merge(d))
                    }
                    entity var count: int = 0
                }
                """);
        myFixture.type('"');
        myFixture.type("minecraft:stone\"");
        com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().contains("Name: \"minecraft:stone\"}"));
        var editor = myFixture.getEditor();
        var highlighter = ((com.intellij.openapi.editor.ex.EditorEx) editor).getHighlighter();
        var keyword = highlighter.createIterator(0);
        assertSame(McfppTokenTypes.DECLARATION_KEYWORD, keyword.getTokenType());
        var component = editor.getContentComponent();
        component.setSize(1000, 280);
        var preview = new java.awt.image.BufferedImage(1000, 280, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = preview.createGraphics();
        try { component.paint(graphics); } finally { graphics.dispose(); }
        var target = java.nio.file.Path.of("build", "entity-editor-preview.png");
        java.nio.file.Files.createDirectories(target.getParent());
        javax.imageio.ImageIO.write(preview, "png", target.toFile());
    }
}
