package top.mcfpp.intellij.lang;

import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import top.mcfpp.language.McfppDocumentationData;

public final class McfppDocumentationEditorTest extends BasePlatformTestCase {
    public void testCompletesAndInsertsTagWithoutDuplicatingItsMarker() {
        myFixture.configureByText(McfppFileType.INSTANCE, "/// @ret<caret>\nfunc result(): int { return 1 }");
        myFixture.completeBasic();
        if (myFixture.getLookup() != null) myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
        assertTrue(myFixture.getEditor().getDocument().getText().startsWith("/// @return\n"));
    }

    public void testHighlightsMetadataOnlyOutsideFences() {
        String text = """
                /// A result.
                /// ```mcfpp
                /// @param example
                /// ```
                /// @return 结果
                /// @custom other
                func result(): int { return 1 }
                """;
        myFixture.configureByText(McfppFileType.INSTANCE, text);
        var tags = myFixture.doHighlighting().stream()
                .filter(info -> info.forcedTextAttributesKey == McfppSyntaxHighlighter.DOC_TAG)
                .map(info -> text.substring(info.startOffset, info.endOffset)).toList();
        assertEquals(java.util.List.of("@return"), tags);
    }

    public void testQuickDocumentationUsesIdeSectionsAndKeepsMarkdownInsideThem() {
        String html = McfppQuickDocumentation.html(getProject(), new McfppDocumentationData("total(value: int): int",
                "Intro.\n@param value A **number**.\n@return 结果\n@deprecated Use the new API.\n@see [Guide](https://example.com)",
                "fixture", "Task", null, null));
        assertTrue(html, html.contains("class=\"definition\""));
        assertTrue(html, html.contains("class=\"content\""));
        assertTrue(html, html.contains("class=\"sections\""));
        assertTrue(html, html.contains("<p>Returns</p></td>"));
        assertTrue(html.contains("<p>结果</p>"));
        assertTrue(html, html.contains("<p>Deprecated</p></td>"));
        assertTrue(html, html.contains("<code>value</code> — A <strong>number</strong>."));
        assertTrue(html, html.contains("href=\"https://example.com\""));
    }
}
