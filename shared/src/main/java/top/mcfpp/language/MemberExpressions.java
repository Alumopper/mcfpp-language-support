package top.mcfpp.language;

/** Extracts member receivers while preserving calls and multiline compound literals. */
public final class MemberExpressions {
    private MemberExpressions() {}

    /** Receiver of a final member call, excluding calls nested inside its arguments. */
    public static String calledMemberReceiver(String expression, String name) {
        String text = expression.strip();
        if (!text.endsWith(")")) return null;
        boolean[] code = codeMask(text, text.length());
        var matcher = java.util.regex.Pattern.compile("\\.\\s*" + java.util.regex.Pattern.quote(name) + "\\s*\\(").matcher(text);
        while (matcher.find()) {
            if (!code[matcher.start()]) continue;
            int depth = 1;
            for (int index = matcher.end(); index < text.length(); index++) {
                if (!code[index]) continue;
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
        boolean[] code = codeMask(source, separator);
        int end = separator;
        if (end > 0 && source.charAt(end - 1) == '?') end--;
        while (end > 0 && Character.isWhitespace(source.charAt(end - 1))) end--;
        int parentheses = 0, brackets = 0, braces = 0, angles = 0;
        int index = end - 1;
        for (; index >= 0; index--) {
            if (!code[index]) continue;
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
                if (before != '.' && before != ':' && after != '.' && after != ':' && after != '(') break;
            }
        }
        String expression = source.subSequence(index + 1, end).toString().strip();
        return expression.isEmpty() ? null : expression;
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

    private static boolean[] codeMask(CharSequence source, int end) {
        boolean[] code = new boolean[end];
        for (int index = 0; index < end;) {
            char value = source.charAt(index);
            if (value == '\'' || value == '"') {
                char quote = value;
                boolean triple = index + 2 < end && source.charAt(index + 1) == quote && source.charAt(index + 2) == quote;
                index += triple ? 3 : 1;
                while (index < end) {
                    if (source.charAt(index) == '\\') { index = Math.min(end, index + 2); continue; }
                    if (source.charAt(index) == quote && (!triple || index + 2 < end &&
                            source.charAt(index + 1) == quote && source.charAt(index + 2) == quote)) {
                        index += triple ? 3 : 1;
                        break;
                    }
                    index++;
                }
            } else if (value == '/' && index + 1 < end && source.charAt(index + 1) == '/') {
                while (index < end && source.charAt(index) != '\n') index++;
            } else if (value == '/' && index + 1 < end && source.charAt(index + 1) == '*') {
                index += 2;
                while (index + 1 < end && !(source.charAt(index) == '*' && source.charAt(index + 1) == '/')) index++;
                index = Math.min(end, index + 2);
            } else code[index++] = true;
        }
        return code;
    }
}
