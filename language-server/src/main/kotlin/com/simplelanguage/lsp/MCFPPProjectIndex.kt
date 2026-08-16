package com.simplelanguage.lsp

import com.google.gson.JsonElement
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import top.mcfpp.io.LibBinReader
import top.mcfpp.model.Generic
import top.mcfpp.model.Namespace
import top.mcfpp.model.compound.CompoundData
import top.mcfpp.model.compound.DataTemplate
import top.mcfpp.model.compound.GenericDataTemplate
import top.mcfpp.model.function.Function
import top.mcfpp.model.function.NativeFunction
import top.mcfpp.model.scope.GlobalScope
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.jar.JarFile
import java.util.zip.ZipFile
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

data class MCFPPProjectConfig(
    val configFile: Path?,
    val root: Path,
    val sourcePath: Path,
    val rootNamespace: String,
    val includes: List<Path>,
    val jars: List<Path>,
    val targetPath: Path?,
    val ignoreStdLib: Boolean
)

data class MCFPPProjectSnapshot(
    val fileAnalyses: Map<String, MCFPPDocumentAnalysis> = emptyMap(),
    val librarySymbols: List<MCFPPSymbol> = emptyList(),
    val configFiles: List<String> = emptyList()
)

private data class CachedWorkspaceAnalysis(
    val modifiedAtMillis: Long,
    val size: Long,
    val projectNamespace: String,
    val analysis: MCFPPDocumentAnalysis
)

private data class CachedProjectLibraries(
    val signature: String,
    val symbols: List<MCFPPSymbol>
)

class MCFPPProjectIndex {
    @Volatile
    private var workspaceRoots: List<Path> = emptyList()

    @Volatile
    private var snapshot: MCFPPProjectSnapshot = MCFPPProjectSnapshot()

    @Volatile
    private var indexedProjects: List<MCFPPProjectConfig> = emptyList()

    private var workspaceFileCache: Map<Path, CachedWorkspaceAnalysis> = emptyMap()
    private var projectLibraryCache: Map<Path, CachedProjectLibraries> = emptyMap()

    fun setWorkspaceRoots(rootUris: List<String>) {
        workspaceRoots = rootUris.mapNotNull(::uriToPath).distinct()
    }

    @Synchronized
    fun refresh(openDocuments: Map<String, String>): MCFPPProjectSnapshot {
        val projects = workspaceRoots.flatMap(::discoverProjects)
        val fileAnalyses = linkedMapOf<String, MCFPPDocumentAnalysis>()
        val nextFileCache = linkedMapOf<Path, CachedWorkspaceAnalysis>()
        val nextLibraryCache = linkedMapOf<Path, CachedProjectLibraries>()
        val librarySymbols = mutableListOf<MCFPPSymbol>()
        val configFiles = mutableListOf<String>()

        projects.forEach { project ->
            project.configFile?.toUri()?.toString()?.let(configFiles::add)
            collectWorkspaceFiles(project, openDocuments).forEach { file ->
                val uri = file.toUri().toString()
                runCatching {
                    val normalizedFile = file.toAbsolutePath().normalize()
                    val attributes = Files.readAttributes(normalizedFile, BasicFileAttributes::class.java)
                    val projectNamespace = projectNamespace(project, normalizedFile)
                    val cached = workspaceFileCache[normalizedFile] ?: nextFileCache[normalizedFile]
                    val analysis = if (
                        cached != null &&
                        cached.modifiedAtMillis == attributes.lastModifiedTime().toMillis() &&
                        cached.size == attributes.size() &&
                        cached.projectNamespace == projectNamespace
                    ) {
                        cached.analysis
                    } else {
                        withDefaultNamespace(
                            MCFPPDocumentIndex.analyze(uri, normalizedFile.readText()),
                            projectNamespace
                        )
                    }
                    fileAnalyses[uri] = analysis
                    nextFileCache[normalizedFile] = CachedWorkspaceAnalysis(
                        modifiedAtMillis = attributes.lastModifiedTime().toMillis(),
                        size = attributes.size(),
                        projectNamespace = projectNamespace,
                        analysis = analysis
                    )
                }
            }
            val libraryCacheKey = (project.configFile ?: project.root).toAbsolutePath().normalize()
            val librarySignature = librarySignature(project)
            val cachedLibraries = projectLibraryCache[libraryCacheKey]
            val projectLibrarySymbols = if (cachedLibraries?.signature == librarySignature) {
                cachedLibraries.symbols
            } else {
                runCatching { loadLibrarySymbols(project) }.getOrDefault(emptyList())
            }
            librarySymbols += projectLibrarySymbols
            nextLibraryCache[libraryCacheKey] = CachedProjectLibraries(librarySignature, projectLibrarySymbols)
        }

        workspaceFileCache = nextFileCache
        projectLibraryCache = nextLibraryCache
        indexedProjects = projects
        snapshot = MCFPPProjectSnapshot(
            fileAnalyses = fileAnalyses,
            librarySymbols = librarySymbols.distinctBy {
                listOf(
                    it.name,
                    it.kind,
                    it.namespaceName,
                    it.containerName,
                    it.receiverType,
                    it.parameters.joinToString(";") { parameter ->
                        listOf(parameter.isReadOnly, parameter.typeName, parameter.hasDefault, parameter.isStatic).joinToString(":")
                    }
                ).joinToString("|")
            },
            configFiles = configFiles.distinct()
        )
        return snapshot
    }

    fun snapshot(): MCFPPProjectSnapshot = snapshot

    fun namespaceForUri(uri: String): String? {
        val path = uriToPath(uri)?.toAbsolutePath()?.normalize() ?: return null
        val project = indexedProjects
            .filter { path.startsWith(it.sourcePath.toAbsolutePath().normalize()) }
            .maxByOrNull { it.sourcePath.nameCount }
            ?: return null
        return projectNamespace(project, path)
    }

    fun isRelevantWorkspaceChange(uri: String): Boolean {
        val path = uriToPath(uri)?.toAbsolutePath()?.normalize() ?: return false
        return workspaceRoots.any { workspaceRoot ->
            val root = workspaceRoot.toAbsolutePath().normalize()
            if (!path.startsWith(root)) {
                return@any false
            }
            when (path.extension.lowercase()) {
                "mcfpp", "mclib", "jar", "zip" -> true
                "json" -> path.parent == root
                else -> false
            }
        }
    }

    internal fun discoverProjects(root: Path): List<MCFPPProjectConfig> {
        if (!root.isDirectory()) {
            return emptyList()
        }
        val configFiles = Files.list(root).use { paths ->
            paths.filter { it.isRegularFile() && it.extension.equals("json", ignoreCase = true) }
                .filter { path ->
                    runCatching {
                        val text = path.readText()
                        listOf("namespace", "targetPath", "sourcePath", "compileArgs", "include", "includes", "jar", "jars").any { key ->
                            text.contains("\"$key\"")
                        }
                    }.getOrDefault(false)
                }
                .toList()
        }

        if (configFiles.isEmpty()) {
            val gradleSource = root.resolve("src/main/mcfpp")
            val sourcePath = if (gradleSource.exists()) gradleSource else root
            return listOf(
                MCFPPProjectConfig(
                    configFile = null,
                    root = root,
                    sourcePath = sourcePath,
                    rootNamespace = root.name,
                    includes = emptyList(),
                    jars = emptyList(),
                    targetPath = null,
                    ignoreStdLib = false
                )
            )
        }

        return configFiles.map(::parseConfig)
    }

    private fun parseConfig(configFile: Path): MCFPPProjectConfig {
        val root = configFile.parent
        val json = com.google.gson.JsonParser.parseString(configFile.readText()).asJsonObject
        val compileArgs = readStringList(json.get("compileArgs"))
        val sourcePath = json.get("sourcePath")?.asString
            ?.let { resolveProjectPath(root, it.replace("/**", "")) }
            ?: root
        val includes = sequenceOf("includes", "include")
            .flatMap { key -> readStringList(json.get(key)).asSequence() }
            .map { resolveProjectPath(root, it) }
            .distinct()
            .toList()
        val jars = sequenceOf("jars", "jar")
            .flatMap { key -> readStringList(json.get(key)).asSequence() }
            .map { resolveProjectPath(root, it) }
            .distinct()
            .toList()
        return MCFPPProjectConfig(
            configFile = configFile,
            root = root,
            sourcePath = sourcePath,
            rootNamespace = json.get("namespace")?.asString ?: root.name,
            includes = includes,
            jars = jars,
            targetPath = json.get("targetPath")?.asString?.let { resolveProjectPath(root, it) },
            ignoreStdLib = compileArgs.any { it == "-ignoreStdLib" }
        )
    }

    private fun readStringList(element: JsonElement?): List<String> {
        if (element == null || element.isJsonNull) {
            return emptyList()
        }
        if (element.isJsonArray) {
            return element.asJsonArray.mapNotNull { item ->
                item?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            }
        }
        if (element.isJsonPrimitive) {
            return listOfNotNull(element.asString.takeIf { it.isNotBlank() })
        }
        return emptyList()
    }

    private fun collectWorkspaceFiles(project: MCFPPProjectConfig, openDocuments: Map<String, String>): List<Path> {
        if (!project.sourcePath.exists()) {
            return emptyList()
        }
        return Files.walk(project.sourcePath).use { paths ->
            paths.filter { it.isRegularFile() && it.extension.equals("mcfpp", ignoreCase = true) }
                .filter { it.toUri().toString() !in openDocuments }
                .toList()
        }
    }

    private fun loadLibrarySymbols(project: MCFPPProjectConfig): List<MCFPPSymbol> {
        return synchronized(GlobalScope) {
            GlobalScope.init()
            if (!project.ignoreStdLib) {
                loadFromStream(this::class.java.classLoader.getResourceAsStream("datapack/bin.mclib"))
            }
            project.includes.forEach { include ->
                when {
                    include.isDirectory() -> loadFromDirectory(include)
                    include.extension.equals("jar", ignoreCase = true) -> loadFromJar(include)
                    include.extension.equals("zip", ignoreCase = true) -> loadFromZip(include)
                }
            }
            project.jars.forEach { jar ->
                when {
                    jar.extension.equals("jar", ignoreCase = true) -> loadFromJar(jar)
                    jar.extension.equals("zip", ignoreCase = true) -> loadFromZip(jar)
                }
            }
            GlobalScope.stdNamespaces.values.flatMap { namespaceSymbols(it, project.configFile?.toUri()?.toString(), true) } +
                GlobalScope.libNamespaces.values.flatMap { namespaceSymbols(it, project.configFile?.toUri()?.toString(), false) }
        }
    }

    internal fun projectNamespace(project: MCFPPProjectConfig, file: Path): String {
        val sourceRoot = project.sourcePath.toAbsolutePath().normalize()
        val fileParent = file.toAbsolutePath().normalize().parent ?: sourceRoot
        val relativeParent = runCatching { sourceRoot.relativize(fileParent) }.getOrNull()
        if (relativeParent == null || relativeParent.nameCount == 0) {
            return project.rootNamespace
        }
        val suffix = relativeParent
            .joinToString(".") { it.toString() }
            .toMcfppSnakeCase()
        return "${project.rootNamespace}.$suffix"
    }

    private fun withDefaultNamespace(analysis: MCFPPDocumentAnalysis, defaultNamespace: String): MCFPPDocumentAnalysis {
        if (analysis.namespaceName != null) {
            return analysis
        }
        fun MCFPPSymbol.withNamespace(): MCFPPSymbol =
            if (namespaceName == null) copy(namespaceName = defaultNamespace) else this

        val symbols = analysis.symbols.map { it.withNamespace() }
        return analysis.copy(
            namespaceName = defaultNamespace,
            symbols = symbols,
            typeMembers = analysis.typeMembers.mapValues { (_, members) -> members.map { it.withNamespace() } },
            topLevelSymbols = analysis.topLevelSymbols.map { it.withNamespace() },
            declarationsByName = symbols.groupBy { it.name }
        )
    }

    private fun String.toMcfppSnakeCase(): String {
        return buildString(length + 4) {
            this@toMcfppSnakeCase.forEachIndexed { index, char ->
                when {
                    char.isLowerCase() || char.isDigit() || char in "_-." -> append(char)
                    char.isUpperCase() -> {
                        if (index > 0) append('_')
                        append(char.lowercaseChar())
                    }
                    else -> append("u${char.code.toString(16)}")
                }
            }
        }
    }

    private fun librarySignature(project: MCFPPProjectConfig): String {
        return buildList {
            add("stdlib=${!project.ignoreStdLib}")
            (project.includes + project.jars)
                .map { it.toAbsolutePath().normalize() }
                .distinct()
                .forEach { path ->
                    add(path.toString())
                    if (path.isDirectory()) {
                        runCatching {
                            Files.walk(path).use { paths ->
                                paths.filter { it.isRegularFile() && it.extension.equals("mclib", ignoreCase = true) }
                                    .sorted()
                                    .forEach { add(fileFingerprint(it)) }
                            }
                        }
                    } else {
                        add(fileFingerprint(path))
                    }
                }
        }.joinToString("|")
    }

    private fun fileFingerprint(path: Path): String {
        if (!path.isRegularFile()) {
            return "${path.toAbsolutePath().normalize()}:missing"
        }
        return runCatching {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
            "${path.toAbsolutePath().normalize()}:${attributes.lastModifiedTime().toMillis()}:${attributes.size()}"
        }.getOrElse { "${path.toAbsolutePath().normalize()}:unreadable" }
    }

    private fun loadFromDirectory(path: Path) {
        val mclibFiles = Files.walk(path).use { paths ->
            paths.filter { it.isRegularFile() && it.extension.equals("mclib", ignoreCase = true) }
                .toList()
        }
        for (mclibFile in mclibFiles) {
            mclibFile.inputStream().use(::loadFromStream)
        }
    }

    private fun loadFromJar(path: Path) {
        if (!path.exists()) {
            return
        }
        JarFile(path.toFile()).use { jar ->
            jar.getJarEntry("datapack/bin.mclib")?.let { entry ->
                jar.getInputStream(entry).use(::loadFromStream)
            }
        }
    }

    private fun loadFromZip(path: Path) {
        if (!path.exists()) {
            return
        }
        ZipFile(path.toFile()).use { zip ->
            zip.getEntry("datapack/bin.mclib")?.let { entry ->
                zip.getInputStream(entry).use(::loadFromStream)
            }
        }
    }

    private fun loadFromStream(stream: InputStream?) {
        if (stream == null) {
            return
        }
        runCatching {
            LibBinReader.readFromStream(stream)
        }
    }

    private fun namespaceSymbols(namespace: Namespace, uri: String?, standard: Boolean): List<MCFPPSymbol> {
        val baseUri = uri ?: ""
        val baseRange = Range(Position(0, 0), Position(0, 0))
        val symbols = mutableListOf<MCFPPSymbol>()
        namespace.scope.forEachFunction { function ->
            val returnType = runCatching { function.returnType.typeName }.getOrNull()
            symbols.add(MCFPPSymbol(
                uri = baseUri,
                name = function.identifier,
                kind = SymbolKind.Function,
                range = baseRange,
                detail = returnType,
                typeName = returnType,
                topLevel = true,
                namespaceName = namespace.identifier,
                isExternal = true,
                isStdlib = standard,
                parameters = externalFunctionParameters(function)
            ))
            null
        }
        namespace.scope.forEachTemplate { template ->
            symbols.addAll(externalTemplateSymbols(baseUri, baseRange, namespace.identifier, template, standard))
            null
        }
        namespace.scope.forEachInterface { template ->
            symbols.addAll(externalTemplateSymbols(baseUri, baseRange, namespace.identifier, template, standard))
            null
        }
        namespace.scope.forEachEnum { enumType ->
            symbols.add(MCFPPSymbol(
                uri = baseUri,
                name = enumType.identifier,
                kind = SymbolKind.Enum,
                range = baseRange,
                typeName = enumType.identifier,
                topLevel = true,
                namespaceName = namespace.identifier,
                isExternal = true,
                isStdlib = standard
            ))
            enumType.members.values.forEach { member ->
                symbols.add(MCFPPSymbol(
                    uri = baseUri,
                    name = member.identifier,
                    kind = SymbolKind.EnumMember,
                    range = baseRange,
                    typeName = enumType.identifier,
                    containerName = enumType.identifier,
                    namespaceName = namespace.identifier,
                    isExternal = true,
                    isStdlib = standard
                ))
            }
            null
        }
        namespace.scope.forEachObject { obj ->
            symbols.addAll(externalCompoundSymbols(baseUri, baseRange, namespace.identifier, obj, standard))
        }
        return symbols
    }

    private fun externalCompoundSymbols(
        uri: String,
        range: Range,
        namespace: String,
        compound: CompoundData,
        standard: Boolean,
        parameters: List<MCFPPFunctionParameter> = emptyList()
    ): List<MCFPPSymbol> {
        val symbols = mutableListOf<MCFPPSymbol>()
        symbols += MCFPPSymbol(
            uri = uri,
            name = compound.identifier,
            kind = if (compound is DataTemplate && compound.isInterface) SymbolKind.Interface else SymbolKind.Class,
            range = range,
            typeName = compound.identifier,
            topLevel = true,
            superTypes = compound.parent.map { it.namespaceID },
            namespaceName = namespace,
            isExternal = true,
            isStdlib = standard,
            parameters = parameters
        )
        compound.scope.forEachFunction { function ->
            val returnType = runCatching { function.returnType.typeName }.getOrNull()
            symbols.add(MCFPPSymbol(
                uri = uri,
                name = function.identifier,
                kind = SymbolKind.Method,
                range = range,
                detail = returnType,
                typeName = returnType,
                containerName = compound.identifier,
                namespaceName = namespace,
                isExternal = true,
                isStdlib = standard,
                parameters = externalFunctionParameters(function),
                accessModifier = externalAccessModifier(runCatching { function.accessModifier.name }.getOrNull()),
                isStatic = runCatching { function.isStatic }.getOrDefault(false)
            ))
            null
        }
        compound.scope.allVars.forEach { variable ->
            val variableType = runCatching { variable.type.typeName }.getOrNull()
            symbols += MCFPPSymbol(
                uri = uri,
                name = variable.identifier,
                kind = SymbolKind.Field,
                range = range,
                detail = variableType,
                typeName = variableType,
                containerName = compound.identifier,
                namespaceName = namespace,
                isExternal = true,
                isStdlib = standard,
                accessModifier = externalAccessModifier(runCatching { variable.accessModifier.name }.getOrNull()),
                isStatic = runCatching { variable.isStatic }.getOrDefault(false)
            )
        }
        return symbols
    }

    private fun externalTemplateSymbols(uri: String, range: Range, namespace: String, template: DataTemplate, standard: Boolean): List<MCFPPSymbol> {
        val readOnlyParameters = externalTemplateReadOnlyParameters(template)
        val symbols = externalCompoundSymbols(uri, range, namespace, template, standard, readOnlyParameters).toMutableList()
        template.constructors.forEach { constructor ->
            symbols += MCFPPSymbol(
                uri = uri,
                name = template.identifier,
                kind = SymbolKind.Constructor,
                range = range,
                detail = template.identifier,
                typeName = template.identifier,
                containerName = template.identifier,
                namespaceName = namespace,
                isExternal = true,
                isStdlib = standard,
                parameters = readOnlyParameters + externalFunctionParameters(constructor),
                accessModifier = externalAccessModifier(runCatching { constructor.accessModifier.name }.getOrNull())
            )
        }
        return symbols
    }

    private fun externalTemplateReadOnlyParameters(template: DataTemplate): List<MCFPPFunctionParameter> {
        val genericTemplate = template as? GenericDataTemplate ?: return emptyList()
        return genericTemplate.readOnlyParams.map { parameter ->
            MCFPPFunctionParameter(
                name = parameter.identifier,
                typeName = parameter.typeIdentifier,
                isReadOnly = true
            )
        }
    }

    private fun externalFunctionParameters(function: Function): List<MCFPPFunctionParameter> {
        val readOnly = when (function) {
            is Generic<*> -> function.readOnlyParams
            is NativeFunction -> function.readOnlyParams
            else -> emptyList()
        }
        return buildList {
            readOnly.forEach { parameter ->
                add(MCFPPFunctionParameter(
                    name = parameter.identifier,
                    typeName = parameter.typeName,
                    isStatic = parameter.isStatic,
                    hasDefault = parameter.hasDefault,
                    defaultValue = parameter.defaultVar?.toString(),
                    isReadOnly = true
                ))
            }
            function.normalParams.forEach { parameter ->
                add(MCFPPFunctionParameter(
                    name = parameter.identifier,
                    typeName = parameter.typeName,
                    isStatic = parameter.isStatic,
                    hasDefault = parameter.hasDefault,
                    defaultValue = parameter.defaultVar?.toString(),
                    isReadOnly = false
                ))
            }
        }
    }

    private fun externalAccessModifier(name: String?): MCFPPAccessModifier = when (name) {
        "PRIVATE", "COMPILE_PRIVATE" -> MCFPPAccessModifier.PRIVATE
        "PROTECTED" -> MCFPPAccessModifier.PROTECTED
        else -> MCFPPAccessModifier.PUBLIC
    }

    private fun resolveProjectPath(root: Path, rawPath: String): Path {
        return runCatching {
            val candidate = Paths.get(rawPath)
            if (candidate.isAbsolute) candidate.normalize() else root.resolve(rawPath).normalize()
        }.getOrDefault(root.resolve(rawPath).normalize())
    }

    private fun uriToPath(uri: String): Path? {
        return runCatching {
            when {
                uri.startsWith("file:/") -> Paths.get(URI(uri))
                else -> Paths.get(uri)
            }
        }.getOrNull()
    }
}
