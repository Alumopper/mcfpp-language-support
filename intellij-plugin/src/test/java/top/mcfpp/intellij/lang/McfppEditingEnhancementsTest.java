package top.mcfpp.intellij.lang;

import com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.lang.annotation.AnnotationSession;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.testFramework.IndexingTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Range;
import top.mcfpp.intellij.lsp.McfppLspDiagnosticsSupport;
import java.util.Arrays;
import java.util.List;

public final class McfppEditingEnhancementsTest extends BasePlatformTestCase {
    @Override protected void setUp() throws Exception {
        super.setUp();
        var plugin = PluginManagerCore.getPlugin(PluginId.getId("top.mcfpp.language"));
        if (plugin != null) VfsRootAccess.allowRootAccess(getTestRootDisposable(), plugin.getPluginPath().toString());
    }

    public void testSameNameCandidatesImportTheSelectedNamespace() {
        myFixture.addFileToProject("one.mcfpp", "namespace one; data Counter {} data CountDown {}");
        myFixture.addFileToProject("two.mcfpp", "namespace two; data Counter {}");
        myFixture.configureByText(McfppFileType.INSTANCE,
                "namespace app;\nfunc main() { var value as Coun<caret>; }\n");
        LookupElement[] items = complete();
        assertEquals(2, Arrays.stream(items).filter(item -> item.getLookupString().equals("Counter")).count());
        select(items, "Counter", "two:Counter");
        myFixture.checkResult("namespace app;\nimport two:Counter;\nfunc main() { var value as Counter<caret>; }\n");
        assertEquals("two", McfppFileModels.get(resolve("Counter;").getContainingFile()).namespace());
    }

    public void testExternalOverloadsRetainSignaturesAndInsertParentheses() {
        myFixture.addFileToProject("convert.mcfpp", """
                namespace converters;
                func convert(value as int) {}
                func convert(value as string) {}
                """);
        myFixture.configureByText(McfppFileType.INSTANCE, "namespace app;\nfunc main() { conv<caret>; }\n");
        LookupElement[] items = complete();
        assertEquals(2, Arrays.stream(items).filter(item -> item.getLookupString().equals("convert")).count());
        var item = Arrays.stream(items).filter(value -> tail(value).contains("value as string")).findFirst().orElseThrow();
        myFixture.getLookup().setCurrentItem(item);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
        myFixture.checkResult("namespace app;\nimport converters:convert;\nfunc main() { convert(<caret>); }\n");
    }

    public void testLocalConflictInsertsQualifiedNameAndResolvesSelectedType() {
        myFixture.addFileToProject("models.mcfpp", "namespace models; data Counter {} data CountDown {}");
        myFixture.configureByText(McfppFileType.INSTANCE,
                "namespace app;\nfunc main() { var Counter as int = 1; var value as Coun<caret>; }\n");
        select(complete(), "Counter", "models:Counter");
        assertFalse(myFixture.getFile().getText().contains("import "));
        myFixture.checkResult("namespace app;\nfunc main() { var Counter as int = 1; var value as models:Counter<caret>; }\n");
        assertEquals("models", McfppFileModels.get(resolve("Counter;").getContainingFile()).namespace());
    }

    public void testExistingSymbolAliasIsCompletedAndReused() {
        myFixture.addFileToProject("models.mcfpp", "namespace models; data Counter {} data CountDown {}");
        myFixture.configureByText(McfppFileType.INSTANCE,
                "namespace app;\nimport models:Counter as RenamedCounter;\nfunc main() { var value as Ren<caret>; }\n");
        LookupElement[] items = complete();
        if (items != null) select(items, "RenamedCounter", "models:Counter");
        myFixture.checkResult("namespace app;\nimport models:Counter as RenamedCounter;\nfunc main() { var value as RenamedCounter<caret>; }\n");
        assertEquals("models", McfppFileModels.get(resolve("RenamedCounter;").getContainingFile()).namespace());
    }

    public void testQualifiedCompletionDoesNotImportOrRepeatParentheses() {
        myFixture.addFileToProject("library.mcfpp", "namespace library; func compute() {} func compare() {}");
        myFixture.configureByText(McfppFileType.INSTANCE, "func main() { library:com<caret>(); }");
        select(complete(), "compute", "library:compute");
        assertEquals("func main() { library:compute(); }", myFixture.getFile().getText());
    }

    public void testOptimizeImportsPreservesCommentsAliasesAndVersionBranches() {
        String source = """
                # file header
                namespace app;
                import z:Z;
                import a:A;
                import z:Z;
                # attached comment
                import y:Y;
                import b:B as Other;
                #if MC >= 26.3
                import z:Z;
                import a:A;
                #else
                import z:Z;
                #endif
                func main() {}
                """;
        String expected = """
                # file header
                namespace app;
                import a:A;
                import z:Z;
                # attached comment
                import y:Y;
                import b:B as Other;
                #if MC >= 26.3
                import a:A;
                import z:Z;
                #else
                import z:Z;
                #endif
                func main() {}
                """;
        assertEquals(expected, McfppImportOptimizer.organize(source));
        assertEquals(expected, McfppImportOptimizer.organize(expected));
        myFixture.configureByText(McfppFileType.INSTANCE, source);
        WriteCommandAction.runWriteCommandAction(getProject(),
                new McfppImportOptimizer().processFile(myFixture.getFile()));
        myFixture.checkResult(expected);
    }

    public void testOptimizeImportsPreservesInlineCommentsCodeAndDifferentAliases() {
        String source = "import z:Z; # attached\nimport b:B; func main() {}\n" +
                "import z:Z as First;\nimport z:Z as Second;\nimport a:*;\n";
        String expected = "import z:Z; # attached\nimport b:B; func main() {}\n" +
                "import a:*;\nimport z:Z as First;\nimport z:Z as Second;\n";
        assertEquals(expected, McfppImportOptimizer.organize(source));
        assertEquals("import a:A\r\nimport z:Z\r\n", McfppImportOptimizer.organize("import z:Z\r\nimport a:A\r\n"));
    }

    public void testImportInsertionProtectsHeaderDocsAndConditionalImports() {
        myFixture.configureByText(McfppFileType.INSTANCE,
                "# file header\nnamespace app;\n#if MC >= 26.3\nimport models:Counter;\n#endif\n#{Function docs}#\nfunc main() {}\n");
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            var document = myFixture.getEditor().getDocument();
            assertTrue(McfppImportManager.insertImport(document, "models", "Counter") >= 0);
            assertEquals(-1, McfppImportManager.insertImport(document, "models", "Counter"));
        });
        assertTrue(myFixture.getEditor().getDocument().getText().startsWith(
                "# file header\nnamespace app;\nimport models:Counter;\n#if"));
        assertTrue(myFixture.getEditor().getDocument().getText().contains("#{Function docs}#\nfunc main()"));
    }

    public void testImportInsertionIgnoresCommentAndStringContentsAndSupportsCRLF() {
        myFixture.configureByText(McfppFileType.INSTANCE,
                "# import models:Counter;\n#{Docs}#\nfunc main() { var text = \"import models:Counter;\"; }\n");
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            McfppImportManager.insertImport(myFixture.getEditor().getDocument(), "models", "Counter");
        });
        assertTrue(myFixture.getEditor().getDocument().getText().startsWith(
                "# import models:Counter;\nimport models:Counter;\n#{Docs}#"));
        // IDEA normalizes Document line separators; test raw CRLF layout independently.
        assertEquals("namespace app;\r\n".length(),
                McfppImportLayout.insertionOffset("namespace app;\r\n"));
    }

    public void testNativeInspectionOffersEveryImportCandidate() {
        myFixture.addFileToProject("one.mcfpp", "namespace one; data Missing {}");
        myFixture.addFileToProject("two.mcfpp", "namespace two; data Missing {}");
        myFixture.enableInspections(McfppUnresolvedReferenceInspection.class);
        myFixture.configureByText(McfppFileType.INSTANCE, "func main() { var value as Mis<caret>sing; }");
        ready();
        assertEquals(1, undefinedCount());
        assertNotEmpty(myFixture.filterAvailableIntentions("Import 'one:Missing'"));
        assertNotEmpty(myFixture.filterAvailableIntentions("Import 'two:Missing'"));
        myFixture.launchAction(myFixture.findSingleIntention("Import 'two:Missing'"));
        assertTrue(myFixture.getFile().getText().startsWith("import two:Missing;\n"));
        assertEquals(0, undefinedCount());
    }

    public void testNativeInspectionIgnoresInactiveBranchesAnnotationsSelectorsAndLiterals() {
        myFixture.enableInspections(McfppUnresolvedReferenceInspection.class);
        myFixture.configureByText(McfppFileType.INSTANCE, """
                @From<example.mni.Base>
                data Child {}
                func nativeBinding() = example.mni.Bridge.run;
                func main(value as int) {
                    # Undefined words in a comment
                    var text = "Undefined words in a string";
                    var target = @e[type=minecraft:pig,tag=hello];
                    var item = value;
                    /say ignoredCommandArgument
                #if MC >= 26.3
                    ignoredModern();
                #else
                    missing();
                #endif
                }
                """);
        ready();
        var errors = undefinedMessages();
        assertEquals(List.of("Undefined symbol `missing`"), errors);
    }

    public void testIncompleteSyntaxAndUnavailableImportsStayLspOwned() {
        myFixture.enableInspections(McfppUnresolvedReferenceInspection.class);
        myFixture.configureByText(McfppFileType.INSTANCE, "func main() { missing(");
        assertEquals(0, undefinedCount());
        myFixture.configureByText(McfppFileType.INSTANCE,
                "import unavailable:External;\nfunc main() { var value as External; }");
        ready();
        assertEquals(0, undefinedCount());
    }

    public void testQualifiedCallsDoNotOfferTopLevelCreation() {
        myFixture.configureByText(McfppFileType.INSTANCE, "func main() { object.mis<caret>sing(); }");
        assertEmpty(myFixture.filterAvailableIntentions("Create MCFPP function 'missing'"));
        myFixture.configureByText(McfppFileType.INSTANCE, "func main() { other:Mis<caret>sing(); }");
        assertEmpty(myFixture.filterAvailableIntentions("Create MCFPP data type 'Missing'"));
    }

    public void testDuplicateInspectionUnderstandsNestedScopesAndParameterCollisions() {
        myFixture.enableInspections(McfppDuplicateDeclarationInspection.class);
        myFixture.configureByText(McfppFileType.INSTANCE, """
                data Duplicate {}
                data Duplicate {}
                data First { var value as int; }
                data Second { var value as int; }
                func main(value as int) {
                    var value as int;
                    if (true) { var value as int; }
                    if (false) { var value as int; }
                }
                """);
        var errors = myFixture.doHighlighting().stream().filter(info -> info.getDescription() != null &&
                info.getDescription().startsWith("Duplicate ")).toList();
        assertEquals(2, errors.size());
    }

    public void testNestedVariableResolutionDoesNotLeakToOuterScope() {
        myFixture.configureByText(McfppFileType.INSTANCE, """
                func main(value as int) {
                    if (true) { var value as string; }
                    var copy = va<caret>lue;
                }
                """);
        ready();
        var target = myFixture.getFile().findElementAt(myFixture.getCaretOffset()).getReferences()[0].resolve();
        assertNotNull(target);
        assertEquals(McfppSymbolKind.PARAMETER, McfppFileModels.get(target.getContainingFile())
                .declarationAt(target.getTextOffset()).kind());
    }

    public void testLspFiltersOnlyOwnedUndefinedDiagnosticsAndRestoresDisabledInspection() {
        myFixture.enableInspections(McfppUnresolvedReferenceInspection.class);
        myFixture.configureByText(McfppFileType.INSTANCE, "func main() { mis<caret>sing(); }");
        ready();
        int offset = myFixture.getCaretOffset() - 3;
        Diagnostic undefined = new Diagnostic(new Range(), "Undefined symbol `missing`", DiagnosticSeverity.Error, "mcfpp");
        undefined.setCode(McfppUnresolvedReferenceInspection.DIAGNOSTIC_CODE);
        var holder = new AnnotationHolderImpl(new AnnotationSession(myFixture.getFile()), false);
        var support = new McfppLspDiagnosticsSupport();
        support.createAnnotation(holder, undefined, TextRange.from(offset, 7), List.of());
        assertEmpty(holder);
        myFixture.disableInspections(new McfppUnresolvedReferenceInspection());
        // Run the annotation builder in its normal PSI context.
        holder.runAnnotatorWithContext(myFixture.getFile().findElementAt(offset), (element, annotations) ->
                support.createAnnotation(annotations, undefined, TextRange.from(offset, 7), List.of()));
        assertEquals(1, holder.size());
        myFixture.enableInspections(McfppUnresolvedReferenceInspection.class);
        undefined.setCode("mcfpp.some-other-diagnostic");
        holder.runAnnotatorWithContext(myFixture.getFile().findElementAt(offset), (element, annotations) ->
                support.createAnnotation(annotations, undefined, TextRange.from(offset, 7), List.of()));
        assertEquals(2, holder.size());
    }

    public void testCreateFunctionUsesConfiguredIndentAndCanBeUndone() {
        myFixture.configureByText(McfppFileType.INSTANCE, "func main() { miss<caret>ing(1); }\n");
        var manager = com.intellij.psi.codeStyle.CodeStyleSettingsManager.getInstance(getProject());
        var settings = manager.getCurrentSettings().clone();
        settings.getIndentOptions(McfppFileType.INSTANCE).INDENT_SIZE = 2;
        manager.setTemporarySettings(settings);
        try {
            myFixture.launchAction(myFixture.findSingleIntention("Create MCFPP function 'missing'"));
            String text = myFixture.getEditor().getDocument().getText();
            assertTrue(text, text.contains("func missing(arg1 as int) {\n  \n}\n"));
            int caret = myFixture.getCaretOffset();
            assertEquals('\n', text.charAt(caret));
            assertEquals("  ", text.substring(text.lastIndexOf('\n', caret - 1) + 1, caret));
            myFixture.performEditorAction(com.intellij.openapi.actionSystem.IdeActions.ACTION_UNDO);
            assertEquals("func main() { missing(1); }\n", myFixture.getEditor().getDocument().getText());
        } finally {
            manager.dropTemporarySettings();
        }
    }

    public void testAliasedTypeSupportsMemberCompletionAndNavigation() {
        myFixture.addFileToProject("models.mcfpp", "namespace models; data Counter { func increment() {} func inspect() {} }");
        myFixture.configureByText(McfppFileType.INSTANCE,
                "import models:Counter as Alias;\nfunc main() { Alias.in<caret>(); }\n");
        select(complete(), "increment", "models:Counter.increment");
        assertEquals("import models:Counter as Alias;\nfunc main() { Alias.increment(); }\n",
                myFixture.getFile().getText());
        var target = resolve("increment();");
        assertEquals("models", McfppFileModels.get(target.getContainingFile()).namespace());
    }

    public void testGlobalFieldCompletionDoesNotQualifyAnUnshadowedField() {
        myFixture.configureByText(McfppFileType.INSTANCE,
                "namespace app;\nvar counter as int = 0;\nfunc main() { cou<caret>; }\n");
        LookupElement[] items = complete();
        if (items != null) select(items, "counter", "app:counter");
        assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().contains("{ counter; }"));
    }

    public void testCompilerOnlyDeclarationsAndDependenciesStayLspOwned() {
        myFixture.enableInspections(McfppUnresolvedReferenceInspection.class);
        for (String source : List.of(
                "data Generic<T as Type> { func main() { var value as T; } }",
                "data Child: Parent { func main() { inherited(); } }",
                "interface Interface { func method(); }",
                "data Accessor { value as int { get { return backing; } set { backing = value; } } }")) {
            myFixture.configureByText(McfppFileType.INSTANCE, source);
            assertEquals(source, 0, undefinedCount());
        }
        myFixture.addFileToProject("mcfpp.json", "{\"jars\":[\"dependency.jar\"]}");
        myFixture.configureByText(McfppFileType.INSTANCE, "func main() { dependencyFunction(); }");
        ready();
        assertEquals(0, undefinedCount());
        assertFalse(McfppUnresolvedReferenceInspection.ownsDiagnostic(
                myFixture.getFile().findElementAt(myFixture.getFile().getText().indexOf("dependencyFunction"))));
    }

    public void testExampleProjectCompletionImportsAndAltEnter() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("..", "examples", "complete-mcfpp-project");
        assertTrue(root.toString(), java.nio.file.Files.isDirectory(root));
        myFixture.addFileToProject("mcfpp.json", java.nio.file.Files.readString(root.resolve("mcfpp.json")));
        try (var files = java.nio.file.Files.walk(root.resolve("src/main/mcfpp"))) {
            for (var path : files.filter(java.nio.file.Files::isRegularFile).toList()) {
                myFixture.addFileToProject(root.relativize(path).toString(), java.nio.file.Files.readString(path));
            }
        }
        String source = java.nio.file.Files.readString(root.resolve("src/main/mcfpp/main.mcfpp"));
        myFixture.configureByText(McfppFileType.INSTANCE, source.replace("math:doubled(result)", "math:dou<caret>(result)"));
        var items = complete();
        if (items != null) select(items, "doubled", "complete.math:doubled");
        assertEquals(source, myFixture.getFile().getText());
        WriteCommandAction.runWriteCommandAction(getProject(), new McfppImportOptimizer().processFile(myFixture.getFile()));
        assertTrue(myFixture.getFile().getText().contains("import complete.math:doubled as twice;"));
        assertTrue(myFixture.getFile().getText().contains("import complete.math:* as math;"));
        assertTrue(myFixture.getFile().getText().contains("func announce(message as string) = example.mni.ProjectMni.announce;"));
        myFixture.configureByText(McfppFileType.INSTANCE, source.replace("counter.increment()", "counter.increment();\n    new<caret>Helper(1)"));
        myFixture.launchAction(myFixture.findSingleIntention("Create MCFPP function 'newHelper'"));
        assertTrue(myFixture.getFile().getText().contains("func newHelper(arg1 as int)"));
    }

    public void testOtherFileInCurrentNamespacePreventsAmbiguousImport() {
        myFixture.addFileToProject("local.mcfpp", "namespace app; data Counter {}");
        myFixture.addFileToProject("foreign.mcfpp", "namespace models; data Counter {} data CountDown {}");
        myFixture.configureByText(McfppFileType.INSTANCE,
                "namespace app;\nfunc main() { var value as Coun<caret>; }\n");
        select(complete(), "Counter", "models:Counter");
        myFixture.checkResult("namespace app;\nfunc main() { var value as models:Counter<caret>; }\n");
        assertEquals("models", McfppFileModels.get(resolve("Counter;").getContainingFile()).namespace());
    }

    private void ready() {
        DumbService.getInstance(getProject()).waitForSmartMode();
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
    }
    private LookupElement[] complete() { ready(); return myFixture.completeBasic(); }
    private void select(LookupElement[] items, String name, String qualified) {
        assertNotNull(items);
        var selected = Arrays.stream(items).filter(item -> item.getLookupString().equals(name) &&
                presentation(item).getTypeText().equals(qualified)).findFirst().orElseThrow();
        myFixture.getLookup().setCurrentItem(selected);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
    }
    private static LookupElementPresentation presentation(LookupElement item) {
        var result = new LookupElementPresentation(); item.renderElement(result); return result;
    }
    private static String tail(LookupElement item) { return presentation(item).getTailText(); }
    private List<String> undefinedMessages() {
        return myFixture.doHighlighting().stream().map(info -> info.getDescription())
                .filter(message -> message != null && message.startsWith("Undefined symbol `")).toList();
    }
    private int undefinedCount() { return undefinedMessages().size(); }
    private PsiElement resolve(String suffix) {
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        String source = myFixture.getFile().getText();
        int offset = source.lastIndexOf(suffix);
        var element = myFixture.getFile().findElementAt(offset);
        assertNotNull(element);
        assertNotNull(element.getReference());
        var resolved = element.getReference().resolve();
        assertNotNull(resolved);
        return resolved;
    }
}
