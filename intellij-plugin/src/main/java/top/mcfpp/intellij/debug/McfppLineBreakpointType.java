package top.mcfpp.intellij.debug;

import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.breakpoints.XLineBreakpointType;
import org.jetbrains.annotations.NotNull;

public final class McfppLineBreakpointType extends XLineBreakpointType<McfppBreakpointProperties> {
    public McfppLineBreakpointType() { super("mcfpp.line", "MCFPP line breakpoint"); }
    @Override public boolean canPutAt(@NotNull VirtualFile file, int line, @NotNull Project project) {
        if (!"mcfpp".equals(file.getExtension())) return false;
        var document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null || line >= document.getLineCount()) return false;
        String text = document.getImmutableCharSequence().subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line)).toString().trim();
        return !text.isEmpty() && !text.startsWith("//") && !text.equals("}");
    }
    @Override public McfppBreakpointProperties createBreakpointProperties(@NotNull VirtualFile file, int line) { return new McfppBreakpointProperties(); }
}
