package top.mcfpp.intellij.command;

import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Maps an MCFPP raw-command line to the command text understood by Datapack Sandbox. */
public record McfppCommandContext(
        String buffer,
        int cursor,
        int documentBaseOffset,
        int caretOffset
) {
    public static @Nullable McfppCommandContext at(CharSequence source, int caretOffset) {
        int boundedCaret = Math.max(0, Math.min(caretOffset, source.length()));
        int lineStart = lineStart(source, boundedCaret);
        int slash = firstNonWhitespace(source, lineStart, boundedCaret);
        if (slash >= boundedCaret || source.charAt(slash) != '/') return null;
        if (insideInterpolation(source, slash + 1, boundedCaret)) return null;

        int base = slash + 1;
        int end = lineEnd(source, boundedCaret);
        String buffer = source.subSequence(base, end).toString();
        return new McfppCommandContext(buffer, boundedCaret - base, base, boundedCaret);
    }

    public int documentOffset(int commandOffset) {
        return documentBaseOffset + Math.max(0, Math.min(commandOffset, buffer.length()));
    }

    public static List<StaticCommand> staticCommands(CharSequence source) {
        List<StaticCommand> commands = new ArrayList<>();
        int lineStart = 0;
        while (lineStart < source.length()) {
            int lineEnd = lineEnd(source, lineStart);
            int slash = firstNonWhitespace(source, lineStart, lineEnd);
            if (slash < lineEnd && source.charAt(slash) == '/' &&
                    !containsInterpolation(source, slash + 1, lineEnd)) {
                int commandStart = slash + 1;
                while (commandStart < lineEnd && Character.isWhitespace(source.charAt(commandStart))) commandStart++;
                int commandEnd = lineEnd;
                while (commandEnd > commandStart && Character.isWhitespace(source.charAt(commandEnd - 1))) commandEnd--;
                if (commandStart < commandEnd) {
                    commands.add(new StaticCommand(
                            source.subSequence(commandStart, commandEnd).toString(),
                            new TextRange(commandStart, commandEnd)
                    ));
                }
            }
            lineStart = skipLineBreak(source, lineEnd);
        }
        return List.copyOf(commands);
    }

    private static int lineStart(CharSequence source, int offset) {
        int index = offset;
        while (index > 0) {
            char previous = source.charAt(index - 1);
            if (previous == '\n' || previous == '\r') break;
            index--;
        }
        return index;
    }

    private static int lineEnd(CharSequence source, int offset) {
        int index = offset;
        while (index < source.length()) {
            char character = source.charAt(index);
            if (character == '\n' || character == '\r') break;
            index++;
        }
        return index;
    }

    private static int skipLineBreak(CharSequence source, int offset) {
        int index = offset;
        if (index < source.length() && source.charAt(index) == '\r') index++;
        if (index < source.length() && source.charAt(index) == '\n') index++;
        return index;
    }

    private static int firstNonWhitespace(CharSequence source, int start, int end) {
        int index = start;
        while (index < end && (source.charAt(index) == ' ' || source.charAt(index) == '\t' ||
                source.charAt(index) == '\f')) index++;
        return index;
    }

    private static boolean containsInterpolation(CharSequence source, int start, int end) {
        for (int index = start; index + 1 < end; index++) {
            if (source.charAt(index) == '$' && source.charAt(index + 1) == '{') return true;
        }
        return false;
    }

    private static boolean insideInterpolation(CharSequence source, int start, int end) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = start; index < end; index++) {
            char character = source.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (quote != 0) {
                if (character == '\\') escaped = true;
                else if (character == quote) quote = 0;
                continue;
            }
            if (depth > 0 && (character == '\'' || character == '"')) {
                quote = character;
            } else if (character == '$' && index + 1 < end && source.charAt(index + 1) == '{') {
                depth++;
                index++;
            } else if (depth > 0 && character == '{') {
                depth++;
            } else if (depth > 0 && character == '}') {
                depth--;
            }
        }
        return depth > 0;
    }

    public record StaticCommand(String text, TextRange range) {
    }
}
