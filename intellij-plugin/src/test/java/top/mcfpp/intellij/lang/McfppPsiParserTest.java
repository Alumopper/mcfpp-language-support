package top.mcfpp.intellij.lang;

import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class McfppPsiParserTest extends BasePlatformTestCase {
    public void testMcfppExtensionCreatesLanguagePsi() {
        var file = myFixture.configureByText("extension-association.mcfpp", "func main() {}");

        assertInstanceOf(file, McfppFile.class);
        assertSame(McfppFileType.INSTANCE, file.getFileType());
        assertSame(McfppLanguage.INSTANCE, file.getLanguage());
    }

    public void testCreatesStableNamedDeclarationNodes() {
        myFixture.configureByText(McfppFileType.INSTANCE, """
                data Counter {
                    var value as int = 0;
                    func increment(amount as int) -> int { return value; }
                }
                typealias list<int> as Scores;
                """);

        Collection<McfppNamedElement> declarations = PsiTreeUtil.findChildrenOfType(
                myFixture.getFile(), McfppNamedElement.class);
        Map<String, McfppNamedElement> byName = declarations.stream().collect(Collectors.toMap(
                McfppNamedElement::getName,
                Function.identity()
        ));

        assertEquals(5, byName.size());
        assertSame(McfppElementTypes.TYPE_DECLARATION, byName.get("Counter").getNode().getElementType());
        assertSame(McfppElementTypes.VARIABLE_DECLARATION, byName.get("value").getNode().getElementType());
        assertSame(McfppElementTypes.FUNCTION_DECLARATION, byName.get("increment").getNode().getElementType());
        assertSame(McfppElementTypes.PARAMETER_DECLARATION, byName.get("amount").getNode().getElementType());
        assertSame(McfppElementTypes.TYPE_ALIAS_DECLARATION, byName.get("Scores").getNode().getElementType());
        PsiElement nameIdentifier = byName.get("increment").getNameIdentifier();
        assertNotNull(nameIdentifier);
        assertEquals("increment", nameIdentifier.getText());
        assertEquals(myFixture.getFile().getText().indexOf("increment"), byName.get("increment").getTextOffset());
    }
}
