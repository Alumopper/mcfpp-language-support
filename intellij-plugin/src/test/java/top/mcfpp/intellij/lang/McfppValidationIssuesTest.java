package top.mcfpp.intellij.lang;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.testFramework.IndexingTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class McfppValidationIssuesTest extends BasePlatformTestCase {
    @Override protected void setUp() throws Exception {
        super.setUp();
        var plugin = PluginManagerCore.getPlugin(PluginId.getId("top.mcfpp.language"));
        if (plugin != null) VfsRootAccess.allowRootAccess(getTestRootDisposable(), plugin.getPluginPath().toString());
    }

    public void testPhysicalValidationProjectReportsUndefinedArgumentAndOffersCreation() throws Exception {
        Path root = Path.of("..", "examples", "idea-plugin-validation");
        myFixture.addFileToProject("mcfpp.json", Files.readString(root.resolve("mcfpp.json")));
        try (var paths = Files.walk(root.resolve("src/main/mcfpp"))) {
            for (var path : paths.filter(Files::isRegularFile).toList()) {
                myFixture.addFileToProject(root.relativize(path).toString(), Files.readString(path));
            }
        }
        myFixture.enableInspections(McfppUnresolvedReferenceInspection.class);
        myFixture.configureFromExistingVirtualFile(myFixture.getTempDirFixture().getFile(
                "src/main/mcfpp/scenarios/01_completion.mcfpp"));
        ready();
        assertEquals(List.of("Undefined symbol `w`"), undefinedMessages());
        myFixture.configureFromExistingVirtualFile(myFixture.getTempDirFixture().getFile(
                "src/main/mcfpp/scenarios/06_quick_fixes.mcfpp"));
        assertEquals(List.of("Undefined symbol `missingHelper`", "Undefined symbol `MissingType`"), undefinedMessages());
        myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf("missingHelper(1"));
        assertNotEmpty(myFixture.filterAvailableIntentions("Create MCFPP function 'missingHelper'"));
        myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf("MissingType;"));
        assertNotEmpty(myFixture.filterAvailableIntentions("Create MCFPP data type 'MissingType'"));
    }

    public void testQuickFixesAtIdentifierEnd() {
        myFixture.configureByText(McfppFileType.INSTANCE, "func main() { missingHelper<caret>(1, \"hello\"); }");
        ready();
        assertNotEmpty(myFixture.filterAvailableIntentions("Create MCFPP function 'missingHelper'"));
        myFixture.configureByText(McfppFileType.INSTANCE, "func main() { var value as MissingType<caret>; }");
        assertNotEmpty(myFixture.filterAvailableIntentions("Create MCFPP data type 'MissingType'"));
        myFixture.launchAction(myFixture.findSingleIntention("Create MCFPP data type 'MissingType'"));
        assertTrue(myFixture.getEditor().getDocument().getText().contains("data MissingType {"));
    }

    public void testUnrelatedMembersDoNotResolveUnqualifiedVariables() {
        myFixture.configureByText(McfppFileType.INSTANCE, "data Other { var w as int; }\nfunc main() { var result = w; }");
        myFixture.enableInspections(McfppUnresolvedReferenceInspection.class);
        ready();
        assertEquals(List.of("Undefined symbol `w`"), undefinedMessages());
    }

    public void testLspKeepsDiagnosticsForNativeResolvedReferences() {
        myFixture.enableInspections(McfppUnresolvedReferenceInspection.class);
        myFixture.configureByText(McfppFileType.INSTANCE, "func supplied() {}\nfunc main() { supplied(); }");
        ready();
        var holder = lspAnnotation("supplied", myFixture.getFile().getText().lastIndexOf("supplied"));
        assertEquals(1, holder.size());
    }

    public void testLspErrorsOfferNativeFixesWhenInspectionIsDisabled() {
        myFixture.enableInspections(McfppUnresolvedReferenceInspection.class);
        myFixture.configureByText(McfppFileType.INSTANCE, "func main() { missingHelper(1); }");
        ready();
        myFixture.disableInspections(new McfppUnresolvedReferenceInspection());
        var holder = lspAnnotation("missingHelper", myFixture.getFile().getText().indexOf("missingHelper"));
        assertEquals(1, holder.size());
        assertNotEmpty(holder.getFirst().getQuickFixes());
    }

    private com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl lspAnnotation(String name, int offset) {
        var holder = new com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl(
                new com.intellij.lang.annotation.AnnotationSession(myFixture.getFile()), false);
        var diagnostic = new org.eclipse.lsp4j.Diagnostic(new org.eclipse.lsp4j.Range(),
                "Undefined symbol `" + name + "`", org.eclipse.lsp4j.DiagnosticSeverity.Error, "mcfpp");
        diagnostic.setCode(McfppUnresolvedReferenceInspection.DIAGNOSTIC_CODE);
        holder.runAnnotatorWithContext(myFixture.getFile().findElementAt(offset), (element, annotations) ->
                new top.mcfpp.intellij.lsp.McfppLspDiagnosticsSupport().createAnnotation(annotations, diagnostic,
                        com.intellij.openapi.util.TextRange.from(offset, name.length()), List.of()));
        return holder;
    }

    public void testUnusedImportsRespectAliasesWildcardUsesAndShadowing() {
        myFixture.addFileToProject("library.mcfpp", "namespace library; data Counter {} data Unused {} func convert(value as int) -> int { return value; }");
        myFixture.enableInspections(McfppUnusedImportInspection.class);
        myFixture.configureByText(McfppFileType.INSTANCE, """
                import library:Counter as Alpha;
                import library:* as alpha;
                import library:Unused;
                import library:convert as shadowed;
                func main() {
                    var counter as Alpha = Alpha();
                    var shadowed = 1;
                    var result = alpha:convert(shadowed);
                    var text = "Unused";
                    # Unused is mentioned only in a comment.
                }
                """);
        ready();
        var unused = myFixture.doHighlighting().stream().filter(info -> "Unused import".equals(info.getDescription())).toList();
        assertEquals(2, unused.size());
        assertEquals(List.of("import library:Unused;", "import library:convert as shadowed;"), unused.stream()
                .map(info -> myFixture.getFile().getText().substring(info.getStartOffset(), info.getEndOffset())).toList());
    }

    public void testUnusedImportQuickFixRetainsCommentsAndConditionalDirectives() {
        myFixture.enableInspections(McfppUnusedImportInspection.class);
        myFixture.configureByText(McfppFileType.INSTANCE, """
                #if MC >= 1.21.8
                # attached documentation
                import library:Un<caret>used; # inline documentation
                #endif
                func main() {}
                """);
        ready();
        myFixture.launchAction(myFixture.findSingleIntention("Remove unused import"));
        assertFalse(myFixture.getFile().getText().contains("import "));
        assertTrue(myFixture.getFile().getText().contains("# inline documentation"));
        assertTrue(myFixture.getFile().getText().contains("# attached documentation"));
        assertTrue(myFixture.getFile().getText().contains("#endif"));
        myFixture.performEditorAction(com.intellij.openapi.actionSystem.IdeActions.ACTION_UNDO);
        com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertTrue(myFixture.getFile().getText().contains("import library:Unused;"));
    }

    public void testInactiveBranchesAreDimmedAndCollapsedAndRefreshWithConfiguration() throws Exception {
        var config = myFixture.addFileToProject("mcfpp.json", "{\"version\":\"26.3\"}");
        var source = myFixture.addFileToProject("src/versions.mcfpp", """
                #if MC >= 26.3
                func modern() { missingModern(); }
                #else
                func legacy() { missingLegacy(); }
                #endif
                """);
        myFixture.configureFromExistingVirtualFile(source.getVirtualFile());
        ready();
        var manager = com.intellij.codeInsight.folding.CodeFoldingManager.getInstance(getProject());
        var document = myFixture.getEditor().getDocument();
        var initial = com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(() ->
                com.intellij.openapi.application.ReadAction.compute(() -> manager.buildInitialFoldings(document)))
                .get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(initial);
        initial.setToEditor(myFixture.getEditor());
        assertInactive("func legacy() { missingLegacy(); }\n");
        assertCollapsedInactiveRegion();
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            var configDocument = com.intellij.psi.PsiDocumentManager.getInstance(getProject()).getDocument(config);
            configDocument.setText("{\"version\":\"1.21.8\"}");
            com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitDocument(configDocument);
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveDocument(configDocument);
        });
        McfppConfigurationListener.refreshEditor(getProject(), myFixture.getEditor(), (McfppFile) myFixture.getFile());
        assertInactive("func modern() { missingModern(); }\n");
        assertCollapsedInactiveRegion();
        var fold = inactiveRegion();
        myFixture.getEditor().getFoldingModel().runBatchFoldingOperation(() -> fold.setExpanded(true));
        McfppConfigurationListener.refreshEditor(getProject(), myFixture.getEditor(), (McfppFile) myFixture.getFile());
        assertTrue(inactiveRegion().isExpanded());
    }

    private void assertCollapsedInactiveRegion() {
        assertFalse(inactiveRegion().isExpanded());
    }

    private com.intellij.openapi.editor.FoldRegion inactiveRegion() {
        var range = McfppFileModels.versionAnalysis(myFixture.getFile()).inactiveRanges().getFirst();
        return java.util.Arrays.stream(myFixture.getEditor().getFoldingModel().getAllFoldRegions())
                .filter(region -> region.getStartOffset() == range.start()).findFirst().orElseThrow();
    }

    public void testNestedInactiveBranchesExcludeBoundaryDirectivesAndIgnoreStringDirectives() {
        String source = "#if MC >= 26.3\n#if MC >= 26.4\nfunc nested() {}\n#endif\n#else\nfunc active() {}\n#endif\n";
        var result = top.mcfpp.language.VersionPreprocessor.preprocess(source, "1.21.8");
        assertEquals(1, result.inactiveRanges().size());
        var range = result.inactiveRanges().getFirst();
        assertEquals("#if MC >= 26.4\nfunc nested() {}\n#endif\n", source.substring(range.start(), range.end()));
        assertEmpty(top.mcfpp.language.VersionPreprocessor.preprocess("var text = \"\"\"\n#if MC >= 26.3\n\"\"\";", "1.21.8").inactiveRanges());
    }

    private void assertInactive(String text) {
        var file = myFixture.getFile();
        var range = McfppFileModels.versionAnalysis(file).inactiveRanges().getFirst();
        assertEquals(text, file.getText().substring(range.start(), range.end()));
        var highlights = myFixture.doHighlighting();
        assertTrue(highlights.stream().anyMatch(info -> info.getStartOffset() == range.start() &&
                info.getEndOffset() == range.end() && com.intellij.openapi.editor.colors.CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES
                .equals(info.forcedTextAttributesKey)));
        var folds = new McfppFoldingBuilder().buildFoldRegions(file, myFixture.getEditor().getDocument(), false);
        assertEquals(1, folds.length);
        assertEquals(Boolean.TRUE, folds[0].isCollapsedByDefault());
        assertEquals(text.stripTrailing(), file.getText().substring(folds[0].getRange().getStartOffset(), folds[0].getRange().getEndOffset()));
    }

    private void ready() {
        DumbService.getInstance(getProject()).waitForSmartMode();
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
    }
    private List<String> undefinedMessages() {
        return myFixture.doHighlighting().stream().map(info -> info.getDescription())
                .filter(message -> message != null && message.startsWith("Undefined symbol `")).toList();
    }
}
