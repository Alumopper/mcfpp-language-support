package top.mcfpp.language;

/** Extracts member receivers while preserving calls and multiline compound literals. */
public final class MemberExpressions {
    private MemberExpressions() {}

    public static boolean endsAtCall(String text, int open) {
        if (open < 0 || !text.endsWith(")")) return false;
        var code = McfppCodeMask.scan(text, text.length());
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            if (!code.includes(i)) continue;
            if (text.charAt(i) == '(') depth++;
            if (text.charAt(i) == ')' && --depth == 0) return i == text.length() - 1;
        }
        return false;
    }

    /** Receiver of a final member call, excluding calls nested inside its arguments. */
    public static String calledMemberReceiver(String expression, String name) {
        String text = expression.strip();
        if (!text.endsWith(")")) return null;
        var code = McfppCodeMask.scan(text, text.length());
        var matcher = java.util.regex.Pattern.compile("\\.\\s*" + java.util.regex.Pattern.quote(name) + "\\s*\\(").matcher(text);
        while (matcher.find()) {
            if (!code.includes(matcher.start())) continue;
            int depth = 1;
            for (int index = matcher.end(); index < text.length(); index++) {
                if (!code.includes(index)) continue;
                if (text.charAt(index) == '(') depth++;
                else if (text.charAt(index) == ')' && --depth == 0) {
                    if (index == text.length() - 1) return receiverBefore(text, matcher.start());
                    break;
                }
            }
        }
        return null;
    }

    public static String receiverBefore(CharSequence source, int separator) {
        if (separator < 0 || separator >= source.length() || source.charAt(separator) != '.') return null;
        // Either dot in a range operator is not a member separator.
        if (separator > 0 && source.charAt(separator - 1) == '.' ||
                separator + 1 < source.length() && source.charAt(separator + 1) == '.') return null;
        var code = McfppCodeMask.scan(source, separator + 1);
        return code.includes(separator) ? expressionBefore(source, separator, code) : null;
    }

    /** Receiver in a reference read with a trailing callback. */
    public static String tryReadReceiver(String source, int brace) {
        int end = brace;
        while (end > 0 && Character.isWhitespace(source.charAt(end - 1))) end--;
        int start = end - "tryRead".length();
        if (start < 0 || !source.startsWith("tryRead", start)) return null;
        while (start > 0 && Character.isWhitespace(source.charAt(start - 1))) start--;
        return receiverBefore(source, start - 1);
    }

    static String expressionBefore(CharSequence source, int separator) {
        return expressionBefore(source, separator, McfppCodeMask.scan(source, separator));
    }

    private static String expressionBefore(CharSequence source, int separator, McfppCodeMask code) {
        int end = separator;
        if (end > 0 && source.charAt(end - 1) == '?') end--;
        while (end > code.start() && Character.isWhitespace(source.charAt(end - 1))) end--;
        int parentheses = 0, brackets = 0, braces = 0, angles = 0;
        int index = end - 1;
        for (; index >= code.start(); index--) {
            if (!code.includes(index)) continue;
            char value = source.charAt(index);
            boolean nested = parentheses > 0 || brackets > 0 || braces > 0 || angles > 0;
            if (value == ')') parentheses++;
            else if (value == ']') brackets++;
            else if (value == '}') braces++;
            else if (value == '>') angles++;
            else if (value == '(') { if (parentheses == 0) break; parentheses--; }
            else if (value == '[') { if (brackets == 0) break; brackets--; }
            else if (value == '{') { if (braces == 0) break; braces--; }
            else if (value == '<') { if (angles == 0) break; angles--; }
            else if (!nested && value == ':' && literalKeyColon(source, index)) break;
            else if (!nested && value == '!' && index > 0 && source.charAt(index - 1) == '!') index--;
            else if (!nested && value == '?' && index + 1 < end && source.charAt(index + 1) == '.') { }
            else if (!nested && "=;,+-*/%!&|?".indexOf(value) >= 0) break;
            else if (!nested && Character.isWhitespace(value)) {
                int left = index - 1, right = index + 1;
                while (left >= 0 && Character.isWhitespace(source.charAt(left))) left--;
                while (right < end && Character.isWhitespace(source.charAt(right))) right++;
                char before = left < 0 ? '\0' : source.charAt(left);
                char after = right >= end ? '\0' : source.charAt(right);
                boolean trailingBlock = after == '{' && isTrailingBlockAttachment(source, left, right);
                if (before != '.' && before != ':' && after != '.' && after != ':' && after != '(' && !trailingBlock) break;
            }
        }
        String expression = source.subSequence(index + 1, end).toString().strip();
        return expression.isEmpty() ? null : expression;
    }

    private static boolean isTrailingBlockAttachment(CharSequence source, int left, int right) {
        if (left < 0) return false;
        for (int i = left + 1; i < right; i++) {
            char c = source.charAt(i);
            if (c == '\n' || c == '\r') return false;
        }
        char before = source.charAt(left);
        if (before == ')') return true;
        if (!Character.isUnicodeIdentifierPart(before)) return false;
        int start = left;
        while (start > 0 && Character.isUnicodeIdentifierPart(source.charAt(start - 1))) start--;
        String word = source.subSequence(start, left + 1).toString();
        return !java.util.Set.of("return", "else", "do", "try", "if", "while", "for", "var", "const", "external", "global", "in", "as").contains(word);
    }

    private static boolean literalKeyColon(CharSequence source, int colon) {
        int index = colon - 1;
        while (index >= 0 && Character.isWhitespace(source.charAt(index))) index--;
        if (index < 0) return false;
        char last = source.charAt(index);
        if (last == '\'' || last == '"') {
            char quote = last;
            for (index--; index >= 0; index--) {
                if (source.charAt(index) != quote) continue;
                int escapes = 0;
                for (int left = index - 1; left >= 0 && source.charAt(left) == '\\'; left--) escapes++;
                if (escapes % 2 == 0) { index--; break; }
            }
        } else {
            if (!Character.isUnicodeIdentifierPart(last)) return false;
            while (index >= 0 && Character.isUnicodeIdentifierPart(source.charAt(index))) index--;
        }
        while (index >= 0 && Character.isWhitespace(source.charAt(index))) index--;
        return index >= 0 && (source.charAt(index) == '{' || source.charAt(index) == ',');
    }

}
