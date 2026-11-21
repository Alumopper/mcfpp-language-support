package top.mcfpp.lsp

import org.eclipse.lsp4j.SymbolKind
import top.mcfpp.language.TemplateLiterals
import top.mcfpp.language.StructuralTypes
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/** Resolves template schemas in the importing document, retaining field docs and generic arguments. */
internal class MCFPPTemplateLiterals(
    private val symbols: List<MCFPPSymbol>,
    private val resolveType: (String) -> String,
    private val accessible: (MCFPPSymbol, Int) -> Boolean
) {
    private val cache = mutableMapOf<String, List<MCFPPSymbol>?>()

    fun fields(type: String): List<MCFPPSymbol>? = members(type)?.filter {
        it.kind in setOf(SymbolKind.Field, SymbolKind.Property) && !it.isSynthetic
    }

    fun members(type: String): List<MCFPPSymbol>? = members(type, emptySet())

    private fun members(type: String, seen: Set<String>): List<MCFPPSymbol>? {
        if (type in seen) return null
        if (cache.containsKey(type)) return cache[type]
        StructuralTypes.fields(type)?.let { fields ->
            return fields.map { field -> MCFPPSymbol(uri = "mcfpp:structural", name = field.name(), kind = SymbolKind.Field,
                range = Range(Position(0, 0), Position(0, 0)), typeName = field.type(), detail = field.type(), topLevel = false) }
        }
        for (operator in listOf('|', '&')) {
            val components = StructuralTypes.components(type, operator)
            if (components.isEmpty()) continue
            val branches = components.map { members(it, seen + type) ?: return null }
            val candidates = branches.flatten().filter { it.kind in setOf(SymbolKind.Field, SymbolKind.Property) }.groupBy { it.name }
            return candidates.mapNotNull { (name, fields) ->
                if (operator == '|' && branches.any { branch -> branch.none { it.name == name } }) null
                else fields.first().copy(typeName = fields.mapNotNull { it.typeName }.distinct().let {
                    if (it.size == 1) it.first() else it.joinToString(" $operator ", "(", ")")
                })
            }
        }
        val base = resolveType(type.substringBefore('<').trim().removeSuffix("?"))
        val definition = symbols.firstOrNull { it.topLevel && it.name == base.substringAfterLast(':') &&
            (':' !in base || it.namespaceName == base.substringBeforeLast(':')) &&
            (it.isDataTemplate || it.kind == SymbolKind.TypeParameter) }
            ?: return null
        if (!definition.isDataTemplate) return definition.typeName?.let { members(it, seen + type) }
        val arguments = splitTypes(type.substringAfter('<', "").substringBeforeLast('>', ""))
        val substitutions = definition.parameters.filter { it.isReadOnly }.mapNotNull { it.name }.zip(arguments).toMap()
        fun substitute(value: String): String = Regex("[\\p{L}_$][\\p{L}\\p{N}_$]*").replace(value) {
            substitutions[it.value] ?: it.value
        }
        fun qualify(value: String): String {
            if ('<' in value && value.endsWith('>')) {
                val base = qualify(value.substringBefore('<'))
                return splitTypes(value.substringAfter('<').dropLast(1)).joinToString(", ", "$base<", ">") { qualify(it) }
            }
            if (':' in value.substringBefore('<')) return value
            val local = symbols.firstOrNull { it.topLevel && it.name == value.substringBefore('<').removeSuffix("?") &&
                it.namespaceName == definition.namespaceName && it.isDataTemplate } ?: return value
            return (local.namespaceName?.takeIf { it.isNotEmpty() }?.let { "$it:" } ?: "") + local.name + value.removePrefix(local.name)
        }
        val own = symbols.filter {
            it.containerName == definition.name && it.namespaceName == definition.namespaceName &&
                it.kind in setOf(SymbolKind.Field, SymbolKind.Property, SymbolKind.Method, SymbolKind.Constructor) && (!it.isStatic || it.name == "new") && !it.isEntityMember
        }.map { it.copy(typeName = it.typeName?.let(::substitute)?.let(::qualify),
            parameters = it.parameters.map { parameter -> parameter.copy(typeName = qualify(substitute(parameter.typeName))) }) }
        val inherited = definition.superTypes.flatMap { members(qualify(substitute(it)), seen + type).orEmpty() }
            .filter { it.kind in setOf(SymbolKind.Field, SymbolKind.Property) || top.mcfpp.language.ContextBuiltins.isType(base) }
        return (own + inherited).distinctBy { Triple(it.name, it.kind, it.parameters.map { parameter -> parameter.typeName }) }
            .also { cache[type] = it }
    }

    fun schema(type: String, offset: Int): List<TemplateLiterals.Field>? = fields(type)?.map {
        TemplateLiterals.Field(it.name, it.typeName ?: "any", false, accessible(it, offset))
    }

    fun constructionError(type: String, offset: Int): String? {
        val name = resolveType(type.substringBefore('<').removeSuffix("?").trim())
        val definition = symbols.firstOrNull { it.name == name.substringAfterLast(':') && it.isDataTemplate &&
            (':' !in name || it.namespaceName == name.substringBeforeLast(':')) } ?: return null
        if (definition.isAbstract) return "Template `$type` cannot be instantiated"
        return null
    }

    companion object {
        fun splitTypes(text: String): List<String> {
            var depth = 0
            var start = 0
            val result = mutableListOf<String>()
            for ((index, char) in text.withIndex()) {
                if (char == '<' || char == '(' || char == '[' || char == '{') depth++
                if (char == '>' || char == ')' || char == ']' || char == '}') depth--
                if (char == ',' && depth == 0) { result += text.substring(start, index).trim(); start = index + 1 }
            }
            text.substring(start).trim().takeIf { it.isNotEmpty() }?.let(result::add)
            return result
        }
    }
}
