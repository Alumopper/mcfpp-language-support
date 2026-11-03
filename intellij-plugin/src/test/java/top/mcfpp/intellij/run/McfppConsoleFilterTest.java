package top.mcfpp.intellij.run;

import com.intellij.execution.filters.OpenFileHyperlinkInfo;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public final class McfppConsoleFilterTest extends BasePlatformTestCase {
    public void testTimestampedCompilerErrorLinksHeaderAndSourceLine() throws Exception {
        var path=java.nio.file.Files.createTempFile("mcfpp-console-", ".mcfpp");
        java.nio.file.Files.writeString(path,"func first() {}\nfunc main() { missing(); }\n");
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(getTestRootDisposable(),path.toString());
        var source=com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        assertNotNull(source);
        try {
        var filter=new McfppConsoleFilter(getProject());
        String header="[ERROR/LogProcessor](07:58:20,678): Error while compiling "+path+">>\n";
        var headerResult=filter.applyFilter(header,header.length());
        assertNotNull(headerResult);
        assertInstanceOf(headerResult.getResultItems().getFirst().getHyperlinkInfo(),OpenFileHyperlinkInfo.class);
        String excerpt="2 | func main() { missing(); }\n";
        var result=filter.applyFilter(excerpt,header.length()+excerpt.length());
        assertNotNull(result);
        result.getResultItems().getFirst().getHyperlinkInfo().navigate(getProject());
        var editor=FileEditorManager.getInstance(getProject()).getSelectedTextEditor();
        assertNotNull(editor);
        assertEquals(1,editor.getCaretModel().getLogicalPosition().line);
        assertEquals(java.nio.file.Files.readString(path),editor.getDocument().getText());
        } finally {
            FileEditorManager.getInstance(getProject()).closeFile(source);
            java.nio.file.Files.deleteIfExists(path);
        }
    }
}
