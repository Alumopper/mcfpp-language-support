package top.mcfpp.intellij.format;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import top.mcfpp.intellij.tooling.McfppSettings;
import top.mcfpp.intellij.tooling.McfppToolchain;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

@Service(Service.Level.PROJECT)
public final class McfppNodeService implements Disposable {
    private static final int MAX_ERROR_BUFFER = 4000;
    private static final long REQUEST_TIMEOUT_SECONDS = 15L;
    private static final long LOCK_TIMEOUT_SECONDS = 5L;

    private final Project project;
    private final ReentrantLock requestLock = new ReentrantLock();
    private final Object errorLock = new Object();
    private final StringBuilder errorBuffer = new StringBuilder();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean availabilityProbeRunning = new AtomicBoolean();
    private final Object availabilityLock = new Object();
    private final ExecutorService io = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "MCFPP formatter");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean disposed;
    private volatile String checkedAvailabilityKey;
    private volatile Boolean formatterAvailable;
    private Process process;
    private BufferedReader reader;
    private BufferedWriter writer;

    public McfppNodeService(Project project) {
        this.project = project;
    }

    /** Returns cached availability and schedules any new probe off the EDT. */
    public boolean isFormatterAvailable() {
        if (disposed || project.isDisposed()) return false;
        String key = availabilityKey();
        synchronized (availabilityLock) {
            if (!Objects.equals(checkedAvailabilityKey, key)) {
                checkedAvailabilityKey = key;
                formatterAvailable = null;
            }
            if (formatterAvailable != null) return formatterAvailable;
        }
        if (availabilityProbeRunning.compareAndSet(false, true)) {
            io.execute(() -> probeAvailability(key));
        }
        return false;
    }

    private String availabilityKey() {
        return McfppSettings.get(project).nodePath.trim() + "\n" +
                System.getProperty("mcfpp.formatter.path", "").trim();
    }

    private void probeAvailability(String key) {
        boolean available = false;
        Process probe = null;
        try {
            probe = McfppToolchain.node(project, "--version").createProcess();
            available = probe.waitFor(3, TimeUnit.SECONDS) && probe.exitValue() == 0 &&
                    Files.isRegularFile(McfppToolchain.formatter());
        } catch (Exception ignored) {
            // Missing Node.js or formatter files select the built-in PSI formatter.
        } finally {
            if (probe != null && probe.isAlive()) {
                probe.destroy();
                if (probe.isAlive()) probe.destroyForcibly();
            }
            synchronized (availabilityLock) {
                if (Objects.equals(checkedAvailabilityKey, key)) formatterAvailable = available;
            }
            availabilityProbeRunning.set(false);
            if (!disposed && !project.isDisposed() && !Objects.equals(key, availabilityKey())) {
                isFormatterAvailable();
            }
        }
    }

    public JsonObject request(String operation, String path, String source, boolean fix, int cursor) throws IOException {
        if (disposed || project.isDisposed()) throw new IOException("The project is closed");
        boolean acquired;
        try {
            acquired = requestLock.tryLock(LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for MCFPP formatter", interrupted);
        }
        if (!acquired) {
            throw new IOException("MCFPP formatter is busy; timed out waiting for previous request");
        }
        try {
            if (disposed || project.isDisposed()) throw new IOException("The project is closed");
            start();
            long id = sequence.incrementAndGet();
            JsonObject input = new JsonObject();
            input.addProperty("id", id);
            input.addProperty("operation", operation);
            input.addProperty("filePath", path);
            input.addProperty("source", source);
            input.addProperty("fix", fix);
            input.addProperty("cursorOffset", cursor);
            writer.write(input.toString());
            writer.newLine();
            writer.flush();
            BufferedReader activeReader = reader;
            Future<String> lineFuture = io.submit(activeReader::readLine);
            String line;
            try {
                line = lineFuture.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                lineFuture.cancel(true);
                throw e;
            }
            if (line == null) {
                markFormatterUnavailable();
                throw new FormatterUnavailableException("Node.js stopped. " + errorSnapshot());
            }
            JsonObject result = JsonParser.parseString(line).getAsJsonObject();
            if (!result.has("id") || result.get("id").getAsLong() != id) {
                throw new IOException("Unexpected formatter response");
            }
            if (!result.get("ok").getAsBoolean()) {
                throw new IOException(result.get("error").getAsString());
            }
            return result;
        } catch (Exception e) {
            stopProcess();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            if (e instanceof FormatterUnavailableException unavailable) throw unavailable;
            throw e instanceof IOException ioe ? ioe : new IOException("MCFPP formatter: " + e.getMessage(), e);
        } finally {
            requestLock.unlock();
        }
    }

    private void start() throws IOException {
        if (process != null && process.isAlive() && reader != null && writer != null) return;
        stopProcess();
        try {
            var formatter = McfppToolchain.formatter();
            if (!Files.isRegularFile(formatter)) throw new IOException("Formatter tool is missing");
            var command = McfppToolchain.formatterCommand(project);
            command.withWorkDirectory(formatter.getParent().toFile());
            Process started = command.createProcess();
            process = started;
            reader = new BufferedReader(new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(started.getOutputStream(), StandardCharsets.UTF_8));
            clearErrorBuffer();
            io.submit(() -> {
                try (var errors = new BufferedReader(new InputStreamReader(started.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = errors.readLine()) != null) {
                        appendError(line);
                    }
                } catch (IOException ignored) {
                    // Stream closed when process exits.
                }
            });
        } catch (Exception unavailable) {
            markFormatterUnavailable();
            throw new FormatterUnavailableException("Node.js or the formatter tool is unavailable", unavailable);
        }
    }

    private void markFormatterUnavailable() {
        synchronized (availabilityLock) {
            checkedAvailabilityKey = availabilityKey();
            formatterAvailable = false;
        }
    }

    private void appendError(String line) {
        synchronized (errorLock) {
            errorBuffer.append(line).append('\n');
            if (errorBuffer.length() > MAX_ERROR_BUFFER) {
                errorBuffer.delete(0, errorBuffer.length() - MAX_ERROR_BUFFER);
            }
        }
    }

    private void clearErrorBuffer() {
        synchronized (errorLock) {
            errorBuffer.setLength(0);
        }
    }

    private String errorSnapshot() {
        synchronized (errorLock) {
            return errorBuffer.toString();
        }
    }

    private void stopProcess() {
        Process current = process;
        BufferedReader activeReader = reader;
        BufferedWriter activeWriter = writer;
        process = null;
        reader = null;
        writer = null;
        closeQuietly(activeWriter);
        closeQuietly(activeReader);
        if (current != null) {
            current.destroy();
            if (current.isAlive()) current.destroyForcibly();
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    public void restart() {
        if (requestLock.tryLock()) {
            try {
                stopProcess();
            } finally {
                requestLock.unlock();
            }
        } else {
            // Force-stop hung process so any blocked reader immediately unblocks.
            stopProcess();
        }
    }

    @Override
    public void dispose() {
        disposed = true;
        stopProcess();
        io.shutdownNow();
    }

    static final class FormatterUnavailableException extends IOException {
        FormatterUnavailableException(String message) { super(message); }
        FormatterUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
