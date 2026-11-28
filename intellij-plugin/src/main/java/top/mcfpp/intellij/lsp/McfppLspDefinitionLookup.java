package top.mcfpp.intellij.lsp;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.platform.lsp.api.LspServerManager;
import com.intellij.platform.lsp.api.LspServerState;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

public class McfppLspDefinitionLookup {
    private static final int REQUEST_TIMEOUT_MS = 1_000;
    private final Project project;

    public McfppLspDefinitionLookup(Project project) {
        this.project = project;
    }

    PsiElement find(PsiElement usage) {
        // Ctrl-hover and goto run this lookup in a cancellable background read action.
        if (ApplicationManager.getApplication().isDispatchThread() || project.isDisposed()) return null;
        var file = usage.getContainingFile();
        var virtual = file.getVirtualFile();
        var documents = PsiDocumentManager.getInstance(project);
        var document = documents.getDocument(file);
        if (virtual == null || !virtual.isInLocalFileSystem() || document == null || !documents.isCommitted(document)) return null;
        var source = usage.getTextRange();
        int line = document.getLineNumber(source.getStartOffset());
        int lineStart = document.getLineStartOffset(line);
        var origin = new Range(new Position(line, source.getStartOffset() - lineStart),
                new Position(line, source.getEndOffset() - lineStart));

        for (var server : LspServerManager.getInstance(project).getServersForProvider(McfppLspIntegrationProvider.class)) {
            if (server.getState() != LspServerState.Running) continue;
            var params = new DefinitionParams(server.getDocumentIdentifier(virtual), origin.getStart());
            var reply = server.sendRequestSync(REQUEST_TIMEOUT_MS, remote -> remote.getTextDocumentService().definition(params));
            if (reply == null) continue;
            Location target;
            if (reply.isRight() && reply.getRight().size() == 1) {
                var link = reply.getRight().getFirst();
                if (!origin.equals(link.getOriginSelectionRange())) continue;
                target = new Location(link.getTargetUri(), link.getTargetSelectionRange());
            } else if (reply.isLeft() && reply.getLeft().size() == 1) {
                target = reply.getLeft().getFirst();
            } else {
                continue;
            }
            var targetFile = server.getDescriptor().findFileByUri(target.getUri());
            if (targetFile == null) continue;
            var element = McfppLspImplementationLookup.elementAt(project, targetFile, target.getRange());
            if (element != null && !element.isEquivalentTo(usage)) return element;
        }
        return null;
    }
}
