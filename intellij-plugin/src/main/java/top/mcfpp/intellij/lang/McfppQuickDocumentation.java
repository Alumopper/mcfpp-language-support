package top.mcfpp.intellij.lang;

import com.intellij.lang.documentation.DocumentationMarkup;
import com.intellij.openapi.editor.richcopy.HtmlSyntaxInfoUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.HtmlChunk;
import com.intellij.platform.backend.documentation.DocumentationResult;
import org.commonmark.node.Paragraph;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import top.mcfpp.language.DocumentationMarkdown;
import top.mcfpp.language.McfppDocumentationData;

import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class McfppQuickDocumentation {
    private static final Parser MARKDOWN = Parser.builder().build();
    private static final HtmlRenderer HTML = HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).build();

    private McfppQuickDocumentation() {}

    static DocumentationResult.Documentation render(Project project, McfppDocumentationData data) {
        return DocumentationResult.documentation(html(project, data));
    }

    static String html(Project project, McfppDocumentationData data) {
        StringBuilder signature = new StringBuilder();
        HtmlSyntaxInfoUtil.appendHighlightedByLexerAndEncodedAsHtmlCodeSnippet(
                signature, project, McfppLanguage.INSTANCE, data.signature(), 1.0f);
        String owner = Stream.of(data.namespace(), data.container())
                .filter(value -> value != null && !value.isBlank()).collect(Collectors.joining(" · "));
        var definition = DocumentationMarkup.PRE_ELEMENT;
        if (!owner.isEmpty()) definition = definition.child(DocumentationMarkup.GRAYED_ELEMENT.addText(owner)).child(HtmlChunk.br());
        StringBuilder result = new StringBuilder();
        DocumentationMarkup.DEFINITION_ELEMENT.child(definition.addRaw(signature.toString())).appendTo(result);

        var comment = DocumentationMarkdown.parse(data.markdown() == null ? "" : data.markdown());
        if (!comment.body().isBlank()) DocumentationMarkup.CONTENT_ELEMENT.addRaw(markdown(comment.body())).appendTo(result);

        var sections = new ArrayList<HtmlChunk>();
        for (var section : comment.sections()) {
            var value = DocumentationMarkup.SECTION_CONTENT_CELL;
            for (var entry : section.entries()) {
                if (entry.subject() != null) {
                    var label = HtmlChunk.text(entry.subject()).code();
                    var description = markdownParagraph(entry.markdown());
                    var paragraph = HtmlChunk.p().child(label);
                    if (description != null && !description.isBlank()) paragraph = paragraph.addText(" — ").addRaw(description);
                    value = value.child(paragraph);
                    if (description == null) value = value.addRaw(markdown(entry.markdown()));
                } else if (!entry.markdown().isBlank()) value = value.addRaw(markdown(entry.markdown()));
            }
            String title = section.title() == null ? "@" + section.tag() : section.title();
            sections.add(row(title, value));
        }
        addDetail(sections, "Receiver type", data.receiver());
        addDetail(sections, "Value", data.detail());
        if (!sections.isEmpty()) DocumentationMarkup.SECTIONS_TABLE.children(sections).appendTo(result);
        return result.toString();
    }

    static String markdown(String value) {
        return HTML.render(MARKDOWN.parse(value));
    }

    private static String markdownParagraph(String value) {
        var first = MARKDOWN.parse(value).getFirstChild();
        if (first == null) return "";
        if (!(first instanceof Paragraph) || first.getNext() != null) return null;
        String rendered = HTML.render(first).strip();
        return rendered.substring("<p>".length(), rendered.length() - "</p>".length());
    }

    private static HtmlChunk row(String title, HtmlChunk value) {
        return HtmlChunk.tag("tr").children(DocumentationMarkup.SECTION_HEADER_CELL.child(HtmlChunk.p().addText(title)), value);
    }

    private static void addDetail(ArrayList<HtmlChunk> rows, String title, String value) {
        if (value != null && !value.isBlank()) {
            rows.add(row(title, DocumentationMarkup.SECTION_CONTENT_CELL.child(HtmlChunk.text(value).code())));
        }
    }
}
