package top.mcfpp.lsp

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import java.nio.file.Files

/** Read builtin native value-member overloads from the exported compiler metadata JSON. */
internal object MCFPPValueMembers {
    private data class ExportedMember(
        val caller: String = "",
        val name: String = "",
        val returnType: String = "void",
        val readOnlyParams: List<String> = emptyList(),
        val normalParams: List<String> = emptyList(),
        val parameterNames: List<String> = emptyList(),
        val documentation: String = "",
        val requiresConstantReceiver: Boolean = false,
        val inferTypesFromArguments: Map<String, Int> = emptyMap(),
    )

    private val declarations: Map<String, List<MCFPPSymbol>> by lazy {
        val stream = MCFPPValueMembers::class.java.classLoader.getResourceAsStream("editor/value-members.json")
            ?: return@lazy emptyMap()
        val type = object : TypeToken<List<ExportedMember>>() {}.type
        val entries: List<ExportedMember> = stream.bufferedReader(Charsets.UTF_8).use { reader ->
            Gson().fromJson(reader, type) ?: emptyList()
        }
        val lines = mutableListOf("/// MCFPP built-in members")
        val source = Files.createTempDirectory("mcfpp-builtins-").apply { toFile().deleteOnExit() }
            .resolve("value-members.mcfpp").apply { toFile().deleteOnExit() }
        val uri = source.toUri().toString()
        val symbols = entries.map { entry ->
            val parameters = entry.readOnlyParams.map { MCFPPFunctionParameter(null, it, isReadOnly = true) } +
                entry.normalParams.mapIndexed { i, type -> MCFPPFunctionParameter(entry.parameterNames.getOrNull(i), type) }
            val owner = when (entry.caller) {
                "NBTRef", "list", "ImmutableList" -> "${entry.caller}<T>"
                else -> entry.caller
            }
            val prefix = "func $owner."
            val symbol = MCFPPSymbol(
                uri, entry.name, SymbolKind.Method, Range(Position(lines.size + 1, prefix.length),
                    Position(lines.size + 1, prefix.length + entry.name.length)),
                containerName = entry.caller, receiverType = entry.caller, typeName = entry.returnType,
                detail = entry.returnType, parameters = parameters, documentation = entry.documentation,
                isStdlib = true, isExternal = true, isSynthetic = true, namespaceName = "mcfpp.lang",
                requiresConstantReceiver = entry.requiresConstantReceiver,
                inferTypesFromArguments = entry.inferTypesFromArguments,
            )
            lines.add("/// ${entry.documentation}")
            lines.add(prefix + buildSignatureLabel(symbol))
            symbol
        }
        Files.write(source, lines)
        symbols.groupBy { it.receiverType.orEmpty() }
    }

    private val typeIdentifier = Regex("""\b[A-Za-z_]\w*\b""")

    fun callbackInput(type: String, name: String): String? =
        members(type).singleOrNull { it.name == name }?.parameters?.singleOrNull()?.typeName
            ?.substringBefore("->")?.trim()?.removeSurrounding("(", ")")

    fun members(type: String): List<MCFPPSymbol> {
        val owner = type.substringBefore('<').substringAfterLast(':')
        val members = declarations[if (owner == "selector") "entity" else owner].orEmpty()
        if ('<' !in type) return members
        val element = type.substringAfter('<').removeSuffix(">")
        return members.map { member ->
            substitute(member.copy(receiverType = type, containerName = type), mapOf("T" to element))
        }
    }

    fun substitute(member: MCFPPSymbol, types: Map<String, String>): MCFPPSymbol {
        if (types.isEmpty()) return member
        fun bind(signature: String): String = typeIdentifier.replace(signature) { types[it.value] ?: it.value }
        return member.copy(typeName = member.typeName?.let(::bind), detail = member.detail?.let(::bind),
            parameters = member.parameters.map { it.copy(typeName = bind(it.typeName)) })
    }
}
