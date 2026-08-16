package top.mcfpp.intellij.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Project-scoped bridge to the bundled Datapack Sandbox JSONL command service. */
@Service(Service.Level.PROJECT)
public final class McfppCommandService implements Disposable {
    private static final Logger LOG = Logger.getInstance(McfppCommandService.class);
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);
    private static final long RETRY_DELAY_NANOS = Duration.ofSeconds(30).toNanos();
    private static final int CACHE_SIZE = 256;

    private final Project project;
    private final Map<String, CompletionResult> completionCache = lruCache();
    private final Map<String, CommandCheck> checkCache = lruCache();

    private volatile Process process;
    private BufferedReader reader;
    private BufferedWriter writer;
    private ExecutorService readerExecutor;
    private long requestSequence;
    private long retryAfter;
    private long versionRefreshAfter;
    private String activeVersion;
    private String resolvedVersion;
    private volatile String stderrTail = "";
    private String lastLoggedFailure = "";

    public McfppCommandService(Project project) {
        this.project = project;
    }

    public static McfppCommandService getInstance(Project project) {
        return project.getService(McfppCommandService.class);
    }

    public synchronized boolean warmUp() {
        return ensureReady();
    }

    public synchronized CompletionResult complete(String buffer, int cursor) {
        if (buffer.length() > 64 * 1024 || !ensureReady()) return CompletionResult.EMPTY;
        int boundedCursor = Math.max(0, Math.min(cursor, buffer.length()));
        String cacheKey = activeVersion + '\0' + boundedCursor + '\0' + buffer;
        CompletionResult cached = completionCache.get(cacheKey);
        if (cached != null) return cached;

        JsonObject params = new JsonObject();
        params.addProperty("buffer", buffer);
        params.addProperty("cursor", boundedCursor);
        try {
            JsonObject result = request("completions", params, REQUEST_TIMEOUT).getAsJsonObject();
            List<CompletionSuggestion> suggestions = new ArrayList<>();
            JsonArray items = result.has("suggestions") ? result.getAsJsonArray("suggestions") : new JsonArray();
            for (JsonElement element : items) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                String value = string(item, "value");
                if (value.isEmpty()) continue;
                suggestions.add(new CompletionSuggestion(
                        value,
                        string(item, "description"),
                        string(item, "group"),
                        bool(item, "appendSpace"),
                        integer(item, "start", boundedCursor),
                        integer(item, "end", boundedCursor),
                        string(item, "behavior")
                ).bounded(buffer.length()));
            }
            List<String> hints = new ArrayList<>();
            if (result.has("multilineHints") && result.get("multilineHints").isJsonArray()) {
                for (JsonElement element : result.getAsJsonArray("multilineHints")) {
                    if (element.isJsonPrimitive()) hints.add(element.getAsString());
                }
            }
            CompletionResult completion = new CompletionResult(
                    List.copyOf(suggestions),
                    string(result, "inlineHint"),
                    List.copyOf(hints)
            );
            completionCache.put(cacheKey, completion);
            return completion;
        } catch (RuntimeException exception) {
            failed("Minecraft command completion failed", exception);
            return CompletionResult.EMPTY;
        }
    }

    public synchronized CommandCheck check(String command) {
        if (command.length() > 64 * 1024 || !ensureReady()) return CommandCheck.UNAVAILABLE;
        String cacheKey = activeVersion + '\0' + command;
        CommandCheck cached = checkCache.get(cacheKey);
        if (cached != null) return cached;

        JsonObject params = new JsonObject();
        params.addProperty("command", command);
        try {
            JsonObject result = request("checkCommand", params, REQUEST_TIMEOUT).getAsJsonObject();
            CommandCheck check = new CommandCheck(
                    bool(result, "valid"),
                    string(result, "code"),
                    string(result, "message"),
                    true
            );
            checkCache.put(cacheKey, check);
            return check;
        } catch (RuntimeException exception) {
            failed("Minecraft command validation failed", exception);
            return CommandCheck.UNAVAILABLE;
        }
    }

    private boolean ensureReady() {
        if (project.isDisposed()) return false;
        String version = resolvedMinecraftVersion();
        if (isAlive() && version.equals(activeVersion)) return true;
        if (isAlive()) {
            try {
                configure(version);
                return true;
            } catch (RuntimeException exception) {
                failed("Unable to select Minecraft command profile " + version, exception);
                return false;
            }
        }
        if (System.nanoTime() < retryAfter) return false;

        RuntimeException lastFailure = null;
        Path serviceJar;
        try {
            serviceJar = McfppDatapackSandboxResource.extract();
        } catch (RuntimeException exception) {
            failed("Unable to load the bundled Datapack Sandbox service", exception);
            return false;
        }
        for (String javaExecutable : javaCandidates()) {
            try {
                start(javaExecutable, serviceJar);
                configure(version);
                return true;
            } catch (RuntimeException exception) {
                lastFailure = exception;
                stopProcess();
            }
        }
        failed(
                "Minecraft command support requires Java 25. Set MCFPP_DPS_JAVA or -Dmcfpp.dps.java.path to a Java 25 executable",
                lastFailure
        );
        return false;
    }

    private String resolvedMinecraftVersion() {
        long now = System.nanoTime();
        if (resolvedVersion == null || now >= versionRefreshAfter) {
            resolvedVersion = McfppMinecraftVersion.resolve(project);
            versionRefreshAfter = now + Duration.ofSeconds(1).toNanos();
        }
        return resolvedVersion;
    }

    private void start(String javaExecutable, Path serviceJar) {
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    javaExecutable,
                    "-Xmx384m",
                    "-Dfile.encoding=UTF-8",
                    "-jar",
                    serviceJar.toString(),
                    "serve"
            );
            Process startedProcess = builder.start();
            process = startedProcess;
            reader = new BufferedReader(new InputStreamReader(startedProcess.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(startedProcess.getOutputStream(), StandardCharsets.UTF_8));
            readerExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "mcfpp-dps-response");
                thread.setDaemon(true);
                return thread;
            });
            stderrTail = "";
            Thread stderrReader = new Thread(() -> drainStderr(startedProcess), "mcfpp-dps-stderr");
            stderrReader.setDaemon(true);
            stderrReader.start();

            JsonObject handshake = parseResponse(readLine(STARTUP_TIMEOUT));
            if (!bool(handshake, "ok")) throw responseFailure(handshake);
            JsonObject result = handshake.getAsJsonObject("result");
            if (result == null || !"dps-jsonl".equals(string(result, "protocol"))) {
                throw new IllegalStateException("Datapack Sandbox returned an incompatible handshake");
            }
            retryAfter = 0;
            LOG.info("Started Datapack Sandbox command service with " + javaExecutable);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start " + javaExecutable, exception);
        }
    }

    private void configure(String version) {
        JsonObject params = new JsonObject();
        params.addProperty("version", version);
        JsonElement result = request("createSandbox", params, STARTUP_TIMEOUT);
        if (!result.isJsonObject() || !version.equals(string(result.getAsJsonObject(), "version"))) {
            throw new IllegalStateException("Datapack Sandbox did not activate Minecraft profile " + version);
        }
        activeVersion = version;
        completionCache.clear();
        checkCache.clear();
    }

    private JsonElement request(String method, JsonObject params, Duration timeout) {
        if (!isAlive()) throw new IllegalStateException("Datapack Sandbox command service is not running");
        String id = Long.toString(++requestSequence);
        JsonObject request = new JsonObject();
        request.addProperty("id", id);
        request.addProperty("method", method);
        request.add("params", params);
        try {
            writer.write(request.toString());
            writer.newLine();
            writer.flush();
            JsonObject response = parseResponse(readLine(timeout));
            if (!id.equals(string(response, "id"))) {
                throw new IllegalStateException("Datapack Sandbox response id did not match request " + id);
            }
            if (!bool(response, "ok")) throw responseFailure(response);
            return response.get("result");
        } catch (IOException exception) {
            throw new IllegalStateException("Datapack Sandbox connection failed", exception);
        }
    }

    private String readLine(Duration timeout) {
        if (readerExecutor == null || reader == null) throw new IllegalStateException("Datapack Sandbox output is unavailable");
        Future<String> future = readerExecutor.submit(() -> reader.readLine());
        try {
            String line = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (line == null) throw new IllegalStateException("Datapack Sandbox stopped: " + stderrTail.trim());
            return line;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Datapack Sandbox", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Could not read Datapack Sandbox output", exception.getCause());
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new IllegalStateException("Datapack Sandbox did not respond within " + timeout.toSeconds() + " seconds", exception);
        }
    }

    private static JsonObject parseResponse(String line) {
        try {
            JsonElement parsed = JsonParser.parseString(line);
            if (!parsed.isJsonObject()) throw new IllegalStateException("response is not a JSON object");
            return parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Invalid Datapack Sandbox response", exception);
        }
    }

    private static IllegalStateException responseFailure(JsonObject response) {
        JsonObject error = response.has("error") && response.get("error").isJsonObject()
                ? response.getAsJsonObject("error") : new JsonObject();
        String code = string(error, "code");
        String message = string(error, "message");
        return new IllegalStateException((code.isEmpty() ? "Datapack Sandbox error" : code) +
                (message.isEmpty() ? "" : ": " + message));
    }

    private Set<String> javaCandidates() {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        addCandidate(candidates, System.getProperty("mcfpp.dps.java.path"));
        addCandidate(candidates, System.getenv("MCFPP_DPS_JAVA"));
        addJavaHome(candidates, System.getenv("JAVA_HOME"));
        candidates.add(isWindows() ? "java.exe" : "java");
        try {
            Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
            if (sdk != null) addJavaHome(candidates, sdk.getHomePath());
        } catch (RuntimeException exception) {
            LOG.debug("Project SDK is unavailable while locating Java 25", exception);
        }
        addJavaHome(candidates, System.getProperty("java.home"));
        return candidates;
    }

    private static void addJavaHome(Set<String> candidates, String home) {
        if (home == null || home.isBlank()) return;
        addCandidate(candidates, Path.of(home, "bin", isWindows() ? "java.exe" : "java").toString());
    }

    private static void addCandidate(Set<String> candidates, String candidate) {
        if (candidate != null && !candidate.isBlank()) candidates.add(candidate.trim());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private void drainStderr(Process sourceProcess) {
        try (BufferedReader stderr = new BufferedReader(new InputStreamReader(
                sourceProcess.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = stderr.readLine()) != null) {
                if (process == sourceProcess) {
                    String next = stderrTail + line + '\n';
                    stderrTail = next.length() <= 16 * 1024 ? next : next.substring(next.length() - 16 * 1024);
                }
            }
        } catch (IOException exception) {
            LOG.debug("Datapack Sandbox stderr stream closed", exception);
        }
    }

    private boolean isAlive() {
        return process != null && process.isAlive() && reader != null && writer != null;
    }

    private void failed(String message, RuntimeException exception) {
        stopProcess();
        retryAfter = System.nanoTime() + RETRY_DELAY_NANOS;
        String detail = exception == null || exception.getMessage() == null ? "" : ": " + exception.getMessage();
        String failure = message + detail;
        if (!failure.equals(lastLoggedFailure)) {
            lastLoggedFailure = failure;
            LOG.warn(failure, exception);
        }
    }

    private void stopProcess() {
        activeVersion = null;
        completionCache.clear();
        checkCache.clear();
        BufferedReader currentReader = reader;
        BufferedWriter currentWriter = writer;
        Process currentProcess = process;
        ExecutorService currentExecutor = readerExecutor;
        reader = null;
        writer = null;
        process = null;
        readerExecutor = null;
        if (currentProcess != null) {
            currentProcess.destroy();
            try {
                if (!currentProcess.waitFor(500, TimeUnit.MILLISECONDS)) currentProcess.destroyForcibly();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                currentProcess.destroyForcibly();
            }
        }
        try {
            if (currentWriter != null) currentWriter.close();
        } catch (IOException ignored) {
        }
        try {
            if (currentReader != null) currentReader.close();
        } catch (IOException ignored) {
        }
        if (currentExecutor != null) currentExecutor.shutdownNow();
    }

    @Override
    public synchronized void dispose() {
        stopProcess();
    }

    private static String string(JsonObject object, String member) {
        if (object == null || !object.has(member) || object.get(member).isJsonNull() ||
                !object.get(member).isJsonPrimitive()) return "";
        try {
            return object.get(member).getAsString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static boolean bool(JsonObject object, String member) {
        if (object == null || !object.has(member) || !object.get(member).isJsonPrimitive()) return false;
        try {
            return object.get(member).getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static int integer(JsonObject object, String member, int fallback) {
        if (object == null || !object.has(member) || !object.get(member).isJsonPrimitive()) return fallback;
        try {
            return object.get(member).getAsInt();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static <V> Map<String, V> lruCache() {
        return new LinkedHashMap<>(CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > CACHE_SIZE;
            }
        };
    }

    public record CompletionResult(
            @NotNull List<CompletionSuggestion> suggestions,
            @NotNull String inlineHint,
            @NotNull List<String> multilineHints
    ) {
        static final CompletionResult EMPTY = new CompletionResult(List.of(), "", List.of());
    }

    public record CompletionSuggestion(
            @NotNull String value,
            @NotNull String description,
            @NotNull String group,
            boolean appendSpace,
            int start,
            int end,
            @NotNull String behavior
    ) {
        CompletionSuggestion bounded(int length) {
            int boundedStart = Math.max(0, Math.min(start, length));
            int boundedEnd = Math.max(boundedStart, Math.min(end, length));
            return new CompletionSuggestion(value, description, group, appendSpace, boundedStart, boundedEnd, behavior);
        }
    }

    public record CommandCheck(boolean valid, @NotNull String code, @NotNull String message, boolean available) {
        static final CommandCheck UNAVAILABLE = new CommandCheck(true, "", "", false);

        public boolean syntaxError() {
            return available && !valid && (code.equals("INPUT_FORMAT") || code.equals("VERSION_MISMATCH"));
        }
    }
}
