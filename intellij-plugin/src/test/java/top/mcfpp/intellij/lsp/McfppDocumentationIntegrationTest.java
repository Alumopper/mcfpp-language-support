package top.mcfpp.intellij.lsp;

import com.intellij.platform.backend.documentation.DocumentationTargetProvider;
import com.intellij.testFramework.ExtensionTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import top.mcfpp.intellij.lang.McfppDocumentationTargetProvider;
import top.mcfpp.intellij.lang.McfppFileType;

public final class McfppDocumentationIntegrationTest extends BasePlatformTestCase {
    public void testNativeAndLspDocumentationOfferOneTargetAndKeepTheNativeFallback() {
        myFixture.configureByText(McfppFileType.INSTANCE, "/// One definition.\nfunc value(): int { return 1 }\nfunc main() { val<caret>ue() }");
        var nativeProvider = new McfppDocumentationTargetProvider();
        var nativeTargets = nativeProvider.documentationTargets(myFixture.getFile(), myFixture.getCaretOffset());
        assertEquals(1, nativeTargets.size());
        var lspProvider = new com.intellij.platform.lsp.test.TestDocumentationProvider(nativeTargets);
        ExtensionTestUtil.maskExtensions(DocumentationTargetProvider.EP_NAME,
                java.util.List.of(lspProvider, nativeProvider), getTestRootDisposable());
        var combined = DocumentationTargetProvider.EP_NAME.getExtensionList().stream()
                .flatMap(provider -> provider.documentationTargets(myFixture.getFile(), myFixture.getCaretOffset()).stream()).toList();
        assertEquals(1, combined.size());
        assertNotNull(combined.getFirst().computeDocumentation());
        lspProvider.targets = java.util.List.of();
        assertEquals(1, nativeProvider.documentationTargets(myFixture.getFile(), myFixture.getCaretOffset()).size());
    }
}
