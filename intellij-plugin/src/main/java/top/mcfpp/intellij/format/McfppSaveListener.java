package top.mcfpp.intellij.format;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.ProjectLocator;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.tooling.*;

public final class McfppSaveListener implements FileDocumentManagerListener {
    private static final Key<Boolean> SAVING=Key.create("mcfpp.formatted.save");
    @Override public void beforeDocumentSaving(@NotNull Document document) {
        if(Boolean.TRUE.equals(document.getUserData(SAVING))) return;
        var file=FileDocumentManager.getInstance().getFile(document);
        if(file==null || !"mcfpp".equalsIgnoreCase(file.getExtension())) return;
        var project=ProjectLocator.getInstance().guessProjectForFile(file);
        if(project==null || project.isDisposed()) return;
        var settings=McfppSettings.get(project);
        if(!settings.formatOnSave && !settings.fixOnSave) return;
        String source=document.getText(); long stamp=document.getModificationStamp();
        var editors=EditorFactory.getInstance().getEditors(document,project);
        int cursor=editors.length==0 ? 0 : editors[0].getCaretModel().getOffset();
        String operation=settings.formatOnSave ? "format" : "fix";
        boolean fix=settings.fixOnSave;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                var response=project.getService(McfppNodeService.class).request(operation,file.getPath(),source,fix,cursor);
                String formatted=response.get("source").getAsString();
                if(source.equals(formatted)) return;
                ApplicationManager.getApplication().invokeLater(() -> {
                    if(project.isDisposed() || document.getModificationStamp()!=stamp || !file.isValid()) return;
                    document.putUserData(SAVING,true);
                    try {
                        WriteCommandAction.runWriteCommandAction(project,"Format MCFPP on save",null,() -> replace(document,formatted));
                        if(response.has("cursorOffset") && editors.length>0 && !editors[0].isDisposed() && !editors[0].getSelectionModel().hasSelection())
                            editors[0].getCaretModel().moveToOffset(Math.min(formatted.length(),response.get("cursorOffset").getAsInt()));
                        FileDocumentManager.getInstance().saveDocument(document);
                    } finally { document.putUserData(SAVING,null); }
                },project.getDisposed());
            } catch(Exception error) { McfppNotifications.error(project,error.getMessage()); }
        });
    }
    public static void replace(Document document,String replacement) {
        String old=document.getText(); int start=0, end=old.length(), newEnd=replacement.length();
        while(start<end && start<newEnd && old.charAt(start)==replacement.charAt(start)) start++;
        while(end>start && newEnd>start && old.charAt(end-1)==replacement.charAt(newEnd-1)) { end--; newEnd--; }
        document.replaceString(start,end,replacement.substring(start,newEnd));
    }
}

