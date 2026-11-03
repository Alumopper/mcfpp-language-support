package top.mcfpp.intellij.run;

import com.intellij.execution.process.ProcessOutputType;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.util.Key;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/** Assign console severity from messages, independently of ANSI colors and the progress stream. */
final class McfppConsoleOutput {
    private static final Pattern LEVEL=Pattern.compile("^\\[(DEBUG|INFO|WARN|ERROR|FATAL)(?:/|\\])");
    private static final Pattern ERROR=Pattern.compile("^(?:MCFPP:|FAILURE:|Execution failed for task|Exception in thread|(?:[\\w.$]+)?(?:Exception|Error):)|: error: ");
    private final StringBuilder[] pending={new StringBuilder(),new StringBuilder()};
    private final boolean[] errors={false,false};

    synchronized void accept(String text,Key<?> type,BiConsumer<String,Key<?>> output) {
        if(type==ProcessOutputTypes.SYSTEM) { output.accept(text,type); return; }
        int stream=ProcessOutputType.isStderr(type) ? 1 : 0;
        var buffer=pending[stream];
        buffer.append(text);
        int newline;
        while((newline=buffer.indexOf("\n"))>=0) {
            String line=buffer.substring(0,newline+1);
            buffer.delete(0,newline+1);
            emit(line,stream,output);
        }
    }

    synchronized void flush(BiConsumer<String,Key<?>> output) {
        for(int stream=0;stream<pending.length;stream++) {
            if(!pending[stream].isEmpty()) { emit(pending[stream].toString(),stream,output); pending[stream].setLength(0); }
        }
    }

    private void emit(String line,int stream,BiConsumer<String,Key<?>> output) {
        String message=line.stripLeading();
        var level=LEVEL.matcher(message);
        if(level.find()) errors[stream]=level.group(1).equals("ERROR") || level.group(1).equals("FATAL");
        else if(ERROR.matcher(message).find()) errors[stream]=true;
        else if(message.startsWith("Built ") || message.startsWith("BUILD SUCCESSFUL") || message.startsWith("Dependencies ready.")) errors[stream]=false;
        output.accept(line,errors[stream] ? ProcessOutputTypes.STDERR : ProcessOutputTypes.STDOUT);
    }
}
