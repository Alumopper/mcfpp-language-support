package top.mcfpp.intellij.run;
import com.intellij.execution.filters.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import java.nio.file.Path;
import java.util.regex.Pattern;
public final class McfppConsoleFilter implements Filter {
    private final Project project;
    private String compilerFile;
    private static final Pattern LOCATION=Pattern.compile("(.+?):(\\d+):(?:(\\d+):)?");
    private static final Pattern COMPILER=Pattern.compile("(?:Error while compiling |Syntax Error in |Warning while compiling )(.+?)>>");
    private static final Pattern SOURCE=Pattern.compile("^\\s*(\\d+) \\|");
    public McfppConsoleFilter(Project project) { this.project=project; }
    @Override public Result applyFilter(String line,int entireLength) {
        if(line.startsWith("MCFPP IR: ")) {
            String path=line.substring("MCFPP IR: ".length()).strip();
            try {
                var file=LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(path));
                return file==null ? null : new Result(entireLength-line.length()+"MCFPP IR: ".length(),
                    entireLength-line.length()+line.stripTrailing().length(),new OpenFileHyperlinkInfo(project,file,0));
            } catch(RuntimeException ignored) { return null; }
        }
        if(line.startsWith("MCFPP test report: ")) {
            String path=line.substring("MCFPP test report: ".length()).strip();
            return new Result(entireLength-line.length()+"MCFPP test report: ".length(),entireLength-line.length()+line.stripTrailing().length(),
                new BrowserHyperlinkInfo(Path.of(path).toUri().toString()));
        }
        var compiler=COMPILER.matcher(line);
        var source=SOURCE.matcher(line);
        var matcher=LOCATION.matcher(line);
        int number=0,column=0,start=0,end=line.stripTrailing().length(); String path=null;
        // Compiler headers include a log timestamp; recognize them before generic file:line locations.
        if(compiler.find()) { path=compilerFile=compiler.group(1).trim(); start=compiler.start(1); end=compiler.end(1); }
        else if(source.find() && compilerFile!=null) { path=compilerFile; number=Integer.parseInt(source.group(1))-1; }
        else if(matcher.find()) { path=matcher.group(1).trim(); number=Integer.parseInt(matcher.group(2))-1; column=matcher.group(3)==null ? 0 : Integer.parseInt(matcher.group(3))-1; start=matcher.start(); end=matcher.end(); }
        if(path==null) return null;
        try {
            Path file=Path.of(path); if(!file.isAbsolute() && project.getBasePath()!=null) file=Path.of(project.getBasePath()).resolve(file);
            var target=LocalFileSystem.getInstance().findFileByNioFile(file);
            return target==null ? null : new Result(entireLength-line.length()+start,entireLength-line.length()+end,new OpenFileHyperlinkInfo(project,target,number,column));
        } catch(RuntimeException ignored) { return null; }
    }
}

