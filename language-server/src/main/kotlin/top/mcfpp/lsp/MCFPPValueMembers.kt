package top.mcfpp.lsp

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind

/** Read builtin native value-member overloads from the exported compiler metadata JSON. */
internal object MCFPPValueMembers {
    private data class ExportedMember(
        val caller: String = "",
        val name: String = "",
        val returnType: String = "void",
        val readOnlyParams: List<String> = emptyList(),
        val normalParams: List<String> = emptyList(),
    )

    private fun method(owner: String, name: String, result: String, params: List<MCFPPFunctionParameter>): MCFPPSymbol =
        MCFPPSymbol(
            "mcfpp-builtin:/$owner", name, SymbolKind.Method, Range(Position(), Position()),
            containerName = owner, receiverType = owner, typeName = result, detail = result, parameters = params,
            isStdlib = true, isExternal = true, isSynthetic = true, namespaceName = "mcfpp.lang"
        )

    private val native: Map<String, List<MCFPPSymbol>> by lazy {
        val stream = MCFPPValueMembers::class.java.classLoader.getResourceAsStream("editor/value-members.json")
            ?: return@lazy emptyMap()
        val type = object : TypeToken<List<ExportedMember>>() {}.type
        val entries: List<ExportedMember> = stream.bufferedReader(Charsets.UTF_8).use { reader ->
            Gson().fromJson(reader, type) ?: emptyList()
        }
        entries.map { entry ->
            val parameters = entry.readOnlyParams.map { MCFPPFunctionParameter(null, it, isReadOnly = true) } +
                entry.normalParams.map { MCFPPFunctionParameter(null, it) }
            method(entry.caller, entry.name, entry.returnType, parameters)
        }.groupBy { it.receiverType.orEmpty() }
    }

    fun members(type: String): List<MCFPPSymbol> {
        val owner = type.substringBefore('<').substringAfterLast(':')
        val element = type.substringAfter('<', "any").removeSuffix(">")
        fun intrinsic(name: String, result: String, vararg params: Pair<String, String>) =
            method(type, name, result, params.map { (name, type) -> MCFPPFunctionParameter(name, type) })
        return when (owner) {
            "selector" -> native["entity"].orEmpty()
            "ScoreRef", "NBTRef" -> {
                val value = if (owner == "ScoreRef") "int" else element
                listOf(intrinsic("tryRead", "bool", "callback" to "($value)->void"), intrinsic("set", "void", "value" to value))
            }
            "list", "ImmutableList" -> listOf(
                intrinsic("map", "list<any>", "transform" to "($element)->any"),
                intrinsic("filter", "list<$element>", "predicate" to "($element)->bool"),
                intrinsic("fold", "any", "initial" to "any", "operation" to "(any,$element)->any")
            )
            else -> native[owner].orEmpty()
        }
    }
}
