package top.mcfpp.lsp

import com.google.gson.JsonElement
import org.eclipse.lsp4j.SymbolKind
import top.mcfpp.language.VersionPreprocessor
import top.mcfpp.language.ProjectConfiguration
import java.net.URI
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipFile
import kotlin.io.path.exists
import kotlin.io.path.extension
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
    val ignoreStdLib: Boolean,
    val targetVersion: String = VersionPreprocessor.DEFAULT_VERSION
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
    val targetVersion: String,
    val analysis: MCFPPDocumentAnalysis
)

private data class CachedProjectLibraries(
    val signature: String,
    val symbols: List<MCFPPSymbol>
)

private data class LibraryParameterKey(val readOnly: Boolean, val typeName: String, val hasDefault: Boolean)

private data class LibrarySymbolKey(
    val name: String,
    val kind: SymbolKind,
    val namespaceName: String?,
    val containerName: String?,
    val receiverType: String?,
    val parameters: List<LibraryParameterKey>
)

class MCFPPProjectIndex {
    private data class MniSymbols(val generation: Long, val symbols: List<MCFPPSymbol>)

    private val symbolPublicationLock = Any()
    private val mniSnapshots = linkedMapOf<String, MniSymbols>()
    private var diskLibrarySymbols = emptyList<MCFPPSymbol>()

    fun setMniSymbols(value: top.mcfpp.language.MniSymbolSnapshot) {
        val project = value.projectUri
        if (project.isNullOrBlank()) return
        synchronized(symbolPublicationLock) {
            val previous = mniSnapshots[project]
            if (previous != null && previous.generation > value.generation) return
            mniSnapshots[project] = MniSymbols(value.generation, value.toSymbols())
            snapshot = snapshot.copy(librarySymbols = mergedLibrarySymbols())
        }
    }
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
                        cached.projectNamespace == projectNamespace &&
                        cached.targetVersion == project.targetVersion
                    ) {
                        cached.analysis
                    } else {
                        withDefaultNamespace(
                            MCFPPDocumentIndex.analyze(uri, normalizedFile.readText(), project.targetVersion),
                            projectNamespace
                        ).withoutParseTree()
                    }
                    fileAnalyses[uri] = analysis
                    nextFileCache[normalizedFile] = CachedWorkspaceAnalysis(
                        modifiedAtMillis = attributes.lastModifiedTime().toMillis(),
                        size = attributes.size(),
                        projectNamespace = projectNamespace,
                        targetVersion = project.targetVersion,
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
                runCatching { loadLibrarySymbols(project) }
                    .onFailure { System.err.println("MCFPP library index: ${it.stackTraceToString()}") }
                    .getOrDefault(emptyList())
            }
            librarySymbols += projectLibrarySymbols
            nextLibraryCache[libraryCacheKey] = CachedProjectLibraries(librarySignature, projectLibrarySymbols)
        }

        workspaceFileCache = nextFileCache
        projectLibraryCache = nextLibraryCache
        indexedProjects = projects
        return synchronized(symbolPublicationLock) {
            diskLibrarySymbols = librarySymbols
            MCFPPProjectSnapshot(fileAnalyses, mergedLibrarySymbols(), configFiles.distinct()).also { snapshot = it }
        }
    }

    private fun mergedLibrarySymbols(): List<MCFPPSymbol> =
        (diskLibrarySymbols + mniSnapshots.values.flatMap { it.symbols }).distinctBy { symbol ->
            LibrarySymbolKey(
                symbol.name, symbol.kind, symbol.namespaceName, symbol.containerName, symbol.receiverType,
                symbol.parameters.map { LibraryParameterKey(it.isReadOnly, it.typeName, it.hasDefault) }
            )
        }

    fun snapshot(): MCFPPProjectSnapshot = snapshot

    fun namespaceForUri(uri: String): String? {
        val path = uriToPath(uri)?.toAbsolutePath()?.normalize() ?: return null
        val project = indexedProjects
            .filter { project -> sourceRoots(project).any { path.startsWith(it.toAbsolutePath().normalize()) } }
            .maxByOrNull { it.sourcePath.nameCount }
            ?: return null
        return projectNamespace(project, path)
    }

    fun targetVersionForUri(uri: String): String {
        val path = uriToPath(uri)?.toAbsolutePath()?.normalize() ?: return VersionPreprocessor.DEFAULT_VERSION
        return indexedProjects
            .filter { project -> sourceRoots(project).any { path.startsWith(it.toAbsolutePath().normalize()) } }
            .maxByOrNull { it.sourcePath.nameCount }
            ?.targetVersion ?: VersionPreprocessor.DEFAULT_VERSION
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
                "json", "toml", "lock" -> path.parent == root || path.fileName.toString() in setOf("mcfpp.toml", "mcfpp.json") ||
                    path.startsWith(root.resolve(".mcfpp")) || indexedProjects.any { path.startsWith(it.root.resolve(".mcfpp")) }
                else -> false
            }
        }
    }

    internal fun discoverProjects(root: Path): List<MCFPPProjectConfig> {
        if (!root.isDirectory()) {
            return emptyList()
        }
        if (Files.isRegularFile(root.resolve("mcfpp.toml"))) return listOf(parseConfig(root.resolve("mcfpp.toml")))
        val configFiles = Files.list(root).use { paths ->
            paths.filter { it.isRegularFile() && it.extension.equals("json", ignoreCase = true) }
                .filter { path ->
                    runCatching {
                        val text = path.readText()
                        listOf("namespace", "targetPath", "sourcePath", "compileArgs", "include", "includes", "jar", "jars", "version").any { key ->
                            text.contains("\"$key\"")
                        }
                    }.getOrDefault(false)
                }
                .toList()
        }

        if (configFiles.isEmpty()) {
            val nestedProjects = mutableListOf<MCFPPProjectConfig>()
            val generatedDirectories = setOf(".git", ".idea", ".gradle", ".mcfpp", "build", "out", "target", "node_modules")
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir == root) return FileVisitResult.CONTINUE
                    if (dir.name in generatedDirectories) return FileVisitResult.SKIP_SUBTREE
                    val config = dir.resolve("mcfpp.toml")
                    if (Files.isRegularFile(config)) {
                        nestedProjects += parseConfig(config)
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }
            })
            if (nestedProjects.isNotEmpty()) return nestedProjects
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
        val config = ProjectConfiguration.read(configFile)
        val modelFile = config.root().resolve(".mcfpp/ide-model.json")
        val libraries = runCatching {
            val model = com.google.gson.JsonParser.parseString(modelFile.readText()).asJsonObject
            model.getAsJsonArray("libraries").mapNotNull { it.asJsonObject.get("path")?.asString?.let(Path::of) }
        }.getOrDefault(emptyList())
        return MCFPPProjectConfig(configFile, config.root(), config.sourcePath(), config.namespace(),
            config.includes() + libraries, config.jars(), config.targetPath(), config.ignoreStdLib(), config.version())
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
        return sourceRoots(project).filter { it.isDirectory() }.flatMap { root ->
            Files.walk(root).use { paths ->
                paths.filter { it.isRegularFile() && it.extension.equals("mcfpp", ignoreCase = true) }
                    .filter { it.toUri().toString() !in openDocuments }
                    .toList()
            }
        }.distinct()
    }

    private fun sourceRoots(project: MCFPPProjectConfig): List<Path> =
        listOf(project.sourcePath, project.root.resolve("src/test/mcfpp")).distinct()

    private fun loadLibrarySymbols(project: MCFPPProjectConfig): List<MCFPPSymbol> {
        return buildList {
            val testApi = this@MCFPPProjectIndex.javaClass.classLoader.getResource("testing/assertions.mcfpp")
            if (testApi != null) {
                val analysis = testApi.openStream().bufferedReader(Charsets.UTF_8).use {
                    MCFPPDocumentIndex.analyze(testApi.toExternalForm(), it.readText(), project.targetVersion).withoutParseTree()
                }
                addAll(analysis.topLevelSymbols.filter { it.kind == SymbolKind.Function }.map { it.copy(isExternal = true) })
            }
            addAll(MCFPPContextBuiltins.symbols())
            if (!project.ignoreStdLib) {
                addAll(MCFPPEntityBuiltins.symbols(project.configFile?.toUri()?.toString() ?: project.root.toUri().toString()))
                addAll(MCFPPNbtBuiltins.symbols(project.configFile?.toUri()?.toString() ?: project.root.toUri().toString()))
                val stdlibBytes = readStdlibIndexBytes(project.targetVersion)
                if (stdlibBytes != null) {
                    addAll(librarySymbols(stdlibBytes, project.configFile?.toUri()?.toString() ?: "", true))
                }
            }
            (project.includes + project.jars).distinct().forEach { path ->
                if (!path.exists()) return@forEach
                val files = if (path.isDirectory()) Files.walk(path).use { entries -> entries.filter { it.isRegularFile() && it.extension == "mclib" }.toList() } else listOf(path)
                for (file in files) {
                    val bytes = when (file.extension.lowercase()) {
                        "mclib" -> Files.readAllBytes(file)
                        "jar", "zip" -> ZipFile(file.toFile()).use { zip ->
                            val entry = zip.getEntry("bin.mclib") ?: zip.getEntry("datapack/bin.mclib")
                            entry?.let { zip.getInputStream(it).use { stream -> stream.readBytes() } }
                        }
                        else -> null
                    }
                    if (bytes != null) addAll(librarySymbols(bytes, file.toUri().toString(), false))
                }
            }
        }
    }

    internal fun projectNamespace(project: MCFPPProjectConfig, file: Path): String {
        val tests = project.root.resolve("src/test/mcfpp").toAbsolutePath().normalize()
        val source = if (file.toAbsolutePath().normalize().startsWith(tests)) tests else project.sourcePath
        return ProjectConfiguration.namespaceFor(source, project.rootNamespace, file)
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


    private fun readStdlibIndexBytes(targetVersion: String): ByteArray? {
        val loader = this@MCFPPProjectIndex.javaClass.classLoader
        val resource = loader.getResource("datapack/targets//bin.mclib")
            ?: loader.getResource("datapack/bin.mclib")
        if (resource != null) {
            return resource.openStream().use { it.readBytes() }
        }
        val externalJar = System.getProperty("mcfpp.stdlib.jar")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val jarPath = Paths.get(externalJar)
        if (!jarPath.isRegularFile()) return null
        return runCatching {
            ZipFile(jarPath.toFile()).use { zip ->
                val entry = zip.getEntry("datapack/targets//bin.mclib")
                    ?: zip.getEntry("datapack/bin.mclib")
                entry?.let { zip.getInputStream(it).use { stream -> stream.readBytes() } }
            }
        }.getOrNull()
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
