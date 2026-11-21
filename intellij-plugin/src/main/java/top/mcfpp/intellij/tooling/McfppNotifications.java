package top.mcfpp.intellij.tooling;

import com.intellij.notification.*;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;

public final class McfppNotifications {
    private static final com.intellij.openapi.util.Key<String> LAST_ERROR=com.intellij.openapi.util.Key.create("mcfpp.last.error");
    private static final com.intellij.openapi.util.Key<Notification> RESOURCE_WARNING=com.intellij.openapi.util.Key.create("mcfpp.render.resources.warning");
    private McfppNotifications() {}
    public static void error(Project project, String message) {
        if (project.isDisposed()) return;
        if (message.equals(project.getUserData(LAST_ERROR))) return;
        project.putUserData(LAST_ERROR,message);
        NotificationGroupManager.getInstance().getNotificationGroup("MCFPP")
            .createNotification("MCFPP", message, NotificationType.ERROR)
            .addAction(NotificationAction.createSimple("Configure MCFPP", () ->
                ShowSettingsUtil.getInstance().showSettingsDialog(project, McfppSettingsConfigurable.class)))
            .notify(project);
    }
    public static void clear(Project project) { project.putUserData(LAST_ERROR,null); }
    /** Called off the UI thread: a game JAR may contain thousands of ZIP entries. */
    public static void checkRenderAssets(Project project,java.nio.file.Path root) {
        if(project.isDisposed()) return;
        String configured=McfppSettings.get(project).minecraftAssetsPath;
        String problem=McfppRenderAssets.problem(root,configured);
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
            if(project.isDisposed() || !configured.equals(McfppSettings.get(project).minecraftAssetsPath)) return;
            var previous=project.getUserData(RESOURCE_WARNING);
            if(problem==null) {
                if(previous!=null) previous.expire();
                project.putUserData(RESOURCE_WARNING,null);
            } else if(previous==null || previous.isExpired()) {
                var notification=NotificationGroupManager.getInstance().getNotificationGroup("MCFPP")
                    .createNotification("MCFPP render resources missing",problem,NotificationType.WARNING)
                    .addAction(NotificationAction.createSimple("Choose Minecraft JAR…",() ->
                        ShowSettingsUtil.getInstance().showSettingsDialog(project,McfppSettingsConfigurable.class,
                            McfppSettingsConfigurable::focusMinecraftAssets)));
                project.putUserData(RESOURCE_WARNING,notification);
                notification.notify(project);
            }
        },project.getDisposed());
    }
}

