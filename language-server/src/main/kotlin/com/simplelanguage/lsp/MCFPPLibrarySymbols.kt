package com.simplelanguage.lsp

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import top.mcfpp.library.format.CompoundCodec
import top.mcfpp.library.format.LibraryModelCodec
import top.mcfpp.library.format.NativeCodec
import top.mcfpp.library.model.*
import top.mcfpp.library.syntax.*

/** Read editor metadata without binding compiler globals or loading library providers. */
internal fun librarySymbols(bytes: ByteArray, uri: String, standard: Boolean): List<MCFPPSymbol> {
    val model = LibraryModelCodec(listOf(CompoundCodec, NativeCodec)).decode(bytes)
    val declarations = model.declarations
    fun declarationName(reference: DeclarationReference): String = when (reference) {
        is DeclarationReference.Local -> declarations.declaration(reference.target).common.name
        is DeclarationReference.Dependency -> reference.key.name
    }
    fun typeName(reference: TypeRef?, depth: Int = 0): String {
        if (reference == null) return "void"
        if (depth > 24) return "any"
        fun nested(value: TypeRef) = typeName(value, depth + 1)
        return when (val type = model.tables.type(reference)) {
            is LibraryType.Builtin -> type.key
            is LibraryType.Declaration -> declarationName(type.target)
            is LibraryType.Parameter -> {
                val owner = (type.owner as? DeclarationReference.Local)?.let { declarations.declaration(it.target) }
                val parameters = when (owner) {
                    is FunctionDeclaration -> owner.readonly
                    is CompoundDeclaration -> owner.readonly
                    else -> emptyList()
                }
                parameters.getOrNull(type.index.toInt())?.let { declarations.declaration(it).common.name } ?: "T${type.index}"
            }
            is LibraryType.Applied -> "${nested(type.constructor)}<${type.arguments.joinToString(", ", transform = ::nested)}>"
            is LibraryType.Specialized -> nested(type.constructor)
            is LibraryType.Union -> type.alternatives.joinToString(" | ", transform = ::nested)
            is LibraryType.Intersection -> type.components.joinToString(" & ", transform = ::nested)
            is LibraryType.Function -> "(${type.parameters.joinToString(", ", transform = ::nested)}) -> ${nested(type.result)}"
            is LibraryType.NbtRef -> "NBTRef<${nested(type.value)}>"
            is LibraryType.UnresolvedNominal -> type.name
            is LibraryType.Dependent -> "any"
        }
    }
    fun parameters(readonly: List<DeclarationId>, normal: List<DeclarationId>) = (readonly + normal).mapNotNull { id ->
        (declarations.declaration(id) as? ValueDeclaration)?.let {
            MCFPPFunctionParameter(it.common.name, typeName(it.type), it.defaultBody != null || it.frozen != null,
                isReadOnly = id in readonly)
        }
    }
    fun macroType(type: MacroType): String = when (type) {
        is MacroType.Source -> typeName(type.type)
        is MacroType.Callback -> "(${type.parameters.joinToString(", ") { macroType(it.type) }}) -> ${macroType(type.result)}"
    }
    val range = Range(Position(0, 0), Position(0, 0))
    return declarations.declarations.mapIndexedNotNull { index, declaration ->
        val common = declaration.common
        if (common.access == DeclarationAccess.PRIVATE) return@mapIndexedNotNull null
        val owner = common.owner?.let { declarations.declaration(it) }
        val container = owner?.takeUnless { it is NamespaceDeclaration }?.common?.name
        val kind: SymbolKind
        val type: String?
        var params = emptyList<MCFPPFunctionParameter>()
        var parents = emptyList<String>()
        var receiver: String? = null
        var name = common.name
        when (declaration) {
            is FunctionDeclaration -> {
                kind = if (container == null) SymbolKind.Function else SymbolKind.Method
                type = typeName(declaration.result)
                params = parameters(declaration.readonly, declaration.normal)
                receiver = declaration.receiver?.let { typeName(it) }
            }
            is MacroDeclaration -> {
                kind = SymbolKind.Function; name = declaration.binding.sourceName; type = macroType(declaration.binding.result)
                params = declaration.binding.parameters.map { MCFPPFunctionParameter(it.name, macroType(it.type)) }
            }
            is CompoundDeclaration -> {
                kind = SymbolKind.Class; type = name
                params = parameters(declaration.readonly, emptyList())
                parents = declaration.parents.mapNotNull { it.type?.let { value -> typeName(value) } }
            }
            is ConstructorDeclaration -> {
                kind = SymbolKind.Constructor; name = container ?: name; type = name
                params = parameters(emptyList(), declaration.normal)
            }
            is ValueDeclaration -> {
                if (declaration.role !in setOf(ValueRole.MEMBER, ValueRole.GLOBAL)) return@mapIndexedNotNull null
                kind = if (container == null) SymbolKind.Variable else SymbolKind.Field; type = typeName(declaration.type)
            }
            is EnumDeclaration -> { kind = SymbolKind.Enum; type = name }
            is EnumMemberDeclaration -> { kind = SymbolKind.EnumMember; type = container }
            is TypeAliasDeclaration -> { kind = SymbolKind.Class; type = typeName(declaration.target) }
            is AnnotationProviderDeclaration -> { kind = SymbolKind.Class; type = name }
            else -> return@mapIndexedNotNull null
        }
        MCFPPSymbol(uri = uri, name = name, kind = kind, range = range, detail = type, typeName = type,
            containerName = container, topLevel = container == null, namespaceName = declarations.environment(common.environment).namespace,
            isExternal = true, isStdlib = standard, parameters = params, receiverType = receiver, superTypes = parents,
            isStatic = common.flags and DeclarationFlags.STATIC != 0L,
            documentation = model.debug?.documents?.firstOrNull { it.declaration.value == index + 1L }?.text)
    }
}
