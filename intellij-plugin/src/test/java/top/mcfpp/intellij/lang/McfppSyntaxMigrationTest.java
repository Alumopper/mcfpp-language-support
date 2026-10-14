package top.mcfpp.intellij.lang;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class McfppSyntaxMigrationTest {
    @Test void retainsMarkdownDocumentation() {
        String source = "/// # Counter\r\n  /// **value**\r\n  ///\r\n  ///     indented\r\ndata Counter {var value: int;}";
        McfppSymbol type = McfppFileModel.parse(source).symbols().stream().filter(symbol -> symbol.name().equals("Counter")).findFirst().orElseThrow();
        assertEquals("# Counter\n**value**\n\n    indented", type.documentation());
    }

    @Test void indexesDirectDeclarationsAndRejectsOldForms() {
        McfppFileModel model = McfppFileModel.parse("data Box {value: int; const fixed=1; dynamic count: int; var explicit: int; const var old=2; var legacy as int;}");
        assertTrue(model.symbols().stream().noneMatch(symbol -> symbol.name().equals("value") || symbol.name().equals("old") || symbol.name().equals("legacy")));
        assertEquals(3, model.symbols().stream().filter(symbol -> symbol.kind() == McfppSymbolKind.FIELD).count());
        McfppLexer lexer = new McfppLexer();
        lexer.start("/// final line");
        assertEquals(McfppTokenTypes.DOC_COMMENT, lexer.getTokenType());
        lexer.start("# old comment");
        assertEquals(McfppTokenTypes.BAD_CHARACTER, lexer.getTokenType());
    }

    @Test void distinguishesTypeAnnotationsAndNamespaceQualifiers() {
        String source = "func transform<T, N: int = 1 as int>(input: models:Counter): models:Counter { return input as models:Counter }";
        assertTrue(McfppDeclarationSyntax.isAnnotationColon(source, source.indexOf("input:") + 5));
        assertFalse(McfppDeclarationSyntax.isAnnotationColon(source, source.indexOf("models:") + 6));
        assertTrue(McfppDeclarationSyntax.isAnnotationColon(source, source.indexOf("): ") + 1));
        McfppFileModel model = McfppFileModel.parse(source);
        assertEquals("type", McfppTypes.declaredType(model.symbols().stream().filter(symbol -> symbol.name().equals("T")).findFirst().orElseThrow()));
        assertEquals("int", McfppTypes.declaredType(model.symbols().stream().filter(symbol -> symbol.name().equals("N")).findFirst().orElseThrow()));
        assertEquals("Counter", McfppTypes.declaredType(model.symbols().stream().filter(symbol -> symbol.name().equals("input")).findFirst().orElseThrow()));
    }

    @Test void retainsAliasesViewsImportsAndAnonymousValueParameters() {
        String source = "import counters:count as total\nimport var imported: int\ndata IntView as int { var value: int }\ntypealias list<int> as Scores\nfunc transform<T>(SomeClass) { var value: T }";
        McfppFileModel model = McfppFileModel.parse(source);
        assertEquals(new McfppImport("counters", "count", "total"), model.imports().getFirst());
        assertEquals(1, model.imports().size());
        assertEquals("int", McfppTypes.declaredType(model.symbols().stream().filter(symbol -> symbol.name().equals("imported")).findFirst().orElseThrow()));
        assertTrue(model.symbols().stream().anyMatch(symbol -> symbol.name().equals("IntView")));
        assertTrue(model.symbols().stream().anyMatch(symbol -> symbol.name().equals("Scores")));
        assertTrue(model.symbols().stream().noneMatch(symbol -> symbol.kind() == McfppSymbolKind.PARAMETER && symbol.name().equals("SomeClass")));
    }

    @Test void indexesImportedVariableDeclarationsWithEitherColonSpacing() {
        McfppFileModel model = McfppFileModel.parse("import var imported: int\nimport var compact:int");
        assertTrue(model.imports().isEmpty());
        assertEquals(java.util.List.of("imported", "compact"), model.symbols().stream().map(McfppSymbol::name).toList());
        assertTrue(model.symbols().stream().allMatch(symbol -> symbol.kind() == McfppSymbolKind.FIELD && "int".equals(McfppTypes.declaredType(symbol))));
    }
}
