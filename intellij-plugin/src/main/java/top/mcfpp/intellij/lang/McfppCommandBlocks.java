package top.mcfpp.intellij.lang;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Lexical line classification for the compiler's `/{ ... }` command blocks.
 *
 * <p>Inside a command block each nonempty line is a vanilla command unless it starts
 * with `/`, which introduces an ordinary MCFPP statement. A line consisting of `}`
 * alone closes the block; `/{}` is the empty form. This helper mirrors those rules
 * without requiring the native parser to understand command grammar.</p>
 */
public final class McfppCommandBlocks {
    public enum Kind {
        /** Ordinary MCFPP source, including `/...` command statements. */
        ORDINARY,
        /** The `/{` line that opens a block. */
        OPEN,
        /** A command line inside a block. */
        COMMAND,
        /** The `}` line that closes a block. */
        CLOSE
    }

    /** One source line with its first non-whitespace offset and block classification. */
    public record Line(int start, int end, int contentStart, String content, Kind kind, boolean insideBlock) {
    }

    private McfppCommandBlocks() {
    }

    public static @NotNull List<Line> lines(@NotNull CharSequence source) {
        List<Line> result = new ArrayList<>();
        boolean inside = false;
        // `/if ... {` inside a command block switches the compiler lexer to ordinary
        // MCFPP mode. Track that nested block so its `}` is not mistaken for the
        // command-block terminator.
        int ordinaryDepth = 0;
        int start = 0;
        while (start <= source.length()) {
            int end = start;
            while (end < source.length() && source.charAt(end) != '\n' && source.charAt(end) != '\r') end++;
            int content = start;
            while (content < end && (source.charAt(content) == ' ' || source.charAt(content) == '\t' ||
                    source.charAt(content) == '\f')) content++;
            String text = source.subSequence(content, end).toString();
            boolean insideBlock = inside;
            Kind kind;
            if (inside) {
                if (ordinaryDepth > 0) {
                    kind = Kind.ORDINARY;
                    ordinaryDepth = Math.max(0, ordinaryDepth + braceDelta(text));
                } else if (isClose(text)) {
                    kind = Kind.CLOSE;
                    inside = false;
                } else if (text.startsWith("/")) {
                    kind = Kind.ORDINARY;
                    ordinaryDepth = Math.max(0, braceDelta(text));
                } else {
                    kind = Kind.COMMAND;
                }
            } else if (text.startsWith("/{")) {
                kind = Kind.OPEN;
                // `/{}` opens and closes on the same line.
                inside = !text.strip().equals("/{}");
            } else {
                kind = Kind.ORDINARY;
            }
            result.add(new Line(start, end, content, text, kind, insideBlock));

            int next = end;
            if (next < source.length() && source.charAt(next) == '\r') next++;
            if (next < source.length() && source.charAt(next) == '\n') next++;
            if (next == start && start == source.length()) break;
            start = next;
            if (start > source.length()) break;
        }
        return List.copyOf(result);
    }

    /** The line containing {@code offset}; the final empty line is returned at the file end. */
    public static @NotNull Line lineAt(@NotNull CharSequence source, int offset) {
        int bounded = Math.max(0, Math.min(offset, source.length()));
        List<Line> lines = lines(source);
        for (Line line : lines) {
            if (bounded <= line.end()) return line;
        }
        return lines.isEmpty()
                ? new Line(0, 0, 0, "", Kind.ORDINARY, false)
                : lines.getLast();
    }

    private static int braceDelta(String text) {
        int delta = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (value == '\\') escaped = true;
                else if (value == quote) quote = 0;
                continue;
            }
            if (value == '/' && index + 1 < text.length() && text.charAt(index + 1) == '/') break;
            if (value == '\'' || value == '"') {
                quote = value;
                continue;
            }
            if (value == '{') delta++;
            else if (value == '}') delta--;
        }
        return delta;
    }

    private static boolean isClose(String text) {
        return text.strip().equals("}");
    }
}
