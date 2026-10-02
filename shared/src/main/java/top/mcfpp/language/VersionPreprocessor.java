package top.mcfpp.language;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Mirrors the compiler's VersionPreprocessor while retaining source offsets for editors. */
public final class VersionPreprocessor {
    public static final String DEFAULT_VERSION = "1.21.8";
    private static final Pattern DIRECTIVE = Pattern.compile("^[ \\t]*#(if|elif|else|endif)(?=[ \\t\\r]|$)(.*)$", Pattern.DOTALL);
    private static final Pattern CONDITION = Pattern.compile("^MC[ \\t]+(==|!=|<=|>=|<|>)[ \\t]+([0-9]+(?:\\.[0-9]+)+)$");
    private static final Pattern VERSION = Pattern.compile("[0-9]+(?:\\.[0-9]+)+");

    public static final class Error extends IllegalArgumentException {
        public final int line;

        private Error(int line, String message) {
            super(message);
            this.line = line;
        }
    }

    public record Directive(int start, int end) {}
    public record InactiveRange(int start, int end) {}
    public record Result(String text, List<Directive> directives, List<InactiveRange> inactiveRanges) {}

    private static final class Branch {
        final int startLine;
        final boolean parentActive;
        boolean taken;
        boolean hasElse;
        boolean active;

        Branch(int startLine, boolean parentActive, boolean selected) {
            this.startLine = startLine;
            this.parentActive = parentActive;
            this.taken = selected;
            this.active = parentActive && selected;
        }
    }

    private enum State { CODE, SINGLE_STRING, DOUBLE_STRING, MULTILINE_STRING, BLOCK_COMMENT, DOC_COMMENT, TRIPLE_DOC_COMMENT }

    private VersionPreprocessor() {}

    public static boolean isDirective(String line) {
        return DIRECTIVE.matcher(line).matches();
    }

    /** Returns the hash offset only when completion is in code at a directive prefix. */
    public static int directivePrefixStart(String source, int offset) {
        int end = Math.max(0, Math.min(offset, source.length()));
        int start = 0;
        State state = State.CODE;
        while (start < end) {
            int newline = source.indexOf('\n', start);
            if (newline < 0 || newline >= end) break;
            state = scanState(source.substring(start, newline), state);
            start = newline + 1;
        }
        String prefix = source.substring(start, end);
        return state == State.CODE && prefix.matches("[ \\t]*#[A-Za-z]*")
                ? start + prefix.indexOf('#') : -1;
    }

    public static String process(String source, String targetVersion) {
        return preprocess(source, targetVersion).text();
    }

    public static Result preprocess(String source, String targetVersion) {
        int[] target = parseVersion(targetVersion, 1);
        ArrayDeque<Branch> branches = new ArrayDeque<>();
        List<Directive> directives = new ArrayList<>();
        List<InactiveRange> inactiveRanges = new ArrayList<>();
        StringBuilder result = new StringBuilder(source.length());
        State state = State.CODE;
        int offset = 0;
        int lineNumber = 1;
        while (offset < source.length()) {
            int end = source.indexOf('\n', offset);
            if (end < 0) end = source.length();
            String line = source.substring(offset, end);
            Matcher match = DIRECTIVE.matcher(line);
            boolean active = branches.isEmpty() || branches.peekLast().active;
            if (state == State.CODE && match.matches()) {
                String name = match.group(1);
                String argument = match.group(2).trim();
                boolean nestedInactive = name.equals("if") ? !active
                        : !branches.isEmpty() && !branches.peekLast().parentActive;
                switch (name) {
                    case "if" -> branches.addLast(new Branch(lineNumber, active, evaluate(argument, target, lineNumber)));
                    case "elif" -> {
                        Branch branch = branches.peekLast();
                        if (branch == null) throw new Error(lineNumber, "#elif without #if");
                        if (branch.hasElse) throw new Error(lineNumber, "#elif after #else");
                        boolean selected = evaluate(argument, target, lineNumber);
                        branch.active = branch.parentActive && !branch.taken && selected;
                        branch.taken |= selected;
                    }
                    case "else" -> {
                        if (!argument.isEmpty()) throw new Error(lineNumber, "#else takes no condition");
                        Branch branch = branches.peekLast();
                        if (branch == null) throw new Error(lineNumber, "#else without #if");
                        if (branch.hasElse) throw new Error(lineNumber, "Duplicate #else");
                        branch.hasElse = true;
                        branch.active = branch.parentActive && !branch.taken;
                        branch.taken = true;
                    }
                    case "endif" -> {
                        if (!argument.isEmpty()) throw new Error(lineNumber, "#endif takes no condition");
                        if (branches.isEmpty()) throw new Error(lineNumber, "#endif without #if");
                        branches.removeLast();
                    }
                }
                directives.add(new Directive(offset + line.indexOf('#'), end));
                appendBlank(result, line);
                // Keep nested directives inside an inactive outer branch in the same fold.
                if (nestedInactive) {
                    addInactiveRange(inactiveRanges, offset, end < source.length() ? end + 1 : end);
                }
            } else {
                if (active) result.append(line);
                else {
                    appendBlank(result, line);
                    addInactiveRange(inactiveRanges, offset, end < source.length() ? end + 1 : end);
                }
                state = scanState(line, state);
            }
            if (end < source.length()) result.append('\n');
            offset = end + 1;
            lineNumber++;
        }
        if (!branches.isEmpty()) throw new Error(branches.peekLast().startLine, "Missing #endif");
        return new Result(result.toString(), List.copyOf(directives), List.copyOf(inactiveRanges));
    }

    private static void addInactiveRange(List<InactiveRange> ranges, int start, int end) {
        if (start == end) return;
        if (!ranges.isEmpty() && ranges.getLast().end() == start) {
            start = ranges.removeLast().start();
        }
        ranges.add(new InactiveRange(start, end));
    }

    private static void appendBlank(StringBuilder result, String line) {
        for (int i = 0; i < line.length(); i++) result.append(line.charAt(i) == '\r' ? '\r' : ' ');
    }

    private static int[] parseVersion(String value, int line) {
        if (!VERSION.matcher(value).matches()) throw new Error(line, "Invalid Minecraft version '" + value + "'");
        String[] segments = value.split("\\.");
        int[] parts = new int[segments.length];
        try {
            for (int i = 0; i < segments.length; i++) parts[i] = Integer.parseInt(segments[i]);
        } catch (NumberFormatException exception) {
            throw new Error(line, "Invalid Minecraft version '" + value + "'");
        }
        if (parts[0] != 1 && parts[0] < 26) throw new Error(line, "Use a full Minecraft version such as 1.21.6 or 26.1");
        return parts;
    }

    private static boolean evaluate(String text, int[] target, int line) {
        Matcher match = CONDITION.matcher(text.trim());
        if (!match.matches()) throw new Error(line, "Expected condition like '#if MC >= 26.1'");
        int[] other = parseVersion(match.group(2), line);
        int comparison = 0;
        for (int i = 0; i < Math.max(target.length, other.length); i++) {
            comparison = Integer.compare(i < target.length ? target[i] : 0, i < other.length ? other[i] : 0);
            if (comparison != 0) break;
        }
        return switch (match.group(1)) {
            case "==" -> comparison == 0;
            case "!=" -> comparison != 0;
            case "<" -> comparison < 0;
            case "<=" -> comparison <= 0;
            case ">" -> comparison > 0;
            default -> comparison >= 0;
        };
    }

    private static State scanState(String line, State initial) {
        State state = initial;
        if (state == State.CODE && line.stripLeading().startsWith("/")) return state;
        for (int i = 0; i < line.length(); i++) {
            switch (state) {
                case CODE -> {
                    if (line.startsWith("\"\"\"", i)) { state = State.MULTILINE_STRING; i += 2; }
                    else if (line.startsWith("#{", i)) { state = State.DOC_COMMENT; i++; }
                    else if (line.startsWith("###", i)) {
                        int end = line.endsWith("\r") ? line.length() - 1 : line.length();
                        if (i + 3 != end) return State.CODE;
                        state = State.TRIPLE_DOC_COMMENT; i += 2;
                    }
                    else if (line.startsWith("##", i)) { state = State.BLOCK_COMMENT; i++; }
                    else if (line.charAt(i) == '#') return State.CODE;
                    else if (line.charAt(i) == '"') state = State.DOUBLE_STRING;
                    else if (line.charAt(i) == '\'') state = State.SINGLE_STRING;
                }
                case SINGLE_STRING -> { if (line.charAt(i) == '\'') state = State.CODE; }
                case DOUBLE_STRING -> { if (line.charAt(i) == '"') state = State.CODE; }
                case MULTILINE_STRING -> { if (line.startsWith("\"\"\"", i)) { state = State.CODE; i += 2; } }
                case BLOCK_COMMENT -> { if (line.startsWith("##", i)) { state = State.CODE; i++; } }
                case DOC_COMMENT -> { if (line.startsWith("}#", i)) { state = State.CODE; i++; } }
                case TRIPLE_DOC_COMMENT -> { if (line.startsWith("###", i)) { state = State.CODE; i += 2; } }
            }
        }
        return state;
    }
}
