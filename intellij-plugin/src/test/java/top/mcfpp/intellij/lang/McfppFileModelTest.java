package top.mcfpp.intellij.lang;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McfppFileModelTest {
    @Test
    void selectsVersionBranchesWithoutMovingDeclarationOffsets() {
        String source = """
                #if MC >= 26.3
                import modern:*;
                func selected() -> float { return -1.5f; }
                #else
                import legacy:*;
                func selected() -> int { return 1; }
                #endif
                """;
        McfppFileModel current = McfppFileModel.parse(source, "26.3");
        McfppFileModel old = McfppFileModel.parse(source, "26.2");
        assertEquals(1, current.symbols().size());
        assertEquals(1, old.symbols().size());
        assertEquals("modern", current.imports().getFirst().namespace());
        assertEquals("legacy", old.imports().getFirst().namespace());
        assertEquals(source.indexOf("selected"), current.symbols().getFirst().nameOffset());
        assertEquals(source.lastIndexOf("selected"), old.symbols().getFirst().nameOffset());
        // The persistent index retains both branches so changing version needs no reindex.
        assertEquals(2, McfppFileModel.parse(source).symbols().size());
    }

    @Test
    void extractsNamespaceImportsDeclarationsMembersParametersAndDocumentation() {
        String source = """
                namespace complete.app;
                import complete.models:Counter;
                import complete.math:doubled as twice;
                import complete.math:* as math;

                #{Increments a counter.}#
                data Counter {
                    var value as int = 0;
                    func increment(amount as int) -> int {
                        var next as int = value + amount;
                        return next;
                    }
                }

                typealias list<int> as Scores;
                """;

        McfppFileModel model = McfppFileModel.parse(source);

        assertEquals("complete.app", model.namespace());
        assertEquals(3, model.imports().size());
        assertTrue(model.importExposes("complete.math", "doubled", "twice"));

        McfppSymbol type = symbol(model, McfppSymbolKind.TYPE, "Counter");
        assertEquals("Increments a counter.", type.documentation());
        assertEquals("complete.app:Counter", type.qualifiedName());

        McfppSymbol function = symbol(model, McfppSymbolKind.FUNCTION, "increment");
        assertEquals("Counter", function.owner());
        assertEquals("complete.app:Counter.increment", function.qualifiedName());
        assertEquals("func increment(amount as int) -> int", function.signature());

        assertEquals("Counter", symbol(model, McfppSymbolKind.FIELD, "value").owner());
        McfppSymbol parameter = symbol(model, McfppSymbolKind.PARAMETER, "amount");
        assertTrue(parameter.scopeContains(source.indexOf("return next")));
        assertEquals(McfppSymbolKind.VARIABLE, symbol(model, McfppSymbolKind.VARIABLE, "next").kind());
        assertEquals(McfppSymbolKind.TYPE_ALIAS, symbol(model, McfppSymbolKind.TYPE_ALIAS, "Scores").kind());

        List<McfppSymbol> typeChildren = model.structureChildren(type);
        assertTrue(typeChildren.stream().anyMatch(symbol -> symbol.name().equals("value")));
        assertTrue(typeChildren.stream().anyMatch(symbol -> symbol.name().equals("increment")));
    }

    @Test
    void handlesObjectDataEnumsAndNativeFunctions() {
        String source = """
                namespace demo;
                object data Service { constructor() {} }
                enum Direction { North, South = 2 }
                func bridge(value as int) = example.mni.Bridge.invoke;
                """;

        McfppFileModel model = McfppFileModel.parse(source);

        assertNotNull(symbol(model, McfppSymbolKind.TYPE, "Service"));
        assertEquals("Service", symbol(model, McfppSymbolKind.CONSTRUCTOR, "constructor").owner());
        assertEquals("Direction", symbol(model, McfppSymbolKind.ENUM_MEMBER, "North").owner());
        assertEquals("Direction", symbol(model, McfppSymbolKind.ENUM_MEMBER, "South").owner());
        assertEquals("func bridge(value as int)", symbol(model, McfppSymbolKind.FUNCTION, "bridge").signature());
    }

    @Test
    void extractsUnmodifiedAndAnnotatedStandardLibraryFields() {
        String source = """
                data PlayerData {
                    private id as string;
                    @Name<"Score"> score as int;
                    func read(fallback as int) -> int { return score; }
                }
                """;

        McfppFileModel model = McfppFileModel.parse(source);

        assertEquals("PlayerData", symbol(model, McfppSymbolKind.FIELD, "id").owner());
        McfppSymbol score = symbol(model, McfppSymbolKind.FIELD, "score");
        assertEquals("PlayerData", score.owner());
        assertTrue(score.signature().contains("score as int"));
        assertEquals(1, model.symbols().stream()
                .filter(value -> value.kind() == McfppSymbolKind.FIELD && value.name().equals("score"))
                .count());
    }

    @Test
    void extractsReadOnlyParametersAndScopesForeachVariables() {
        String source = """
                func visit<player as Player = currentPlayer()>(values as list<int>) {
                    for (entry : values) {
                        player.notify(entry);
                    }
                    entry;
                }
                """;

        McfppFileModel model = McfppFileModel.parse(source);

        McfppSymbol function = symbol(model, McfppSymbolKind.FUNCTION, "visit");
        assertTrue(function.signature().startsWith("func visit<player as Player"));
        assertEquals("player as Player = currentPlayer()",
                symbol(model, McfppSymbolKind.PARAMETER, "player").signature());
        assertEquals("values as list<int>",
                symbol(model, McfppSymbolKind.PARAMETER, "values").signature());

        McfppSymbol entry = symbol(model, McfppSymbolKind.VARIABLE, "entry");
        assertTrue(entry.scopeContains(source.indexOf("notify(entry)") + "notify(".length()));
        assertFalse(entry.scopeContains(source.lastIndexOf("entry")));
    }

    private static McfppSymbol symbol(McfppFileModel model, McfppSymbolKind kind, String name) {
        return model.symbols().stream()
                .filter(symbol -> symbol.kind() == kind && symbol.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing " + kind + " " + name + ": " + model.symbols()));
    }
}
