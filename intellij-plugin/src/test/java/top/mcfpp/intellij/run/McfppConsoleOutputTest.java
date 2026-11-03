package top.mcfpp.intellij.run;

import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.util.Key;
import junit.framework.TestCase;
import java.util.ArrayList;

public final class McfppConsoleOutputTest extends TestCase {
    public void testProgressOnStderrAndDebugUseThemeTextButRealErrorsStayErrors() {
        var router=new McfppConsoleOutput();
        var text=new StringBuilder();
        var types=new ArrayList<Key<?>>();
        java.util.function.BiConsumer<String,Key<?>> collect=(message,type) -> { text.append(message); types.add(type); };
        router.accept("Preparing dependencies\nBUILD SUCCESSFUL in 2s\n",ProcessOutputTypes.STDERR,collect);
        router.accept("[DEBUG/LogProcessor](08:00:00): compiling\n[ERR",ProcessOutputTypes.STDOUT,collect);
        router.accept("OR/LogProcessor](08:00:01): failed\n1 | bad()\n[INFO/LogProcessor]: complete\n",ProcessOutputTypes.STDOUT,collect);
        router.accept("MCFPP: invalid configuration",ProcessOutputTypes.STDERR,collect);
        router.flush(collect);
        assertEquals(java.util.List.of(ProcessOutputTypes.STDOUT,ProcessOutputTypes.STDOUT,ProcessOutputTypes.STDOUT,
            ProcessOutputTypes.STDERR,ProcessOutputTypes.STDERR,ProcessOutputTypes.STDOUT,ProcessOutputTypes.STDERR),types);
        assertTrue(text.toString().endsWith("MCFPP: invalid configuration"));
    }
}
