package top.mcfpp.intellij.run;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

final class McfppRunOutput {
    private McfppRunOutput() {}

    /** The DSB event stream preserves the rendered text, including line breaks. */
    static void publish(Path outputs, Consumer<String> console) throws IOException {
        try (var tail = new Tail(outputs, console)) {
            tail.finish();
        }
    }

    static final class Tail implements AutoCloseable {
        private final BufferedInputStream input;
        private final Consumer<String> console;
        private final ByteArrayOutputStream record = new ByteArrayOutputStream();
        private int depth;
        private boolean quoted;
        private boolean escaped;
        private IOException failure;

        Tail(Path outputs, Consumer<String> console) throws IOException {
            input = new BufferedInputStream(Files.newInputStream(outputs));
            this.console = console;
        }

        void poll() {
            if (failure != null) return;
            try {
                readAvailable();
            } catch (IOException error) {
                failure = error;
            } catch (JsonParseException error) {
                failure = new IOException("Invalid DSB output object", error);
            }
        }

        private void readAvailable() throws IOException {
            // DSB can pretty print each object and omit the final line separator.
            for (int b; (b = input.read()) != -1;) {
                if (depth == 0 && Character.isWhitespace(b)) continue;
                if (depth == 0 && b != '{') throw new IOException("Expected a DSB output object");
                record.write(b);
                if (quoted) {
                    if (escaped) escaped = false;
                    else if (b == '\\') escaped = true;
                    else if (b == '"') quoted = false;
                } else if (b == '"') quoted = true;
                else if (b == '{') depth++;
                else if (b == '}' && --depth == 0) {
                    JsonObject event = JsonParser.parseString(record.toString(StandardCharsets.UTF_8)).getAsJsonObject();
                    record.reset();
                    String message = text(event, "text");
                    if (message != null && (!"data".equals(text(event, "channel")) || isQuery(event))) console.accept(message + "\n");
                }
            }
        }

        void finish() throws IOException {
            poll();
            if (failure != null) throw failure;
            if (depth != 0) throw new IOException("Incomplete DSB output object");
        }

        @Override public void close() throws IOException { input.close(); }
    }

    private static boolean isQuery(JsonObject event) {
        var source = event.get("source");
        String command = source != null && source.isJsonObject() ? text(source.getAsJsonObject(), "command") : null;
        return command != null && (command.startsWith("data get ") ||
            command.startsWith("scoreboard players get ") || command.startsWith("compute "));
    }

    private static String text(JsonObject object, String field) {
        var value = object.get(field);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }
}
