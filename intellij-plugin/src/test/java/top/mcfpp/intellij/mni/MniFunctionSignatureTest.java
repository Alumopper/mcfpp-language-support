package top.mcfpp.intellij.mni;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MniFunctionSignatureTest {
    @Test
    void parsesReadOnlyNormalAndReturnTypes() {
        String source = """
                func convert<kind as type, size as int = 2>(
                    input as list<string>,
                    static var entity<minecraft:zombie>
                ) -> map<string> = example.mni.Bridge.convert;
                """;
        MniJavaTargetParser.Target target = MniJavaTargetParser.parse(source).getFirst();

        MniFunctionSignature signature = MniFunctionSignature.from(source, target).orElseThrow();

        assertEquals("convert", signature.name());
        assertEquals(List.of("type", "int"), signature.readOnlyTypes());
        assertEquals(List.of("list<string>", "entity<minecraft:zombie>"), signature.normalTypes());
        assertEquals("map<string>", signature.returnType());
        assertEquals(5, signature.javaArgumentCount());
    }

    @Test
    void preservesCommasNestedInsideTypesAndDefaults() {
        String source = """
                func nested(value as entity<1, "zombie", "undead">, other as (int|string)) = example.mni.Bridge.nested;
                """;
        MniJavaTargetParser.Target target = MniJavaTargetParser.parse(source).getFirst();

        MniFunctionSignature signature = MniFunctionSignature.from(source, target).orElseThrow();

        assertEquals(List.of("entity<1,\"zombie\",\"undead\">", "(int|string)"), signature.normalTypes());
        assertEquals("void", signature.returnType());
    }
}
