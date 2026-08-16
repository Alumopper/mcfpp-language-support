package top.mcfpp.intellij.lang;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McfppSemanticTokensTest {
    @Test
    void classifiesDeclarationsParametersVariablesCallsAndNamespaces() {
        String source = """
                namespace demo.example
                data Player {
                    var score as int = 0
                    func update(target as entity) -> bool {
                        update(target)
                        return true
                    }
                }
                """;

        Map<Integer, TextAttributesKey> attributes = McfppSemanticTokens.analyze(source);

        assertEquals(McfppSyntaxHighlighter.NAMESPACE, attributes.get(source.indexOf("demo")));
        assertEquals(McfppSyntaxHighlighter.NAMESPACE, attributes.get(source.indexOf("example")));
        assertEquals(McfppSyntaxHighlighter.TYPE_NAME, attributes.get(source.indexOf("Player")));
        assertEquals(McfppSyntaxHighlighter.FIELD, attributes.get(source.indexOf("score")));
        assertEquals(McfppSyntaxHighlighter.FUNCTION_DECLARATION, attributes.get(source.indexOf("update")));
        assertEquals(McfppSyntaxHighlighter.PARAMETER, attributes.get(source.indexOf("target")));
        assertEquals(
                McfppSyntaxHighlighter.FUNCTION_CALL,
                attributes.get(source.indexOf("update", source.indexOf("update") + 1))
        );
    }

    @Test
    void marksExtensionOwnerAsATypeAndLastIdentifierAsFunctionName() {
        String source = "func Player.tick() { tick() }";
        Map<Integer, TextAttributesKey> attributes = McfppSemanticTokens.analyze(source);

        assertEquals(McfppSyntaxHighlighter.TYPE_NAME, attributes.get(source.indexOf("Player")));
        assertEquals(McfppSyntaxHighlighter.FUNCTION_DECLARATION, attributes.get(source.indexOf("tick")));
        assertEquals(McfppSyntaxHighlighter.FUNCTION_CALL, attributes.get(source.lastIndexOf("tick")));
    }

    @Test
    void classifiesAliasesOnSubsequentLinesWithoutTreatingThemAsVariables() {
        String source = """
                namespace demo
                typealias list<int> as Scores
                import complete.math:doubled as twice
                """;
        Map<Integer, TextAttributesKey> attributes = McfppSemanticTokens.analyze(source);

        assertEquals(McfppSyntaxHighlighter.TYPE_NAME, attributes.get(source.indexOf("Scores")));
        assertEquals(McfppSyntaxHighlighter.NAMESPACE, attributes.get(source.indexOf("twice")));
    }

    @Test
    void classifiesReadOnlyParametersForeachVariablesMembersAndMniTargets() {
        String source = """
                @From<example.mni.Base>
                data Child {
                    var count as int = 0
                    func visit<player as Player>(values as list<int>) {
                        for (entry : values) {
                            player.notify(entry)
                            count++
                        }
                    }
                }
                enum Direction { NORTH, SOUTH }
                func bridge(value as string) = example.mni.Bridge.invoke;
                """;

        Map<Integer, TextAttributesKey> attributes = McfppSemanticTokens.analyze(source);

        assertEquals(McfppSyntaxHighlighter.PARAMETER, attributes.get(source.indexOf("player")));
        assertEquals(McfppSyntaxHighlighter.PARAMETER, attributes.get(source.indexOf("player.notify")));
        assertEquals(McfppSyntaxHighlighter.VARIABLE, attributes.get(source.indexOf("entry")));
        assertEquals(McfppSyntaxHighlighter.VARIABLE, attributes.get(source.indexOf("entry)")));
        assertEquals(McfppSyntaxHighlighter.FIELD, attributes.get(source.indexOf("count")));
        assertEquals(McfppSyntaxHighlighter.FIELD, attributes.get(source.lastIndexOf("count")));
        assertEquals(McfppSyntaxHighlighter.ENUM_MEMBER, attributes.get(source.indexOf("NORTH")));
        assertEquals(McfppSyntaxHighlighter.NAMESPACE, attributes.get(source.indexOf("example.mni.Base")));
        assertEquals(McfppSyntaxHighlighter.TYPE_NAME, attributes.get(source.indexOf("Base")));
        assertEquals(McfppSyntaxHighlighter.TYPE_NAME, attributes.get(source.indexOf("Bridge")));
        assertEquals(McfppSyntaxHighlighter.FUNCTION_CALL, attributes.get(source.lastIndexOf("invoke")));
    }

    @Test
    void distinguishesNamespaceAliasesConstructorsAndMemberFields() {
        String source = """
                namespace demo
                import complete.models:Counter
                import complete.math:* as math
                func main() {
                    var counter as Counter = Counter()
                    counter.value = math:doubled(counter.value)
                }
                """;

        Map<Integer, TextAttributesKey> attributes = McfppSemanticTokens.analyze(source);

        assertEquals(McfppSyntaxHighlighter.TYPE_NAME, attributes.get(source.indexOf("Counter()")));
        assertEquals(McfppSyntaxHighlighter.NAMESPACE, attributes.get(source.indexOf("math:doubled")));
        assertEquals(McfppSyntaxHighlighter.FUNCTION_CALL, attributes.get(source.indexOf("doubled")));
        assertEquals(McfppSyntaxHighlighter.FIELD, attributes.get(source.indexOf("value")));
        assertEquals(McfppSyntaxHighlighter.FIELD, attributes.get(source.lastIndexOf("value")));
    }

    @Test
    void exposesSemanticSubrangesInsideQuotedMniTargets() {
        String source = "@From<\"example.mni.Base\">\ndata Child {}";

        McfppSemanticTokens.Analysis analysis = McfppSemanticTokens.analyzeDetailed(source);

        assertTrue(analysis.embeddedRanges().stream().anyMatch(range ->
                source.substring(range.startOffset(), range.endOffset()).equals("example") &&
                        range.attributesKey() == McfppSyntaxHighlighter.NAMESPACE));
        assertTrue(analysis.embeddedRanges().stream().anyMatch(range ->
                source.substring(range.startOffset(), range.endOffset()).equals("Base") &&
                        range.attributesKey() == McfppSyntaxHighlighter.TYPE_NAME));
    }

    @Test
    void highlightsMinecraftCommandStructureAndMcfppInterpolation() {
        String source = "    /execute as @e[type=minecraft:zombie] positioned ~1 64 ^-2 run give @s minecraft:stone ${count}";

        McfppSemanticTokens.Analysis analysis = McfppSemanticTokens.analyzeDetailed(source);

        assertEmbedded(analysis, source, "execute", McfppSyntaxHighlighter.COMMAND_ROOT);
        assertEmbedded(analysis, source, "as", McfppSyntaxHighlighter.COMMAND_KEYWORD);
        assertEmbedded(analysis, source, "@e", McfppSyntaxHighlighter.TARGET_SELECTOR);
        assertEmbedded(analysis, source, "minecraft:zombie", McfppSyntaxHighlighter.COMMAND_RESOURCE);
        assertEmbedded(analysis, source, "type", McfppSyntaxHighlighter.COMMAND_SELECTOR_KEY);
        assertEmbedded(analysis, source, "~1", McfppSyntaxHighlighter.COMMAND_COORDINATE);
        assertEmbedded(analysis, source, "^-2", McfppSyntaxHighlighter.COMMAND_COORDINATE);
        assertEmbedded(analysis, source, "count", McfppSyntaxHighlighter.VARIABLE);
    }

    @Test
    void keepsNamespacedCommandHeadsAndResourceLocationsAsDistinctWholeTokens() {
        String source = "/minecraft:execute run give @s minecraft:stone";

        McfppSemanticTokens.Analysis analysis = McfppSemanticTokens.analyzeDetailed(source);

        assertEmbedded(analysis, source, "minecraft:execute", McfppSyntaxHighlighter.COMMAND_ROOT);
        assertEmbedded(analysis, source, "minecraft:stone", McfppSyntaxHighlighter.COMMAND_RESOURCE);
        assertTrue(analysis.embeddedRanges().stream().noneMatch(range ->
                range.attributesKey() == McfppSyntaxHighlighter.NAMESPACE &&
                        range.startOffset() >= source.indexOf("minecraft:stone")));

        String incomplete = "/give @s minecraft:";
        McfppSemanticTokens.Analysis incompleteAnalysis = McfppSemanticTokens.analyzeDetailed(incomplete);
        assertEmbedded(incompleteAnalysis, incomplete, "minecraft:", McfppSyntaxHighlighter.COMMAND_RESOURCE);
    }

    private static void assertEmbedded(
            McfppSemanticTokens.Analysis analysis,
            String source,
            String text,
            TextAttributesKey key
    ) {
        assertTrue(analysis.embeddedRanges().stream().anyMatch(range ->
                        source.substring(range.startOffset(), range.endOffset()).equals(text) &&
                                range.attributesKey() == key),
                "No " + key.getExternalName() + " range for " + text);
    }
}
