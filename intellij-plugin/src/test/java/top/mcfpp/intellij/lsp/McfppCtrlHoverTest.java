package top.mcfpp.intellij.lsp;

import com.intellij.codeInsight.navigation.CtrlMouseAction;
import com.intellij.codeInsight.navigation.CtrlMouseData;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.ServiceContainerUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import top.mcfpp.intellij.mni.McfppJavaFixtureTestCase;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class McfppCtrlHoverTest extends McfppJavaFixtureTestCase {
    public void testActualGotoActionProvidesCtrlHoverRangesForServerOnlyMembers() throws Exception {
        var declaration = myFixture.addFileToProject("collections.mcfpp",
                "func list<E>.map<E, R>(transform: (E) -> R): list<R> = api.CollectionMacros.mapList");
        var implementation = myFixture.addFileToProject("ReferenceReadCallbacks.kt",
                "object ReferenceReadCallbacks { fun tryRead() {} }");
        var targets = Map.of(
                "map", declaration.findElementAt(declaration.getText().indexOf(".map") + 1),
                "tryRead", implementation.findElementAt(implementation.getText().indexOf("tryRead")));
        var lookedUp = new ArrayList<String>();
        ServiceContainerUtil.registerOrReplaceServiceInstance(getProject(), McfppLspDefinitionLookup.class,
                new McfppLspDefinitionLookup(getProject()) {
                    @Override
                    PsiElement find(PsiElement usage) {
                        lookedUp.add(usage.getText());
                        return targets.get(usage.getText());
                    }
                }, getTestRootDisposable());
        var file = myFixture.configureByText("main.mcfpp", """
                func main(values: list<int>, ref: ScoreRef) {
                    var value = 3
                    values.map { it * value }
                    ref.tryRead { read -> print(read) }
                    /data modify storage showcase:inputs values set value [3,4,6]
                }
                """);
        var action = (CtrlMouseAction) ActionManager.getInstance().getAction(IdeActions.ACTION_GOTO_DECLARATION);
        var editor = myFixture.getEditor();
        for (String name : List.of("map", "tryRead")) {
            int offset = file.getText().indexOf("." + name) + 1;
            var hover = hoverAt(action, editor, file, offset + 1);
            assertNotNull(name, hover);
            assertTrue(name, hover.isNavigatable());
            assertEquals(List.of(TextRange.from(offset, name.length())), hover.getRanges());
        }
        int localOffset = file.getText().indexOf("* value") + 2;
        var localHover = hoverAt(action, editor, file, localOffset + 1);
        assertNotNull(localHover);
        assertTrue(localHover.isNavigatable());
        int commandOffset = file.getText().lastIndexOf("value") + 1;
        var commandHover = hoverAt(action, editor, file, commandOffset);
        assertTrue(commandHover == null || !commandHover.isNavigatable());
        assertEquals(List.of("map", "tryRead"), lookedUp);
    }

    private static CtrlMouseData hoverAt(CtrlMouseAction action, Editor editor, PsiFile file, int offset) throws Exception {
        return PlatformTestUtil.waitForFuture(ReadAction.nonBlocking(() -> action.getCtrlMouseData(editor, file, offset))
                .submit(AppExecutorUtil.getAppExecutorService()));
    }
}
