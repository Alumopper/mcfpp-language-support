package top.mcfpp.intellij.format;

import com.google.gson.*;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import top.mcfpp.intellij.tooling.McfppToolchain;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Service(Service.Level.PROJECT)
public final class McfppNodeService implements Disposable {
    private final Project project;
    private Process process;
    private BufferedReader reader;
    private BufferedWriter writer;
    private final AtomicLong sequence = new AtomicLong();
    private final ExecutorService io = Executors.newCachedThreadPool(r -> { Thread t=new Thread(r,"MCFPP formatter"); t.setDaemon(true); return t; });
    private volatile String errorOutput = "";
    public McfppNodeService(Project project) { this.project=project; }
    public synchronized JsonObject request(String operation, String path, String source, boolean fix, int cursor) throws IOException {
        try {
            if (project.isDisposed()) throw new IOException("The project is closed");
            start();
            long id=sequence.incrementAndGet();
            JsonObject input=new JsonObject();
            input.addProperty("id",id); input.addProperty("operation",operation); input.addProperty("filePath",path);
            input.addProperty("source",source); input.addProperty("fix",fix); input.addProperty("cursorOffset",cursor);
            writer.write(input.toString()); writer.newLine(); writer.flush();
            String line=io.submit(() -> reader.readLine()).get(15,TimeUnit.SECONDS);
            if (line==null) throw new IOException("Node.js stopped. " + errorOutput);
            JsonObject result=JsonParser.parseString(line).getAsJsonObject();
            if (!result.has("id") || result.get("id").getAsLong()!=id) throw new IOException("Unexpected formatter response");
            if (!result.get("ok").getAsBoolean()) throw new IOException(result.get("error").getAsString());
            return result;
        } catch (Exception e) {
            stop();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw e instanceof IOException i ? i : new IOException("MCFPP formatter: " + e.getMessage(),e);
        }
    }
    private void start() throws Exception {
        if (process!=null && process.isAlive()) return;
        var command=McfppToolchain.formatterCommand(project);
        command.withWorkDirectory(McfppToolchain.formatter().getParent().toFile());
        process=command.createProcess(); reader=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));
        writer=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8));
        errorOutput="";
        Process current=process;
        io.submit(() -> {
            try(var errors=new BufferedReader(new InputStreamReader(current.getErrorStream(),StandardCharsets.UTF_8))) {
                String line; while((line=errors.readLine())!=null) errorOutput=(errorOutput + line + "\n").substring(Math.max(0,errorOutput.length()+line.length()+1-4000));
            } catch(IOException ignored) {}
        });
    }
    private void stop() {
        if(process!=null) { process.destroy(); if(process.isAlive()) process.destroyForcibly(); }
        process=null;
    }
    public synchronized void restart() { stop(); }
    @Override public void dispose() { stop(); io.shutdownNow(); }
}

