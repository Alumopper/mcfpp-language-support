package top.mcfpp.intellij.tooling;

import com.intellij.notification.*;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;

public final class McfppNotifications {
    private static final com.intellij.openapi.util.Key<String> LAST_ERROR=com.intellij.openapi.util.Key.create("mcfpp.last.error");
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
}

