package top.mcfpp.intellij.lsp;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.lang.Language;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootEvent;
import com.intellij.openapi.roots.ModuleRootListener;
import com.intellij.platform.lsp.api.LspServer;
import com.intellij.platform.lsp.api.LspServerManager;
import com.intellij.platform.lsp.api.LspServerManagerListener;
import com.intellij.platform.lsp.api.LspServerState;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.util.concurrency.AppExecutorUtil;
import kotlin.Unit;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.mni.McfppMacroIndex;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Project-level Disposable service owning MNI symbol publishing to the MCFPP LSP server.
 * Filters PSI notifications so edits in non-JVM files (such as .mcfpp) never trigger Java PSI rescans.
 */
@Service(Service.Level.PROJECT)
public final class McfppMniSymbolPublisherService implements Disposable {
    private final Project project;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicReference<ScheduledFuture<?>> pending = new AtomicReference<>();
    private final AtomicLong lastJvmModCount = new AtomicLong(-1L);

    public McfppMniSymbolPublisherService(@NotNull Project project) {
        this.project = project;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) return;
        var javaPlugin = PluginManagerCore.getPlugin(PluginId.getId("com.intellij.java"));
        if (javaPlugin == null || !javaPlugin.isEnabled()) return;

        var connection = project.getMessageBus().connect(this);
        connection.subscribe(PsiModificationTracker.TOPIC, (PsiModificationTracker.Listener) () -> {
            long current = currentJvmModificationCount();
            if (current != lastJvmModCount.get()) {
                schedule(false);
            }
        });
        connection.subscribe(com.intellij.ProjectTopics.PROJECT_ROOTS, new ModuleRootListener() {
            @Override
            public void rootsChanged(@NotNull ModuleRootEvent event) {
                schedule(true);
            }
        });
        LspServerManager.getInstance(project).addLspServerManagerListener(new LspServerManagerListener() {
            @Override
            public void serverStateChanged(@NotNull LspServer server) {
                if (server.getProviderClass() == McfppLspIntegrationProvider.class &&
                        server.getState() == LspServerState.Running) {
                    schedule(true);
                }
            }
        }, this, true);
        DumbService.getInstance(project).runWhenSmart(() -> schedule(true));
    }

    private long currentJvmModificationCount() {
        PsiModificationTracker tracker = PsiModificationTracker.getInstance(project);
        long count = 0L;
        Language javaLang = Language.findLanguageByID("JAVA");
        if (javaLang != null) {
            count += tracker.forLanguage(javaLang).getModificationCount();
        }
        Language kotlinLang = Language.findLanguageByID("kotlin");
        if (kotlinLang != null) {
            count += tracker.forLanguage(kotlinLang).getModificationCount();
        }
        return count;
    }

    private void schedule(boolean force) {
        if (project.isDisposed()) return;
        var previous = pending.getAndSet(AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
            if (project.isDisposed()) return;
            long modCount = currentJvmModificationCount();
            if (!force && !lastJvmModCount.compareAndSet(lastJvmModCount.get(), modCount)) {
                return;
            }
            lastJvmModCount.set(modCount);
            ReadAction.nonBlocking(() -> McfppMacroIndex.snapshot(project))
                    .inSmartMode(project)
                    .expireWith(this)
                    .coalesceBy(McfppMniSymbolPublisherService.class, project)
                    .finishOnUiThread(ModalityState.any(), snapshot -> {
                        if (project.isDisposed()) return;
                        for (var server : LspServerManager.getInstance(project)
                                .getServersForProvider(McfppLspIntegrationProvider.class)) {
                            if (server.getState() == LspServerState.Running) {
                                server.sendNotification(remote -> {
                                    if (remote instanceof McfppExtendedServer extended) {
                                        extended.updateMniSymbols(snapshot);
                                    }
                                    return Unit.INSTANCE;
                                });
                            }
                        }
                    }).submit(AppExecutorUtil.getAppExecutorService());
        }, 400, TimeUnit.MILLISECONDS));
        if (previous != null) previous.cancel(false);
    }

    @Override
    public void dispose() {
        var future = pending.getAndSet(null);
        if (future != null) future.cancel(false);
    }
}
