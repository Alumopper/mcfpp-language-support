package top.mcfpp.intellij.lsp;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.platform.lsp.api.LspServerManager;
import com.intellij.platform.lsp.api.LspServerState;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import top.mcfpp.intellij.mni.McfppRemoteImplementationLookup;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public final class McfppLspImplementationLookup implements McfppRemoteImplementationLookup {
    private final Project project;

    public McfppLspImplementationLookup(Project project) {
        this.project = project;
    }

    @Override
    public List<PsiElement> find(PsiElement call) {
        // The IDE runs implementation searches with cancellable background progress.
        // A synchronous PSI query invoked on the EDT or under a read lock can only use local targets.
        var application = ApplicationManager.getApplication();
        if (application.isDispatchThread() || application.isReadAccessAllowed() || project.isDisposed()) return List.of();
        var usage = ReadAction.compute(() -> usageAt(call));
        if (usage == null) return List.of();
        for (var server : LspServerManager.getInstance(project).getServersForProvider(McfppLspIntegrationProvider.class)) {
            if (server.getState() != LspServerState.Running) continue;
            var params = new ImplementationParams(server.getDocumentIdentifier(usage.file()), usage.position());
            var reply = server.sendRequestSync(3_000, remote -> remote instanceof McfppExtendedServer extended
                    ? extended.getTextDocumentService().implementation(params)
                    : java.util.concurrent.CompletableFuture.completedFuture(null));
            if (reply == null) continue;
            Stream<PsiElement> targets;
            if (reply.isLeft()) {
                targets = reply.getLeft().stream().map(this::target);
            } else if (reply.isRight()) {
                targets = reply.getRight().stream()
                        .map(link -> target(new Location(link.getTargetUri(), link.getTargetSelectionRange())));
            } else {
                continue;
            }
            return targets.filter(Objects::nonNull).distinct().toList();
        }
        return List.of();
    }

    private static Usage usageAt(PsiElement call) {
        if (!call.isValid()) return null;
        var file = call.getContainingFile().getVirtualFile();
        if (file == null) return null;
        var document = FileDocumentManager.getInstance().getDocument(file);
        int offset = call.getTextOffset();
        if (document == null || offset >= document.getTextLength()) return null;
        int line = document.getLineNumber(offset);
        return new Usage(file, new Position(line, offset - document.getLineStartOffset(line)));
    }

    private PsiElement target(Location location) {
        var vfs = VirtualFileManager.getInstance();
        var file = vfs.findFileByUrl(location.getUri());
        if (file == null) file = vfs.refreshAndFindFileByUrl(location.getUri());
        if (file == null || project.isDisposed()) return null;
        VirtualFile targetFile = file;
        return ReadAction.compute(() -> elementAt(project, targetFile, location.getRange()));
    }

    static PsiElement elementAt(Project project, VirtualFile file, Range range) {
        var psi = PsiManager.getInstance(project).findFile(file);
        if (psi == null || range == null) return null;
        var document = PsiDocumentManager.getInstance(project).getDocument(psi);
        var start = range.getStart();
        if (document == null || start.getLine() < 0 || start.getLine() >= document.getLineCount()) return null;
        int lineStart = document.getLineStartOffset(start.getLine());
        if (start.getCharacter() < 0 || start.getCharacter() >= document.getLineEndOffset(start.getLine()) - lineStart) return null;
        return psi.findElementAt(lineStart + start.getCharacter());
    }

    private record Usage(VirtualFile file, Position position) {}
}
