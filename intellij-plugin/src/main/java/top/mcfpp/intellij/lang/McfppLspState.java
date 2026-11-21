package top.mcfpp.intellij.lang;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Collection;

/**
 * Safely checks whether the MCFPP LSP server is actively running for a project without hard-linking
 * against com.intellij.modules.lsp (so Community Edition and unit tests work seamlessly).
 */
public final class McfppLspState {
    private static volatile boolean initialized;
    private static Class<?> providerClass;
    private static Method getInstanceMethod;
    private static Method getServersMethod;
    private static Method getStateMethod;

    private McfppLspState() {}

    public static boolean isRunning(@Nullable Project project) {
        if (project == null || project.isDisposed() || ApplicationManager.getApplication().isUnitTestMode()) {
            return false;
        }
        ensureInitialized();
        if (getInstanceMethod == null || getServersMethod == null || getStateMethod == null || providerClass == null) {
            return false;
        }
        try {
            Object manager = getInstanceMethod.invoke(null, project);
            if (manager == null) return false;
            Object servers = getServersMethod.invoke(manager, providerClass);
            if (!(servers instanceof Collection<?> collection)) return false;
            for (Object server : collection) {
                if (server == null) continue;
                Object state = getStateMethod.invoke(server);
                if (state != null && "Running".equals(state.toString())) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void ensureInitialized() {
        if (initialized) return;
        synchronized (McfppLspState.class) {
            if (initialized) return;
            try {
                Class<?> managerClass = Class.forName("com.intellij.platform.lsp.api.LspServerManager");
                Class<?> serverClass = Class.forName("com.intellij.platform.lsp.api.LspServer");
                providerClass = Class.forName("top.mcfpp.intellij.lsp.McfppLspIntegrationProvider");
                getInstanceMethod = managerClass.getMethod("getInstance", Project.class);
                getServersMethod = managerClass.getMethod("getServersForProvider", Class.class);
                getStateMethod = serverClass.getMethod("getState");
            } catch (Throwable ignored) {
                // com.intellij.modules.lsp is optional and absent in Community Edition.
            } finally {
                initialized = true;
            }
        }
    }
}
