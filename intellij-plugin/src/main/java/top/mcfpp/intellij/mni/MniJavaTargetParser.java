package top.mcfpp.intellij.mni;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MniJavaTargetParser {
    static final int COMPLETION_CONTEXT_LIMIT = 8 * 1024;
    private static final String IDENTIFIER = "[A-Za-z_$][A-Za-z0-9_$]*";
    private static final String QUALIFIED_NAME = IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER + ")+";
    private static final Pattern SEGMENT = Pattern.compile(IDENTIFIER);
    private static final Pattern FROM_TARGET = Pattern.compile(
            "@From\\s*<\\s*[\\\"']?\\s*(" + QUALIFIED_NAME + ")"
    );
    private static final String JAVA_METHOD_DECLARATION = "(?:func|operator|get|set)";
    private static final Pattern JAVA_METHOD_TARGET = Pattern.compile(
            "\\b" + JAVA_METHOD_DECLARATION + "\\b" +
                    "(?:(?!\\b" + JAVA_METHOD_DECLARATION + "\\b|[;{}]).)*=\\s*(" + QUALIFIED_NAME + ")",
            Pattern.DOTALL
    );
    private static final Pattern FROM_COMPLETION = Pattern.compile(
            "@From\\s*<\\s*[\\\"']?\\s*([A-Za-z0-9_$.]*)$"
    );
    private static final Pattern JAVA_METHOD_COMPLETION = Pattern.compile(
            "\\b" + JAVA_METHOD_DECLARATION + "\\b" +
                    "(?:(?!\\b" + JAVA_METHOD_DECLARATION + "\\b|[;{}]).)*=\\s*([A-Za-z0-9_$.]*)$",
            Pattern.DOTALL
    );

    private MniJavaTargetParser() {
    }

    public static List<Target> parse(CharSequence source) {
        List<Target> targets = new ArrayList<>();
        collect(source, FROM_TARGET, Kind.FROM, targets);
        collect(source, JAVA_METHOD_TARGET, Kind.JAVA_METHOD, targets);
        targets.sort(Comparator.comparingInt(Target::startOffset));
        return List.copyOf(targets);
    }

    public static Optional<CompletionContext> completionAt(CharSequence source, int offset) {
        int safeOffset = Math.max(0, Math.min(offset, source.length()));
        int contextStart = safeOffset;
        int earliestContext = Math.max(0, safeOffset - COMPLETION_CONTEXT_LIMIT);
        while (contextStart > earliestContext) {
            char previous = source.charAt(contextStart - 1);
            if (previous == ';' || previous == '{' || previous == '}') {
                break;
            }
            contextStart--;
        }

        String declarationPrefix = source.subSequence(contextStart, safeOffset).toString();
        Optional<CompletionContext> from = completion(declarationPrefix, FROM_COMPLETION, Kind.FROM);
        return from.isPresent()
                ? from
                : completion(declarationPrefix, JAVA_METHOD_COMPLETION, Kind.JAVA_METHOD);
    }

    private static Optional<CompletionContext> completion(String linePrefix, Pattern pattern, Kind kind) {
        Matcher matcher = pattern.matcher(linePrefix);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(new CompletionContext(kind, matcher.group(1)));
    }

    private static void collect(CharSequence source, Pattern pattern, Kind kind, List<Target> targets) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            int qualifiedNameStart = matcher.start(1);
            Matcher segmentMatcher = SEGMENT.matcher(matcher.group(1));
            List<Segment> segments = new ArrayList<>();
            while (segmentMatcher.find()) {
                segments.add(new Segment(
                        segmentMatcher.group(),
                        qualifiedNameStart + segmentMatcher.start(),
                        qualifiedNameStart + segmentMatcher.end(),
                        segments.size()
                ));
            }
            if (segments.size() >= 2) {
                targets.add(new Target(kind, List.copyOf(segments), matcher.start()));
            }
        }
    }

    public enum Kind {
        FROM,
        JAVA_METHOD
    }

    public record Segment(String text, int startOffset, int endOffset, int ordinal) {
    }

    public record Target(Kind kind, List<Segment> segments, int declarationStartOffset) {
        public int startOffset() {
            return segments.getFirst().startOffset();
        }

        public int endOffset() {
            return segments.getLast().endOffset();
        }

        public String qualifiedName() {
            return join(segments.size());
        }

        public String ownerQualifiedName() {
            int segmentCount = kind == Kind.JAVA_METHOD ? segments.size() - 1 : segments.size();
            return join(segmentCount);
        }

        public String memberName() {
            return kind == Kind.JAVA_METHOD ? segments.getLast().text() : "";
        }

        private String join(int segmentCount) {
            return segments.stream()
                    .limit(segmentCount)
                    .map(Segment::text)
                    .reduce((left, right) -> left + "." + right)
                    .orElse("");
        }
    }

    public record CompletionContext(Kind kind, String qualifiedPrefix) {
        public String qualifier() {
            int dot = qualifiedPrefix.lastIndexOf('.');
            return dot < 0 ? "" : qualifiedPrefix.substring(0, dot);
        }

        public String namePrefix() {
            int dot = qualifiedPrefix.lastIndexOf('.');
            return dot < 0 ? qualifiedPrefix : qualifiedPrefix.substring(dot + 1);
        }
    }
}
