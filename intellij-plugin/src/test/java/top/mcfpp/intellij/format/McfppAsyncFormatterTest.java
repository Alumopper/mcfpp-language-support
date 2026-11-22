package top.mcfpp.intellij.format;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixture4TestCase;
import org.junit.Test;
import top.mcfpp.intellij.lang.McfppFileType;
import top.mcfpp.intellij.tooling.McfppSettings;
import static org.junit.Assert.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class McfppAsyncFormatterTest extends LightPlatformCodeInsightFixture4TestCase {
    @Test
    public void unavailableNodeUsesTheBuiltInPsiFormatter() throws Exception {
        String oldFormatterPath = System.getProperty("mcfpp.formatter.path");
        Path formatterScript = Files.createTempFile("mcfpp-missing-node", ".mjs");
        McfppSettings.get(getProject()).nodePath = Path.of(getProject().getBasePath(), "missing-node").toString();
        System.setProperty("mcfpp.formatter.path", formatterScript.toString());
        PsiFile file = myFixture.configureByText(McfppFileType.INSTANCE, "func run(){\nvar value:int=1\n}");

        try {
            McfppNodeService service = getProject().getService(McfppNodeService.class);
            assertThrows(McfppNodeService.FormatterUnavailableException.class,
                    () -> service.request("format", file.getVirtualFile().getPath(), file.getText(), true, 0));
            assertFalse(new McfppAsyncFormatter().canFormat(file));
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                CodeStyleManager.getInstance(getProject()).reformat(file);
            });

            assertEquals("func run() {\n    var value: int = 1\n}", file.getText());
        } finally {
            if (oldFormatterPath == null) System.clearProperty("mcfpp.formatter.path");
            else System.setProperty("mcfpp.formatter.path", oldFormatterPath);
            Files.deleteIfExists(formatterScript);
        }
    }

    @Test
    public void saveFormattingIsNonBlockingAndDropsStaleResults() throws Exception {
        PsiFile file = myFixture.configureByText(McfppFileType.INSTANCE, "func run() {}\n");
        Document document = PsiDocumentManager.getInstance(getProject()).getDocument(file);
        assertTrue(document != null);
        McfppSettings.get(getProject()).formatOnSave = true;
        McfppSettings.get(getProject()).fixOnSave = false;
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch allowResponse = new CountDownLatch(1);
        CountDownLatch responseReturned = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        McfppSaveListener listener = new McfppSaveListener((project, operation, path, source, fix, cursor) -> {
            requests.incrementAndGet();
            requestStarted.countDown();
            try {
                if (!allowResponse.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("Test formatter timed out");
                JsonObject response = new JsonObject();
                response.addProperty("source", source + "// formatted\n");
                return response;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("Test formatter interrupted", interrupted);
            } finally {
                responseReturned.countDown();
            }
        });

        long initialStamp = document.getModificationStamp();
        long startedAt = System.nanoTime();
        ApplicationManager.getApplication().invokeAndWait(() -> listener.beforeDocumentSaving(document));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        assertTrue("Save callback blocked for " + elapsedMillis + "ms", elapsedMillis < 1000);
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS));
        ApplicationManager.getApplication().invokeAndWait(() -> listener.beforeDocumentSaving(document));
        assertEquals(1, requests.get());

        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.insertString(document.getTextLength(), "// changed\n"));
        assertFalse(McfppSaveListener.isCurrent(getProject(), FileDocumentManager.getInstance().getFile(document),
                document, initialStamp));
        allowResponse.countDown();
        assertTrue(responseReturned.await(2, TimeUnit.SECONDS));
        assertEquals("func run() {}\n// changed\n", document.getText());
    }
}
