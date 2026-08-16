package top.mcfpp.intellij.mni;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MniJavaTargetParserTest {
    @Test
    void parsesNativeFunctionOwnerAndMethod() {
        String source = "func announce(message as string) = example.mni.ProjectMni.announce;";

        List<MniJavaTargetParser.Target> targets = MniJavaTargetParser.parse(source);

        assertEquals(1, targets.size());
        assertEquals(MniJavaTargetParser.Kind.JAVA_METHOD, targets.getFirst().kind());
        assertEquals("example.mni.ProjectMni", targets.getFirst().ownerQualifiedName());
        assertEquals("announce", targets.getFirst().memberName());
        assertEquals("example.mni.ProjectMni.announce", targets.getFirst().qualifiedName());
    }

    @Test
    void parsesMultilineNativeFunctionsOperatorsAndAccessors() {
        String source = """
                func announce(
                    message as string
                ) -> void = example.mni.ProjectMni.announce

                operator + (other as Vector) -> Vector = example.mni.VectorMni.plus
                var value as int {
                    get = example.mni.ValueMni.read;
                    set = example.mni.ValueMni.write;
                }
                """;

        List<MniJavaTargetParser.Target> targets = MniJavaTargetParser.parse(source);

        assertEquals(
                List.of("announce", "plus", "read", "write"),
                targets.stream().map(MniJavaTargetParser.Target::memberName).toList()
        );
        assertTrue(targets.stream().allMatch(target -> target.kind() == MniJavaTargetParser.Kind.JAVA_METHOD));
    }

    @Test
    void parsesQuotedAndUnquotedFromTargets() {
        String source = """
                @From<"top.mcfpp.mni.minecraft.AreaData">
                data Area;
                @From<example.mni.ProjectMni>
                data Project;
                """;

        List<MniJavaTargetParser.Target> targets = MniJavaTargetParser.parse(source);

        assertEquals(2, targets.size());
        assertEquals("top.mcfpp.mni.minecraft.AreaData", targets.get(0).qualifiedName());
        assertEquals("example.mni.ProjectMni", targets.get(1).qualifiedName());
        assertTrue(targets.stream().allMatch(target -> target.kind() == MniJavaTargetParser.Kind.FROM));
    }

    @Test
    void ignoresOrdinaryAssignmentsAndJvmValueAccess() {
        String source = """
                var value as int = project.value;
                var raw = value::jvm;
                """;

        assertTrue(MniJavaTargetParser.parse(source).isEmpty());
        assertFalse(MniJavaTargetParser.completionAt(source, source.length()).isPresent());
    }

    @Test
    void detectsIncompleteNativeCompletionContext() {
        String source = "func announce(message as string) = example.mni.Pro";

        MniJavaTargetParser.CompletionContext context = MniJavaTargetParser
                .completionAt(source, source.length())
                .orElseThrow();

        assertEquals(MniJavaTargetParser.Kind.JAVA_METHOD, context.kind());
        assertEquals("example.mni", context.qualifier());
        assertEquals("Pro", context.namePrefix());
    }

    @Test
    void detectsMultilineOperatorCompletionContext() {
        String source = """
                operator + (
                    other as Vector
                ) -> Vector = example.mni.VectorMni.pl""";

        MniJavaTargetParser.CompletionContext context = MniJavaTargetParser
                .completionAt(source, source.length())
                .orElseThrow();

        assertEquals(MniJavaTargetParser.Kind.JAVA_METHOD, context.kind());
        assertEquals("example.mni.VectorMni", context.qualifier());
        assertEquals("pl", context.namePrefix());
    }

    @Test
    void detectsQuotedFromCompletionContext() {
        String source = "@From<\"java.lang.Str";

        MniJavaTargetParser.CompletionContext context = MniJavaTargetParser
                .completionAt(source, source.length())
                .orElseThrow();

        assertEquals(MniJavaTargetParser.Kind.FROM, context.kind());
        assertEquals("java.lang", context.qualifier());
        assertEquals("Str", context.namePrefix());
    }

    @Test
    void boundsCompletionScanningForLargeFiles() {
        String source = "func old() = example.mni.Bridge.invoke" +
                " ".repeat(MniJavaTargetParser.COMPLETION_CONTEXT_LIMIT + 1) + "tail";

        assertTrue(MniJavaTargetParser.completionAt(source, source.length()).isEmpty());
    }
}
