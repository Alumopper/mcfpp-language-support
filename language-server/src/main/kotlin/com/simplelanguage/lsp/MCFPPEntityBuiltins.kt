package com.simplelanguage.lsp

import org.eclipse.lsp4j.*
import top.mcfpp.language.EntityBuiltins

/** These declarations have compiler identities rather than standard-library data declarations. */
internal object MCFPPEntityBuiltins {
    fun symbols(uri: String): List<MCFPPSymbol> = buildList {
        val sourceUri = javaClass.classLoader.getResource("editor/entity-builtins.mcfpp")?.toExternalForm() ?: uri
        val source = EntityBuiltins.source()
        fun location(name: String, marker: String, start: Int = 0): Range {
            val offset = source.indexOf(marker, start).coerceAtLeast(0) + marker.indexOf(name)
            val before = source.substring(0, offset)
            val point = Position(before.count { it == '\n' }, before.substringAfterLast('\n').length)
            return Range(point, Position(point.line, point.character + name.length))
        }
        for (type in EntityBuiltins.TYPES + "UUID") add(MCFPPSymbol(sourceUri, type, SymbolKind.Class, location(type, "data $type"),
            typeName = type, superTypes = listOfNotNull(EntityBuiltins.parent(type)), topLevel = true,
            namespaceName = "mcfpp.lang", isExternal = true, isStdlib = true, isSynthetic = true,
            documentation = if (type == "UUID") "Four-int UUID value. UUID(string) accepts shortened UUID text; UUID(IntArray) requires four integers."
                else "Entity reference type. Declaring this type does not check the actual entity kind; use checkType<T>()."))
        for ((owner, members) in EntityBuiltins.declaredMembers()) for (member in members) add(MCFPPSymbol(
            sourceUri, member.name(), if (member.function()) SymbolKind.Method else SymbolKind.Property,
            location(member.name(), (if (member.function()) "func " else "var ") + member.name(),
                source.indexOf("data " + (if (owner == "entity") "EntityReference" else owner)).coerceAtLeast(0)),
            detail = member.type(), containerName = owner, typeName = member.type(), receiverType = owner,
            namespaceName = "mcfpp.lang", isExternal = true, isStdlib = true, isSynthetic = true,
            documentation = member.documentation(), parameters = if (member.name() == "checkType")
                listOf(MCFPPFunctionParameter("T", "type", isReadOnly = true)) else MCFPPContextBuiltins.entityParameters(member.name())))
    }
}
