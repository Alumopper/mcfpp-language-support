package top.mcfpp.intellij.mni;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.testFramework.IndexingTestUtil;
import com.intellij.testFramework.DumbModeTestUtils;
import top.mcfpp.intellij.lang.McfppFileType;
import top.mcfpp.intellij.lang.McfppLanguage;
import top.mcfpp.intellij.lang.McfppSymbolIndex;
import top.mcfpp.intellij.lang.McfppTokenTypes;

import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

public final class McfppMniReferenceIntegrationTest extends LightJavaCodeInsightFixtureTestCase {
    public void testJavaOptionalDescriptorRegistersMniReferences() {
        myFixture.addClass("""
                package example.mni;
                public final class Bridge {
                    public static void invoke() {}
                }
                """);
        myFixture.configureByText(
                McfppFileType.INSTANCE,
                "func invoke() = example.mni.Bri<caret>dge.invoke;"
        );

        assertTrue(
                "The Java optional plugin descriptor did not register the MNI contributor",
                PsiReferenceContributor.EP_NAME.getExtensionList().stream()
                        .anyMatch(extension -> extension.getInstance() instanceof McfppMniReferenceContributor)
        );
        PsiElement identifier = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
        assertNotNull(identifier);
        assertEquals("Bridge", identifier.getText());
        assertSame(McfppTokenTypes.IDENTIFIER, identifier.getNode().getElementType());
        assertSame(McfppLanguage.INSTANCE, identifier.getLanguage());
        assertEquals(1, McfppMniReferenceContributor.referencesFor(identifier).length);
        assertEquals(1, ReferenceProvidersRegistry.getReferencesFromProviders(identifier).length);

        PsiReference reference = myFixture.getReferenceAtCaretPosition();

        assertNotNull(reference);
        PsiClass resolvedClass = assertInstanceOf(reference.resolve(), PsiClass.class);
        assertEquals("example.mni.Bridge", resolvedClass.getQualifiedName());
    }

    public void testMethodReferenceSelectsAnnotatedMcfppOverload() {
        myFixture.addClass("""
                package top.mcfpp.annotations;
                public @interface MNIFunction {
                    String[] readOnlyParams() default {};
                    String[] normalParams() default {};
                }
                """);
        myFixture.addClass("""
                package example.mni;
                import top.mcfpp.annotations.MNIFunction;
                public final class Bridge {
                    @MNIFunction(normalParams = {"int"})
                    public static void convert(int value) {}
                    @MNIFunction(normalParams = {"string"})
                    public static void convert(String value) {}
                }
                """);
        myFixture.configureByText(
                McfppFileType.INSTANCE,
                "func convert(value as string) = example.mni.Bridge.con<caret>vert;"
        );

        PsiReference reference = myFixture.getReferenceAtCaretPosition();

        assertNotNull(reference);
        PsiMethod method = assertInstanceOf(reference.resolve(), PsiMethod.class);
        assertEquals("String", method.getParameterList().getParameter(0).getType().getPresentableText());
    }

    public void testMniResolvesJavaSourceWhenTheProjectClassIndexIsUnavailable() throws Exception {
        Path temporaryDirectory = Files.createTempDirectory("mcfpp-unindexed-mni-");
        Path source = temporaryDirectory.resolve("src/main/java/external/mni/UnindexedBridge.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package external.mni;
                public final class UnindexedBridge {
                    public static void invoke() {}
                }
                """);
        String previous = System.getProperty("mcfpp.sources.path");
        try {
            System.setProperty("mcfpp.sources.path", temporaryDirectory.toString());
            assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(temporaryDirectory));
            myFixture.configureByText(
                    McfppFileType.INSTANCE,
                    "func invoke() = external.mni.UnindexedBridge.inv<caret>oke;"
            );

            PsiReference reference = myFixture.getReferenceAtCaretPosition();
            assertNotNull(reference);
            PsiMethod method = assertInstanceOf(reference.resolve(), PsiMethod.class);
            assertEquals("invoke", method.getName());
            assertEquals(source.toString().replace('\\', '/'),
                    method.getContainingFile().getVirtualFile().getPath());
        } finally {
            if (previous == null) System.clearProperty("mcfpp.sources.path");
            else System.setProperty("mcfpp.sources.path", previous);
            FileUtil.delete(temporaryDirectory.toFile());
        }
    }

    public void testNativeDeclarationNameNavigatesDirectlyToJavaImplementation() {
        myFixture.addClass("""
                package top.mcfpp.annotations;
                public @interface MNIFunction {
                    String[] readOnlyParams() default {};
                    String[] normalParams() default {};
                }
                """);
        myFixture.addClass("""
                package example.mni;
                import top.mcfpp.annotations.MNIFunction;
                public final class ProjectMni {
                    @MNIFunction(normalParams = {"string"})
                    public static void announce(String message) {}
                }
                """);
        myFixture.configureByText(
                McfppFileType.INSTANCE,
                "func ann<caret>ounce(message as string) = example.mni.ProjectMni.announce;"
        );

        PsiElement declaration = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
        assertNotNull(declaration);
        PsiElement[] targets = new McfppMniGotoDeclarationHandler().getGotoDeclarationTargets(
                declaration, myFixture.getCaretOffset(), myFixture.getEditor());

        assertNotNull("The MCFPP native declaration name must expose its Java binding", targets);
        assertEquals(1, targets.length);
        PsiMethod method = assertInstanceOf(targets[0], PsiMethod.class);
        assertEquals("announce", method.getName());
        assertEquals(List.of(method), McfppMniLineMarkerProvider.findJavaTargets(declaration));
        assertTrue("The Java optional descriptor must register the real Ctrl+B handler",
                GotoDeclarationHandler.EP_NAME.getExtensionList().stream()
                        .anyMatch(McfppMniGotoDeclarationHandler.class::isInstance));

        int adjustedOffset = TargetElementUtil.adjustOffset(
                myFixture.getFile(), myFixture.getEditor().getDocument(), myFixture.getCaretOffset());
        PsiElement actionSource = myFixture.getFile().findElementAt(adjustedOffset);
        assertNotNull(actionSource);
        assertEquals("The real action must address the MCFPP declaration leaf", "announce", actionSource.getText());
        assertSame(McfppLanguage.INSTANCE, actionSource.getLanguage());
        GotoDeclarationHandler winningHandler = null;
        PsiElement[] winningTargets = null;
        for (GotoDeclarationHandler handler : GotoDeclarationHandler.EP_NAME.getExtensionList()) {
            PsiElement[] candidateTargets = handler.getGotoDeclarationTargets(
                    actionSource, myFixture.getCaretOffset(), myFixture.getEditor());
            if (candidateTargets != null && candidateTargets.length > 0) {
                winningHandler = handler;
                winningTargets = candidateTargets;
                break;
            }
        }
        assertNotNull("No registered Ctrl+B provider accepted the declaration", winningHandler);
        assertTrue("An earlier provider hijacked MCFPP declaration navigation: " +
                        winningHandler.getClass().getName() + " -> " + java.util.Arrays.toString(winningTargets),
                winningHandler instanceof McfppMniGotoDeclarationHandler);

        assertEquals(myFixture.getFile().getVirtualFile(),
                FileEditorManager.getInstance(getProject()).getSelectedFiles()[0]);
        myFixture.performEditorAction(IdeActions.ACTION_GOTO_DECLARATION);
        assertEquals("The real Ctrl+B action must select the Java implementation",
                "ProjectMni.java",
                FileEditorManager.getInstance(getProject()).getSelectedFiles()[0].getName());
    }

    public void testRealCtrlBNavigatesToJavaDuringDumbMode() {
        myFixture.addClass("""
                package example.mni;
                public final class DumbModeBridge {
                    public static void invoke() {}
                }
                """);
        myFixture.configureByText(
                McfppFileType.INSTANCE,
                "func inv<caret>oke() = example.mni.DumbModeBridge.invoke;"
        );

        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> {
            assertTrue(DumbService.isDumb(getProject()));
            PsiElement declaration = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
            assertNotNull(declaration);
            List<PsiElement> targets = McfppMniLineMarkerProvider.findJavaTargets(declaration);
            assertEquals(1, targets.size());
            assertEquals("invoke", assertInstanceOf(targets.getFirst(), PsiMethod.class).getName());

            myFixture.performEditorAction(IdeActions.ACTION_GOTO_DECLARATION);
            assertEquals("Ctrl+B must select Java while IDEA is still indexing",
                    "DumbModeBridge.java",
                    FileEditorManager.getInstance(getProject()).getSelectedFiles()[0].getName());
        });
    }

    public void testFromTypeDeclarationNameNavigatesToBoundJavaClass() {
        PsiClass base = myFixture.addClass("""
                package example.mni;
                public class Base {}
                """);
        myFixture.configureByText(
                McfppFileType.INSTANCE,
                "@From<\"example.mni.Base\">\ndata Ch<caret>ild {}"
        );

        PsiElement declaration = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
        assertNotNull(declaration);
        PsiElement[] targets = new McfppMniGotoDeclarationHandler().getGotoDeclarationTargets(
                declaration, myFixture.getCaretOffset(), myFixture.getEditor());

        assertNotNull("The @From MCFPP type declaration must expose its Java binding", targets);
        assertEquals(List.of(base), List.of(targets));
        assertEquals(List.of(base), McfppMniLineMarkerProvider.findJavaTargets(declaration));
    }

    public void testMniLineMarkersNavigateInBothDirectionsUsingExactIndexKeys() {
        myFixture.addClass("""
                package top.mcfpp.annotations;
                public @interface MNIFunction {
                    String[] readOnlyParams() default {};
                    String[] normalParams() default {};
                }
                """);
        PsiClass bridge = myFixture.addClass("""
                package example.mni;
                import top.mcfpp.annotations.MNIFunction;
                public final class Bridge {
                    @MNIFunction(normalParams = {"string"})
                    public static void invoke(String value) {}
                }
                """);
        PsiClass base = myFixture.addClass("""
                package example.mni;
                public class Base {}
                """);
        myFixture.configureByText(McfppFileType.INSTANCE, """
                @From<"example.mni.Base">
                data Child {}
                func invoke(value as string) = example.mni.Bridge.invoke;
                """);
        PsiFile bindingFile = myFixture.getFile();
        assertEquals(2, MniJavaTargetParser.parse(bindingFile.getText()).size());
        DumbService.getInstance(getProject()).waitForSmartMode();
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        var indexedKeys = com.intellij.util.indexing.FileBasedIndex.getInstance()
                .getAllKeys(McfppSymbolIndex.NAME, getProject());
        assertContainsElements(
                indexedKeys,
                McfppSymbolIndex.mniMethodKey("example.mni.Bridge.invoke"),
                McfppSymbolIndex.mniClassKey("example.mni.Base")
        );

        PsiMethod method = bridge.findMethodsByName("invoke", false)[0];
        List<PsiElement> methodDeclarations = McfppMniLineMarkerProvider.findMcfppTargets(
                method.getNameIdentifier());
        assertEquals(1, methodDeclarations.size());
        assertEquals("invoke", methodDeclarations.getFirst().getText());
        assertEquals(bindingFile, methodDeclarations.getFirst().getContainingFile());
        PsiElement[] reverseNavigation = new McfppMniGotoDeclarationHandler().getGotoDeclarationTargets(
                method.getNameIdentifier(), method.getTextOffset(), myFixture.getEditor());
        assertNotNull(reverseNavigation);
        assertEquals(methodDeclarations, List.of(reverseNavigation));

        int targetOffset = bindingFile.getText().lastIndexOf("invoke");
        PsiElement mcfppTarget = bindingFile.findElementAt(targetOffset);
        assertNotNull(mcfppTarget);
        List<PsiElement> javaImplementations = McfppMniLineMarkerProvider.findJavaTargets(mcfppTarget);
        assertEquals(List.of(method), javaImplementations);

        int declarationOffset = bindingFile.getText().indexOf("invoke");
        PsiElement declarationName = bindingFile.findElementAt(declarationOffset);
        assertNotNull(declarationName);
        assertEquals(List.of(method), McfppMniLineMarkerProvider.findJavaTargets(declarationName));

        List<PsiElement> classDeclarations = McfppMniLineMarkerProvider.findMcfppTargets(
                base.getNameIdentifier());
        assertEquals(1, classDeclarations.size());
        PsiElement fromTarget = classDeclarations.getFirst();
        assertEquals("\"example.mni.Base\"", fromTarget.getText());
        assertEquals(List.of(base), McfppMniLineMarkerProvider.findJavaTargets(fromTarget));

    }
}
