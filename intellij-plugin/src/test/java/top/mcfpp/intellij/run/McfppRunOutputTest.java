package top.mcfpp.intellij.run;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;

public class McfppRunOutputTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void acceptsAnEmptyEventStream() throws Exception {
        var outputs = temporary.newFile("empty.jsonl").toPath();
        var messages = new ArrayList<String>();
        McfppRunOutput.publish(outputs, messages::add);
        Files.writeString(outputs, "\r\n  \t");
        McfppRunOutput.publish(outputs, messages::add);
        assertEquals(List.of(), messages);
    }

    @Test public void presentsRenderedMessagesAndExplicitQueriesOnly() throws Exception {
        var outputs = temporary.newFile("events.jsonl").toPath();
        Files.writeString(outputs, """
            {
              "channel": "data",
              "text": "1",
              "source": {"command": "data modify storage mcfpp:system stack_frame prepend value {}"}
            }
            {"channel":"chat","text":"same"}
            {"channel":"data","text":"1","source":{"command":"execute store result score tmp example run data get storage example:test value"}}
            {"channel":"chat","text":"same"}
            {"channel":"chat","text":"[\\n  0,\\n  0,\\n  0\\n]","rawText":"unformatted"}
            {"channel":"data","text":"-4","source":{"command":"scoreboard players get result example"}}
            {"channel":"data","text":"7","source":{"command":"data get storage example:test value"}}
            {"channel":"data","text":"0.5","source":{"command":"compute test"}}
            {"channel":"data","text":"1","source":{"command":"data remove storage mcfpp:system stack_frame[0]"}}
            """);
        var output = new ArrayList<String>();
        McfppRunOutput.publish(outputs, output::add);
        assertEquals(List.of("same\n", "same\n", "[\n  0,\n  0,\n  0\n]\n", "-4\n", "7\n", "0.5\n"), output);
    }

    @Test public void tailsCompleteObjectsAcrossPollsWithoutRepeatingOutput() throws Exception {
        var outputs = temporary.newFile("live.jsonl").toPath();
        var messages = new ArrayList<String>();
        try (var tail = new McfppRunOutput.Tail(outputs, messages::add)) {
            Files.writeString(outputs, "{\"text\":\"first\"}\r\n{\n  \"text\": \"",
                StandardOpenOption.APPEND);
            tail.poll();
            tail.poll();
            assertEquals(List.of("first\n"), messages);
            byte[] end = "分割 } \\\"quoted\\\" \\nvalue\"\n}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(outputs, java.util.Arrays.copyOf(end, 1), StandardOpenOption.APPEND);
            tail.poll();
            assertEquals(List.of("first\n"), messages);
            Files.write(outputs, java.util.Arrays.copyOfRange(end, 1, end.length), StandardOpenOption.APPEND);
            tail.finish();
            assertEquals(List.of("first\n", "分割 } \"quoted\" \nvalue\n"), messages);
        }
    }
}
