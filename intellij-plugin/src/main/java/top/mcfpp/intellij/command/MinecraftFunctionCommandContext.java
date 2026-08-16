package top.mcfpp.intellij.command;

import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Maps a vanilla .mcfunction line to the command text understood by Datapack Sandbox. */
public record MinecraftFunctionCommandContext(
        String buffer,
        int cursor,
        int documentBaseOffset,
        int caretOffset
) {
    public static @Nullable MinecraftFunctionCommandContext at(CharSequence source, int caretOffset) {
        int boundedCaret = Math.max(0, Math.min(caretOffset, source.length()));
        int lineStart = lineStart(source, boundedCaret);
        int lineEnd = lineEnd(source, boundedCaret);
        int contentStart = firstNonWhitespace(source, lineStart, lineEnd);
        if (contentStart < lineEnd && source.charAt(contentStart) == '#') return null;

        int base = contentStart;
        if (base < lineEnd && source.charAt(base) == '$') {
            base++;
            if (insideMacroPlaceholder(source, base, boundedCaret)) return null;
        }
        if (boundedCaret < base) return null;
        String buffer = source.subSequence(base, lineEnd).toString();
        return new MinecraftFunctionCommandContext(buffer, boundedCaret - base, base, boundedCaret);
    }

    public static List<StaticCommand> staticCommands(CharSequence source) {
        List<StaticCommand> commands = new ArrayList<>();
        int lineStart = 0;
        while (lineStart < source.length()) {
            int lineEnd = lineEnd(source, lineStart);
            int commandStart = firstNonWhitespace(source, lineStart, lineEnd);
            if (commandStart < lineEnd && source.charAt(commandStart) != '#') {
                boolean macro = source.charAt(commandStart) == '$';
                if (macro) commandStart++;
                int commandEnd = lineEnd;
                while (commandEnd > commandStart && Character.isWhitespace(source.charAt(commandEnd - 1))) commandEnd--;
                if (commandStart < commandEnd && (!macro || !containsMacroPlaceholder(source, commandStart, commandEnd))) {
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

    private static boolean containsMacroPlaceholder(CharSequence source, int start, int end) {
        for (int index = start; index + 1 < end; index++) {
            if (source.charAt(index) == '$' && source.charAt(index + 1) == '(') return true;
        }
        return false;
    }

    private static boolean insideMacroPlaceholder(CharSequence source, int start, int end) {
        int depth = 0;
        for (int index = start; index < end; index++) {
            char character = source.charAt(index);
            if (character == '$' && index + 1 < end && source.charAt(index + 1) == '(') {
                depth++;
                index++;
            } else if (depth > 0 && character == '(') {
                depth++;
            } else if (depth > 0 && character == ')') {
                depth--;
            }
        }
        return depth > 0;
    }

    private static int lineStart(CharSequence source, int offset) {
        int index = offset;
        while (index > 0 && source.charAt(index - 1) != '\n' && source.charAt(index - 1) != '\r') index--;
        return index;
    }

    private static int lineEnd(CharSequence source, int offset) {
        int index = offset;
        while (index < source.length() && source.charAt(index) != '\n' && source.charAt(index) != '\r') index++;
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

    public record StaticCommand(String text, TextRange range) {
    }
}
