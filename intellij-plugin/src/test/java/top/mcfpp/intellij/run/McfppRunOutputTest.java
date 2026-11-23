package top.mcfpp.intellij.run;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;

public class McfppRunOutputTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

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
}
