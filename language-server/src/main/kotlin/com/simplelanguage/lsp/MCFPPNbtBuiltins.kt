package com.simplelanguage.lsp

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import top.mcfpp.language.EntityBuiltins
import top.mcfpp.language.NbtBuiltins

internal object MCFPPNbtBuiltins {
    fun symbols(uri: String): List<MCFPPSymbol> {
        val sourceUri = javaClass.classLoader.getResource("editor/entity-builtins.mcfpp")?.toExternalForm() ?: uri
        val source = EntityBuiltins.source()
        return NbtBuiltins.MEMBERS.map { member ->
            val offset = source.indexOf(member.signature(), source.indexOf("data ${NbtBuiltins.SOURCE_OWNER}")) + "func ".length
            val prefix = source.substring(0, offset)
            val point = Position(prefix.count { it == '\n' }, prefix.substringAfterLast('\n').length)
            MCFPPSymbol(sourceUri, member.name(), SymbolKind.Method,
                Range(point, Position(point.line, point.character + member.name().length)),
                detail = member.type(), containerName = "nbt", typeName = member.type(), receiverType = "nbt",
                namespaceName = "mcfpp.lang", isExternal = true, isStdlib = true, isSynthetic = true,
                documentation = member.documentation(), parameters = listOf(MCFPPFunctionParameter("d", "nbt")))
        }
    }
}
