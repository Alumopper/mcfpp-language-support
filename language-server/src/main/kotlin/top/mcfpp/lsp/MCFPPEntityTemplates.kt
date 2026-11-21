package top.mcfpp.lsp

import top.mcfpp.language.EntityBuiltins

/**
 * Shared interpretation of compiler-owned entity template types.
 *
 * The compiler gives every `entity` declaration an `EntityRef<T>` reference type.
 * Template members (`var`, `func`) belong to the template value, while `entity var`
 * and `entity func` members belong to the reference. Keeping both syntaxes on the
 * declaration symbol lets completion and the type checker distinguish them without
 * rebuilding the compiler's template registry.
 */
internal object MCFPPEntityTemplates {
    private val REFERENCE = Regex("^(?:[\\w.]*:)?EntityRef\\s*<\\s*([\\w.]+)\\s*>$")
    private val TEMPLATE = Regex("^(?:[\\w.]*:)?([A-Za-z_][A-Za-z0-9_]*)$")

    fun referenceType(template: String): String = "EntityRef<$template>"

    fun templateOf(typeName: String?): String? {
        val text = typeName?.trim() ?: return null
        return REFERENCE.find(text)?.groupValues?.get(1)?.substringAfterLast(':')
    }

    fun isReference(typeName: String?): Boolean = templateOf(typeName) != null ||
        typeName?.trim()?.substringBefore('<')?.substringAfterLast(':') == "EntityRef"

    /** Template ancestry, retaining the built-in entity kind that terminates the chain. */
    fun owners(symbols: List<MCFPPSymbol>, template: String): List<String> {
        val typesByName = symbols
            .asSequence()
            .filter { it.topLevel && it.kind in setOf(org.eclipse.lsp4j.SymbolKind.Class, org.eclipse.lsp4j.SymbolKind.Enum) }
            .groupBy { it.name.substringAfterLast(':') }
        val result = linkedSetOf<String>()
        val pending = ArrayDeque<String>()
        pending.addLast(template)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst().substringAfterLast(':')
            if (!result.add(current)) continue
            val symbol = typesByName[current].orEmpty().firstOrNull()
            symbol?.superTypes.orEmpty().forEach { pending.addLast(it) }
            if (EntityBuiltins.TYPES.contains(current)) {
                EntityBuiltins.owners(current).forEach(result::add)
            }
        }
        // Every entity template also reaches the base `entity` interface.
        result.add("entity")
        return result.toList()
    }

    /** The built-in entity interface contributing members, for example `BlockDisplay` or `entity`. */
    fun kindOf(symbols: List<MCFPPSymbol>, template: String): String? =
        owners(symbols, template).firstOrNull { EntityBuiltins.TYPES.contains(it) }
            ?: template.takeIf { EntityBuiltins.TYPES.contains(it) }

    fun templateName(typeName: String?): String? =
        TEMPLATE.find(typeName?.trim() ?: "")?.groupValues?.get(1)
}
