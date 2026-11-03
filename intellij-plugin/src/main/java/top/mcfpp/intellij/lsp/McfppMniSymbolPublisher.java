package top.mcfpp.intellij.lsp;
import com.intellij.openapi.project.*;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.util.Disposer;
import com.intellij.platform.lsp.api.LspServerManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.util.concurrency.AppExecutorUtil;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.mni.McfppMacroIndex;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public final class McfppMniSymbolPublisher implements ProjectActivity {
    @Override public Object execute(@NotNull Project project,@NotNull Continuation<? super Unit> continuation) {
        var javaPlugin=com.intellij.ide.plugins.PluginManagerCore.getPlugin(com.intellij.openapi.extensions.PluginId.getId("com.intellij.java"));
        if(javaPlugin==null || !javaPlugin.isEnabled()) return Unit.INSTANCE;
        AtomicReference<ScheduledFuture<?>> pending=new AtomicReference<>();
        Runnable schedule=() -> {
            if(project.isDisposed()) return;
            var previous=pending.getAndSet(AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
                if(project.isDisposed()) return;
                ReadAction.nonBlocking(() -> McfppMacroIndex.snapshot(project)).inSmartMode(project).expireWith(project)
                    .coalesceBy(McfppMniSymbolPublisher.class,project)
                    .finishOnUiThread(com.intellij.openapi.application.ModalityState.any(),snapshot -> {
                        for(var server:LspServerManager.getInstance(project).getServersForProvider(McfppLspIntegrationProvider.class))
                            if(server.getState()==com.intellij.platform.lsp.api.LspServerState.Running)
                                server.sendNotification(remote -> { if(remote instanceof McfppExtendedServer extended) extended.updateMniSymbols(snapshot); return Unit.INSTANCE; });
                    }).submit(AppExecutorUtil.getAppExecutorService());
            },400,TimeUnit.MILLISECONDS));
            if(previous!=null) previous.cancel(false);
        };
        project.getMessageBus().connect().subscribe(PsiModificationTracker.TOPIC,(PsiModificationTracker.Listener)schedule::run);
        project.getMessageBus().connect().subscribe(com.intellij.ProjectTopics.PROJECT_ROOTS,new com.intellij.openapi.roots.ModuleRootListener() {
            @Override public void rootsChanged(@NotNull com.intellij.openapi.roots.ModuleRootEvent event) { schedule.run(); }
        });
        LspServerManager.getInstance(project).addLspServerManagerListener(new com.intellij.platform.lsp.api.LspServerManagerListener() {
            @Override public void serverStateChanged(@NotNull com.intellij.platform.lsp.api.LspServer server) {
                if(server.getProviderClass()==McfppLspIntegrationProvider.class && server.getState()==com.intellij.platform.lsp.api.LspServerState.Running) schedule.run();
            }
        },project,true);
        Disposer.register(project,() -> { var future=pending.get(); if(future!=null) future.cancel(false); });
        DumbService.getInstance(project).runWhenSmart(schedule);
        return Unit.INSTANCE;
    }
}

