package top.mcfpp.intellij.run;

import com.google.gson.JsonObject;
import com.google.gson.JsonStreamParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

final class McfppRunOutput {
    private McfppRunOutput() {}

    /** The DSB event stream preserves the rendered text, including line breaks. */
    static void publish(Path outputs, Consumer<String> console) throws IOException {
        try (var reader = Files.newBufferedReader(outputs)) {
            var events = new JsonStreamParser(reader);
            while (events.hasNext()) {
                JsonObject event = events.next().getAsJsonObject();
                if ("data".equals(text(event, "channel")) && !isQuery(event)) continue;
                String message = text(event, "text");
                if (message != null) console.accept(message + "\n");
            }
        }
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
