package top.mcfpp.language;

import java.util.ArrayDeque;

/** Identifies code in the expression containing the end of a source prefix. */
public final class McfppCodeMask {
    private final int[] regions;
    private final int start;

    private McfppCodeMask(int[] regions, int start) {
        this.regions = regions;
        this.start = start;
    }

    public int start() { return start; }

    public boolean includes(int offset) { return regions[offset] == start + 1; }

    public static McfppCodeMask scan(CharSequence source, int end) {
        int[] regions = new int[end];
        // The offset just after an interpolation opener uniquely identifies its code region.
        int start = 0;
        var strings = new ArrayDeque<QuotedString>();
        for (int i = 0; i < end;) {
            char c = source.charAt(i);
            char next = i + 1 < end ? source.charAt(i + 1) : '\0';
            var string = strings.peek();
            if (string != null && string.braceDepth == 0) {
                if (c == '\\' && string.width == 1) {
                    i += 2;
                } else if (c == string.quote && (string.width == 1 || tripleQuote(source, i, end))) {
                    strings.pop();
                    i += string.width;
                    if (string.width == 3) {
                        while (i < end && source.charAt(i) == '"') i++;
                    }
                } else if (c == '$' && next == '{' && string.quote == '"') {
                    string.braceDepth = 1;
                    i += 2;
                    start = i;
                } else if ((c == '\n' || c == '\r') && string.width == 1) {
                    strings.pop();
                } else {
                    i++;
                }
            } else if (c == '"' || c == '\'') {
                int width = tripleQuote(source, i, end) ? 3 : 1;
                strings.push(new QuotedString(c, width, start));
                i += width;
            } else if (c == '/' && next == '/') {
                while (i < end && source.charAt(i) != '\n' && source.charAt(i) != '\r') i++;
            } else if (c == '/' && next == '*') {
                i += 2;
                while (i + 1 < end && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) i++;
                i = Math.min(end, i + 2);
            } else {
                if (string != null) {
                    if (c == '{') string.braceDepth++;
                    if (c == '}' && --string.braceDepth == 0) {
                        start = string.parentStart;
                        i++;
                        continue;
                    }
                }
                regions[i++] = start + 1;
            }
        }
        return new McfppCodeMask(regions, start);
    }

    private static boolean tripleQuote(CharSequence source, int offset, int end) {
        return offset + 2 < end && source.charAt(offset) == '"' &&
                source.charAt(offset + 1) == '"' && source.charAt(offset + 2) == '"';
    }

    private static final class QuotedString {
        final char quote;
        final int width;
        final int parentStart;
        int braceDepth;

        QuotedString(char quote, int width, int parentStart) {
            this.quote = quote;
            this.width = width;
            this.parentStart = parentStart;
        }
    }
}
