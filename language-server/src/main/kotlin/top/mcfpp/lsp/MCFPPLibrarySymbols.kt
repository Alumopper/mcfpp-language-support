package top.mcfpp.lsp

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import top.mcfpp.library.format.CompoundCodec
import top.mcfpp.library.format.LibraryModelCodec
import top.mcfpp.library.format.NativeCodec
import top.mcfpp.library.model.*
import top.mcfpp.library.syntax.*

/** Read editor metadata without binding compiler globals or loading library providers. */
internal fun librarySymbols(bytes: ByteArray, uri: String, standard: Boolean, sources: MCFPPSourceJarResolver? = null): List<MCFPPSymbol> {
    val model = LibraryModelCodec(listOf(CompoundCodec, NativeCodec)).decode(bytes)
    val declarations = model.declarations
    fun qualifiedDeclarationName(reference: DeclarationReference): String = when (reference) {
        is DeclarationReference.Local -> declarations.declaration(reference.target).common.let { common ->
            val namespace = declarations.environment(common.environment).namespace
            if (namespace.isEmpty()) common.name else "$namespace:${common.name}"
        }
        is DeclarationReference.Dependency -> reference.key.name
    }
    fun typeName(reference: TypeRef?, depth: Int = 0): String {
        if (reference == null) return "void"
        if (depth > 24) return "any"
        fun nested(value: TypeRef) = typeName(value, depth + 1)
        return when (val type = model.tables.type(reference)) {
            is LibraryType.Builtin -> when (type.key) {
                "context.base" -> "Context"
                "context.dimension" -> "DimensionContext"
                "context.pos" -> "PosContext"
                "context.rotation" -> "RotationContext"
                "context.anchor" -> "AnchorContext"
                "context.entity" -> "EntityContext"
                "context.execution" -> "ExecutionContext"
                "context.entity_execution" -> "EntityExecutionContext"
                "context.relation" -> "RelationContext"
                "context.position" -> "ContextPosition"
                "context.bound.rotation" -> "ContextRotation"
                "context.bound.dimension" -> "ContextDimension"
                "context.bound.anchor" -> "ContextAnchor"
                else -> type.key.removePrefix("entity.")
            }
            is LibraryType.Declaration -> qualifiedDeclarationName(type.target)
            is LibraryType.Parameter -> {
                val owner = (type.owner as? DeclarationReference.Local)?.let { declarations.declaration(it.target) }
                val parameters = when (owner) {
                    is FunctionDeclaration -> owner.readonly
                    is CompoundDeclaration -> owner.readonly
                    else -> emptyList()
                }
                if (owner is MacroDeclaration) owner.binding.genericParameters.getOrNull(type.index.toInt())?.name ?: "T${type.index}"
                else parameters.getOrNull(type.index.toInt())?.let { declarations.declaration(it).common.name } ?: "T${type.index}"
            }
            is LibraryType.Applied -> "${nested(type.constructor)}<${type.arguments.joinToString(", ", transform = ::nested)}>"
            is LibraryType.Specialized -> nested(type.constructor)
            is LibraryType.Union -> type.alternatives.joinToString(" | ", "(", ")", transform = ::nested)
            is LibraryType.Intersection -> type.components.joinToString(" & ", "(", ")", transform = ::nested)
            is LibraryType.Object -> type.fields.joinToString(", ", "{ ", " }") { "${it.name}: ${nested(it.type)}${if (it.optional) "?" else ""}" }
            is LibraryType.Literal -> nested(type.primitive)
            is LibraryType.Function -> (if (type.contexts.isEmpty()) "" else "context(${type.contexts.joinToString(", ", transform = ::nested)}) ") +
                "(${type.parameters.joinToString(", ", transform = ::nested)}) -> ${nested(type.result)}"
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
    fun macroParameter(parameter: MacroParameter): MCFPPFunctionParameter =
        MCFPPFunctionParameter(parameter.name, macroType(parameter.type), macroMode = parameter.mode,
            macroCallbackParameters = (parameter.type as? MacroType.Callback)?.parameters.orEmpty().map(::macroParameter))
    val range = Range(Position(0, 0), Position(0, 0))
    val docsByDeclarationId: Map<Long, String> = model.debug?.documents
        ?.associate { it.declaration.value to it.text }
        .orEmpty()
    val symbols = declarations.declarations.mapIndexedNotNull { index, declaration ->
        val common = declaration.common
        if (common.access == DeclarationAccess.PRIVATE &&
            !(declaration is ValueDeclaration && declaration.role == ValueRole.MEMBER) && declaration !is ConstructorDeclaration)
            return@mapIndexedNotNull null
        val owner = common.owner?.let { declarations.declaration(it) }
        val container = owner?.takeUnless { it is NamespaceDeclaration }?.common?.name
        val kind: SymbolKind
        val type: String?
        var params = emptyList<MCFPPFunctionParameter>()
        var contexts = emptyList<MCFPPFunctionParameter>()
        var parents = emptyList<String>()
        var receiver: String? = null
        var name = common.name
        when (declaration) {
            is FunctionDeclaration -> {
                val factory = common.flags and DeclarationFlags.FACTORY != 0L
                kind = if (factory) SymbolKind.Constructor else if (container == null) SymbolKind.Function else SymbolKind.Method
                if (factory) name = container ?: name
                type = typeName(declaration.result)
                params = parameters(declaration.readonly, declaration.normal)
                contexts = parameters(emptyList(), declaration.contexts)
                receiver = declaration.receiver?.let { typeName(it) }
            }
            is MacroDeclaration -> {
                receiver = declaration.binding.receiverType?.let { typeName(it) }
                kind = if (receiver == null) SymbolKind.Function else SymbolKind.Method
                name = declaration.binding.sourceName; type = macroType(declaration.binding.result)
                params = declaration.binding.parameters.map(::macroParameter)
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
                kind = if (container == null) SymbolKind.Variable else SymbolKind.Field
                type = typeName(declaration.type) + if (common.flags and DeclarationFlags.NULLABLE != 0L) "?" else ""
            }
            is EnumDeclaration -> { kind = SymbolKind.Enum; type = name }
            is EnumMemberDeclaration -> { kind = SymbolKind.EnumMember; type = container }
            is TypeAliasDeclaration -> { kind = SymbolKind.TypeParameter; type = typeName(declaration.target) }
            is AnnotationProviderDeclaration -> { kind = SymbolKind.Class; type = name }
            else -> return@mapIndexedNotNull null
        }
        val detail = if (declaration is EnumMemberDeclaration) {
            val data = model.tables.constant(declaration.data) as? ConstantValue.Nbt
            data?.let { "$container.$name = ${it.snbt} (ordinal ${declaration.ordinal})" }
        } else type
        val native = when (declaration) {
            is FunctionDeclaration -> declaration.native
            is MacroDeclaration -> declaration.binding.native
            else -> null
        }
        MCFPPSymbol(uri = uri, name = name, kind = kind, range = range, detail = detail, typeName = type,
            containerName = container, topLevel = container == null, namespaceName = declarations.environment(common.environment).namespace,
            isExternal = true, isStdlib = standard, parameters = params, contextParameters = contexts, receiverType = receiver, superTypes = parents,
            isStatic = declaration is EnumMemberDeclaration || common.flags and DeclarationFlags.STATIC != 0L,
            isReadOnly = declaration is EnumMemberDeclaration,
            isRuntimeValue = (declaration as? ValueDeclaration)?.storage == ValueStorage.EXTERNAL,
            accessModifier = when (common.access) {
                DeclarationAccess.PRIVATE -> MCFPPAccessModifier.PRIVATE
                DeclarationAccess.PROTECTED -> MCFPPAccessModifier.PROTECTED
                else -> MCFPPAccessModifier.PUBLIC
            },
            isDataTemplate = declaration is CompoundDeclaration && declaration.category == CompoundCategory.DATA && common.flags and DeclarationFlags.ENTITY == 0L,
            isAbstract = common.flags and DeclarationFlags.ABSTRACT != 0L,
            isNative = native != null,
            nativeImplementation = native?.let { sources?.implementation(it) ?: MCFPPValueMembers.implementation(it) },
            isMacro = declaration is MacroDeclaration,
            typeParameters = (declaration as? MacroDeclaration)?.binding?.genericParameters?.map { it.name }.orEmpty(),
            initializer = if (declaration is ValueDeclaration && (declaration.defaultBody != null || declaration.frozen != null)) "<library default>" else null,
            isEntityTemplate = declaration is CompoundDeclaration && common.flags and DeclarationFlags.ENTITY != 0L,
            isEntityMember = common.flags and DeclarationFlags.ENTITY != 0L,
            documentation = docsByDeclarationId[index + 1L])
    }
    return symbols + symbols.filter { it.kind == SymbolKind.Constructor }.map {
        it.copy(name = "new", kind = SymbolKind.Method, isStatic = true)
    }
}
