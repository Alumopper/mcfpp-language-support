package top.mcfpp.intellij.lang;

import java.util.Set;

/** Compiler-defined namespaces whose declarations are visible without an explicit import. */
final class McfppStandardLibrary {
    private static final Set<String> IMPLICIT_NAMESPACES = Set.of(
            "mcfpp.lang",
            "mcfpp.sys",
            "mcfpp"
    );

    private McfppStandardLibrary() {
    }

    static boolean isImplicitNamespace(String namespace) {
        return IMPLICIT_NAMESPACES.contains(namespace);
    }
}
