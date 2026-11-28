package top.mcfpp.language;

import top.mcfpp.syntax.DocumentationCommentSyntax;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Formats portable documentation for clients that render Markdown. */
public final class DocumentationMarkdown {
    private static final Map<String, String> TITLES = Map.ofEntries(
            Map.entry("param", "Parameters"), Map.entry("return", "Returns"), Map.entry("throws", "Throws"),
            Map.entry("receiver", "Receiver"), Map.entry("property", "Properties"), Map.entry("constructor", "Constructor"),
            Map.entry("sample", "Examples"), Map.entry("see", "See also"), Map.entry("since", "Since"),
            Map.entry("deprecated", "Deprecated"), Map.entry("version", "Version"), Map.entry("author", "Author"),
            Map.entry("base", "Base"), Map.entry("context", "Context"));
    private static final Set<String> WITH_SUBJECT = Set.of("param", "throws", "property", "context");

    private DocumentationMarkdown() {}

    public static String render(String text) {
        Comment comment = parse(text);
        StringBuilder output = new StringBuilder(comment.body());
        for (Section section : comment.sections()) {
            if (!output.isEmpty()) output.append("\n\n");
            output.append("**").append(section.title() != null ? section.title() : "`@" + section.tag() + "`").append("**");
            for (Entry entry : section.entries()) {
                if (entry.markdown().isBlank() && entry.subject() == null) continue;
                output.append("\n\n");
                if (entry.subject() != null) {
                    String delimiter = "`".repeat(1 + longestBacktickRun(entry.subject()));
                    output.append("- ").append(delimiter).append(' ').append(entry.subject()).append(' ').append(delimiter);
                    if (!entry.markdown().isEmpty()) output.append("\n\n").append(entry.markdown().indent(2).stripTrailing());
                } else output.append(entry.markdown());
            }
        }
        return output.toString();
    }

    public static String parameter(String text, String name) {
        if (text == null) return null;
        Comment comment = parse(text);
        for (Section section : comment.sections()) {
            if (!section.tag().equals("param") && !section.tag().equals("context")) continue;
            for (Entry entry : section.entries()) {
                if (name.equals(entry.subject()) && !entry.markdown().isBlank()) return entry.markdown();
            }
        }
        return null;
    }

    private static int longestBacktickRun(String text) {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            run = text.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        return longest;
    }

    private static Entry entry(String tag, String value) {
        if (!WITH_SUBJECT.contains(tag) || value.isBlank()) return new Entry(null, value);
        String text = value.stripLeading();
        int end = 0;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
        return new Entry(text.substring(0, end), text.substring(end).strip());
    }

    public static Comment parse(String text) {
        List<String> body = new ArrayList<>();
        List<String> current = body;
        Map<String, List<List<String>>> tags = new LinkedHashMap<>();
        for (var line : DocumentationCommentSyntax.lines(text, false)) {
            if (line.tag() != null) {
                current = new ArrayList<>();
                tags.computeIfAbsent(line.tag(), ignored -> new ArrayList<>()).add(current);
                current.add(line.tagContent());
            } else current.add(line.text());
        }
        List<Section> sections = tags.entrySet().stream().map(tag -> new Section(tag.getKey(), TITLES.get(tag.getKey()),
                tag.getValue().stream().map(lines -> entry(tag.getKey(), String.join("\n", lines).stripTrailing())).toList())).toList();
        return new Comment(String.join("\n", body).stripTrailing(), sections);
    }

    public record Entry(String subject, String markdown) {}
    public record Section(String tag, String title, List<Entry> entries) {}
    public record Comment(String body, List<Section> sections) {}
}
