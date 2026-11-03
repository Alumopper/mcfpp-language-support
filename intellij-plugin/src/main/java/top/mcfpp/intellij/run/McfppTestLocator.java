package top.mcfpp.intellij.run;

import com.intellij.execution.Location;
import com.intellij.execution.PsiLocation;
import com.intellij.execution.testframework.sm.runner.SMTestLocator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

public final class McfppTestLocator implements SMTestLocator {
    @Override public @NotNull List<Location> getLocation(@NotNull String protocol,@NotNull String path,
            @NotNull Project project,@NotNull GlobalSearchScope scope) {
        if(!protocol.equals("mcfpp")) return List.of();
        try {
            int colon=path.lastIndexOf(':');
            int line=Integer.parseInt(path.substring(colon+1));
            var target=Path.of(URI.create("file://"+path.substring(0,colon)));
            var virtual=LocalFileSystem.getInstance().findFileByNioFile(target);
            var file=virtual==null ? null : PsiManager.getInstance(project).findFile(virtual);
            if(file==null) return List.of();
            var document=PsiDocumentManager.getInstance(project).getDocument(file);
            var element=document!=null && line>0 && line<=document.getLineCount() ? file.findElementAt(document.getLineStartOffset(line-1)) : file;
            return List.of(PsiLocation.fromPsiElement(element==null ? file : element));
        } catch(RuntimeException ignored) { return List.of(); }
    }
}
