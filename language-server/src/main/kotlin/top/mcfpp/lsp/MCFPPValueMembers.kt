package top.mcfpp.lsp

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import top.mcfpp.library.model.NativeBinding
import top.mcfpp.library.model.MacroParameterMode

/** Read builtin native value-member overloads from the exported compiler metadata JSON. */
internal object MCFPPValueMembers {
    private data class Source(val path: String, val line: Int, val column: Int, val length: Int) {
        fun location(prefix: String = sourceUriPrefix) = Location(prefix + path, Range(Position(line, column), Position(line, column + length)))
    }
    private data class JvmMethod(val owner: String, val method: String, val parameterTypes: List<String>, val returnType: String)
    private data class ExportedImplementation(val binding: JvmMethod, val source: Source)
    private data class MacroParameter(val name: String?, val type: String, val mode: MacroParameterMode,
                                      val callbackParameters: List<MacroParameter>) {
        fun parameter(acceptsFunctionValues: Boolean = false): MCFPPFunctionParameter =
            MCFPPFunctionParameter(name, type, macroMode = mode,
                macroCallbackParameters = callbackParameters.map { it.parameter() },
                acceptsFunctionValues = acceptsFunctionValues && mode == MacroParameterMode.INLINE)
    }

    private data class ExportedMember(
        val caller: String = "",
        val name: String = "",
        val receiverType: String? = null,
        val namespace: String? = null,
        val returnType: String = "void",
        val readOnlyParams: List<String> = emptyList(),
        val normalParams: List<String> = emptyList(),
        val parameterNames: List<String> = emptyList(),
        val documentation: String = "",
        val requiresConstantReceiver: Boolean = false,
        val inferTypesFromArguments: Map<String, Int> = emptyMap(),
        val receiverParameters: List<String>? = null,
        val typeParameters: List<String>? = null,
        val inlineCallback: Boolean = false,
        val implementation: Source? = null,
        val declaration: Source? = null,
        val macroParameters: List<MacroParameter>? = null,
        val acceptsFunctionValues: Boolean = false,
        val jvmBinding: JvmMethod? = null,
    )

    private data class Declaration(val symbol: MCFPPSymbol, val receiverParameters: List<String>)
    private data class ExportedCatalogue(
        val formatVersion: Int,
        val members: List<ExportedMember>,
        val receiverAliases: Map<String, String>,
        val implementations: List<ExportedImplementation>? = null,
    )

    private const val sourceUriPrefix = "mcfpp-kotlin-source:/"
    private const val stdlibUriPrefix = "mcfpp-stdlib-declaration:/"
    private val sourceDirectory by lazy {
        Files.createTempDirectory("mcfpp-kotlin-sources-").apply { toFile().deleteOnExit() }
    }
    private val sourceFiles = ConcurrentHashMap<String, Path>()
    private val localRoots by lazy {
        System.getProperty("mcfpp.kotlin.source.roots").orEmpty().split(File.pathSeparatorChar)
            .filter(String::isNotBlank).mapNotNull { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }
    }
    private val loader get() = MCFPPValueMembers::class.java.classLoader

    private val catalogue: ExportedCatalogue by lazy {
        val stream = loader.getResourceAsStream("editor/value-members.json")
        val empty = ExportedCatalogue(2, emptyList(), mapOf("selector" to "entity"))
        val json = stream?.bufferedReader(Charsets.UTF_8)?.use(JsonParser::parseReader) ?: return@lazy empty
        if (json.isJsonArray) {
            val type = object : TypeToken<List<ExportedMember>>() {}.type
            empty.copy(members = Gson().fromJson(json, type))
        } else {
            Gson().fromJson(json, ExportedCatalogue::class.java)?.takeIf { it.formatVersion == 2 } ?: empty
        }
    }

    private val declarations: Map<String, List<Declaration>> by lazy {
        catalogue.members.mapIndexed { index, entry ->
            val parameters = entry.readOnlyParams.map { MCFPPFunctionParameter(null, it, isReadOnly = true) } +
                entry.normalParams.mapIndexed { i, type ->
                    entry.macroParameters?.getOrNull(i)?.parameter(entry.acceptsFunctionValues) ?:
                    MCFPPFunctionParameter(entry.parameterNames.getOrNull(i)?.takeIf(String::isNotEmpty), type,
                        macroMode = MacroParameterMode.INLINE.takeIf { entry.inlineCallback && callbackSignature(type) != null })
                }
            val implementation = entry.implementation?.location()
            val declaration = entry.declaration?.location(stdlibUriPrefix)
            val symbol = MCFPPSymbol(
                declaration?.uri ?: "mcfpp-builtin:/" + entry.caller, entry.name,
                if (entry.caller.isEmpty()) SymbolKind.Function else SymbolKind.Method,
                declaration?.range ?: Range(Position(index, 0), Position(index, entry.name.length)),
                containerName = entry.caller.ifEmpty { null }, receiverType = entry.receiverType ?: entry.caller.ifEmpty { null }, typeName = entry.returnType,
                topLevel = entry.caller.isEmpty(),
                detail = entry.returnType, parameters = parameters, documentation = entry.documentation,
                isStdlib = true, isExternal = true, isSynthetic = true, namespaceName = entry.namespace ?: "mcfpp.lang",
                requiresConstantReceiver = entry.requiresConstantReceiver,
                inferTypesFromArguments = entry.inferTypesFromArguments,
                nativeImplementation = implementation,
                isNative = entry.jvmBinding != null,
                isMacro = entry.macroParameters != null,
                typeParameters = entry.typeParameters.orEmpty(),
            )
            Declaration(symbol, entry.receiverParameters ?: listOf("T"))
        }.groupBy { it.symbol.containerName.orEmpty() }
    }

    private val implementations by lazy {
        catalogue.implementations.orEmpty().associate { it.binding to it.source } +
            catalogue.members.mapNotNull { entry -> entry.jvmBinding?.let { binding -> entry.implementation?.let { binding to it } } }
    }

    fun implementation(binding: NativeBinding): Location? =
        implementations[JvmMethod(binding.ownerClass, binding.method, binding.jvmParameters, binding.jvmResult)]?.location()

    fun resolveImplementation(location: Location): Location? =
        resolveSource(location, sourceUriPrefix, "kotlin-sources", ".kt", localRoots)

    fun resolveDeclaration(location: Location): Location? =
        resolveSource(location, stdlibUriPrefix, "stdlib-sources", ".mcfpp", emptyList())

    private fun resolveSource(location: Location, prefix: String, directory: String, extension: String, roots: List<Path>): Location? {
        if (!location.uri.startsWith(prefix)) return location
        val source = location.uri.removePrefix(prefix)
        if (!source.endsWith(extension)) return null
        val resource = "$directory/$source"
        val file = sourceFiles[resource] ?: runCatching {
            sourceFiles.computeIfAbsent(resource) {
                val target = sourceDirectory.resolve(resource).normalize()
                require(target.startsWith(sourceDirectory))
                // Preserve the Kotlin filename and package path for the IDE.
                val parents = generateSequence(target.parent) { it.parent }
                    .takeWhile { it != sourceDirectory }.toList()
                Files.createDirectories(target.parent)
                parents.asReversed().forEach { it.toFile().deleteOnExit() }
                loader.getResourceAsStream("editor/$resource")?.use { Files.copy(it, target) }
                    ?: error("Missing editor source $resource")
                target.apply { toFile().deleteOnExit() }
            }
        }.getOrNull() ?: return null
        val local = roots.firstNotNullOfOrNull { root ->
            val candidate = root.resolve(source).normalize()
            candidate.takeIf { it.startsWith(root) && Files.isRegularFile(it) && runCatching {
                Files.mismatch(it, file) == -1L
            }.getOrDefault(false) }
        }
        return Location((local ?: file).toUri().toString(), location.range)
    }

    private val typeIdentifier = Regex("""\b[A-Za-z_]\w*\b""")

    fun globals(): List<MCFPPSymbol> = declarations[""].orEmpty().map { it.symbol }

    val genericMethodNames by lazy { declarations.values.flatten().map { it.symbol }.filter { it.typeParameters.isNotEmpty() }.map { it.name }.toSet() }

    fun callbackInput(type: String, name: String, argumentIndex: Int): List<String>? =
        members(type).filter { it.name == name }.mapNotNull { it.parameters.getOrNull(argumentIndex) }
            .mapNotNull { callbackSignature(it.typeName)?.first }.distinct().singleOrNull()

    fun callbackSignature(type: String): Pair<List<String>, String>? {
        val start = type.indexOf('(')
        if (start != 0) return null
        var depth = 0
        val end = type.indices.firstOrNull { index ->
            if (type[index] == '(') depth++
            type[index] == ')' && --depth == 0
        } ?: return null
        val tail = type.substring(end + 1).trimStart()
        if (!tail.startsWith("->")) return null
        return MCFPPTemplateLiterals.splitTypes(type.substring(1, end)) to tail.drop(2).trim()
    }

    fun members(type: String): List<MCFPPSymbol> {
        val base = type.substringBefore('<').trim()
        val owner = base.substringAfterLast(':')
        val members = declarations[base].orEmpty().ifEmpty {
            declarations[catalogue.receiverAliases[owner] ?: owner].orEmpty()
        } + if (owner == "void") emptyList() else declarations["*"].orEmpty()
        val arguments = if ('<' in type) MCFPPTemplateLiterals.splitTypes(type.substringAfter('<').removeSuffix(">")) else emptyList()
        return members.map { declaration ->
            substitute(declaration.symbol.copy(receiverType = type, containerName = type),
                declaration.receiverParameters.zip(arguments).toMap() + ("Self" to type))
        }
    }

    fun instantiate(
        member: MCFPPSymbol,
        argumentCount: Int,
        argumentType: (Int, String) -> String?,
    ): MCFPPSymbol {
        if (member.typeParameters.isEmpty() && member.inferTypesFromArguments.isEmpty()) return member
        val substitutions = linkedMapOf<String, String>()
        val parameters = member.parameters.filterNot { it.isReadOnly }
        member.inferTypesFromArguments.forEach { (name, index) ->
            parameters.getOrNull(index)?.let { argumentType(index, it.typeName) }?.let { substitutions[name] = it }
        }
        fun bind(expected: String, actual: String) {
            if (expected in member.typeParameters) {
                if (actual != expected && actual != "?") substitutions.putIfAbsent(expected, actual)
                return
            }
            val expectedCallback = callbackSignature(expected)
            val actualCallback = callbackSignature(actual)
            if (expectedCallback != null && actualCallback != null && expectedCallback.first.size == actualCallback.first.size) {
                expectedCallback.first.zip(actualCallback.first).forEach { (formal, supplied) -> bind(formal, supplied) }
                bind(expectedCallback.second, actualCallback.second)
            } else if ('<' in expected && '<' in actual && expected.substringBefore('<').trim() == actual.substringBefore('<').trim()) {
                val expectedTypes = MCFPPTemplateLiterals.splitTypes(expected.substringAfter('<').removeSuffix(">"))
                val actualTypes = MCFPPTemplateLiterals.splitTypes(actual.substringAfter('<').removeSuffix(">"))
                if (expectedTypes.size == actualTypes.size) expectedTypes.zip(actualTypes).forEach { (formal, supplied) -> bind(formal, supplied) }
            }
        }
        for ((index, parameter) in parameters.take(argumentCount).withIndex()) {
            val expected = parameter.typeName
            val instantiated = typeIdentifier.replace(expected) { substitutions[it.value] ?: it.value }
            argumentType(index, instantiated)?.let { bind(expected, it) }
        }
        return substitute(member, substitutions)
    }

    fun substitute(member: MCFPPSymbol, types: Map<String, String>): MCFPPSymbol {
        if (types.isEmpty()) return member
        fun bind(signature: String): String = typeIdentifier.replace(signature) { types[it.value] ?: it.value }
        fun parameter(value: MCFPPFunctionParameter): MCFPPFunctionParameter = value.copy(
            typeName = bind(value.typeName), macroCallbackParameters = value.macroCallbackParameters.map(::parameter),
        )
        return member.copy(typeName = member.typeName?.let(::bind), detail = member.detail?.let(::bind),
            parameters = member.parameters.map(::parameter), typeParameters = member.typeParameters - types.keys)
    }
}
