package top.mcfpp.intellij.mni;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MniFunctionSignatureTest {
    @Test
    void readsNamedReadonlyTypeAndValueParameters() {
        String source = "func bridge<T, N: int>() = example.mni.Bridge.invoke";
        var signature = MniFunctionSignature.from(source, MniJavaTargetParser.parse(source).getFirst()).orElseThrow();
        assertEquals(List.of("type", "int"), signature.readOnlyTypes());
    }

    @Test
    void distinguishesImplicitTypesFromAnonymousReadonlyValueTypes() {
        String source = "func bridge<T, I: int, values: list<int>, N: int>() = example.mni.Bridge.invoke";
        var signature = MniFunctionSignature.from(source, MniJavaTargetParser.parse(source).getFirst()).orElseThrow();
        assertEquals(List.of("type", "int", "list<int>", "int"), signature.readOnlyTypes());
    }

    @Test
    void preservesAnonymousValuesAndExplicitTypeParameters() {
        String source = "func generic<T, U: type>(value: SomeClass) = example.mni.Bridge.generic";
        var signature = MniFunctionSignature.from(source, MniJavaTargetParser.parse(source).getFirst()).orElseThrow();
        assertEquals(List.of("type", "type"), signature.readOnlyTypes());
        assertEquals(List.of("SomeClass"), signature.normalTypes());
    }

    @Test
    void parsesReadOnlyNormalAndReturnTypes() {
        String source = """
                func convert<kind, size: int = 2>(
                    input: list<string>,
                    target: entity<"minecraft:zombie">
                ) : map<string> = example.mni.Bridge.convert;
                """;
        MniJavaTargetParser.Target target = MniJavaTargetParser.parse(source).getFirst();

        MniFunctionSignature signature = MniFunctionSignature.from(source, target).orElseThrow();

        assertEquals("convert", signature.name());
        assertEquals(List.of("type", "int"), signature.readOnlyTypes());
        assertEquals(List.of("list<string>", "entity<\"minecraft:zombie\">"), signature.normalTypes());
        assertEquals("map<string>", signature.returnType());
        assertEquals(5, signature.javaArgumentCount());
    }

    @Test
    void preservesCommasNestedInsideTypesAndDefaults() {
        String source = """
                func nested(value: entity<1, "zombie", "undead">, other: (int|string)) = example.mni.Bridge.nested;
                """;
        MniJavaTargetParser.Target target = MniJavaTargetParser.parse(source).getFirst();

        MniFunctionSignature signature = MniFunctionSignature.from(source, target).orElseThrow();

        assertEquals(List.of("entity<1,\"zombie\",\"undead\">", "(int|string)"), signature.normalTypes());
        assertEquals("void", signature.returnType());
    }
}
