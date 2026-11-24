package top.mcfpp.lsp

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import top.mcfpp.library.model.NativeBinding
import java.io.File
import java.lang.classfile.ClassFile
import java.lang.classfile.attribute.SourceFileAttribute
import java.lang.classfile.instruction.LineNumber
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

/** Uses the binary's JVM descriptor and debug positions without loading library providers. */
internal class MCFPPSourceJarResolver(private val artifact: Path, dependencies: List<Path>, private val projectRoot: Path? = null) {
    private data class DebugSource(val file: String?, val line: Int?)
    private data class Sources(val localRoots: List<Path>, val archives: List<Path>)
    private val binaries = (listOf(artifact) + dependencies).distinct().filter { it.extension in setOf("jar", "zip") }
    private val sourcesByArtifact = mutableMapOf<Path, Sources>()

    fun implementation(binding: NativeBinding): Location? = runCatching {
        val binary = binaries.firstNotNullOfOrNull { candidate ->
            debugSource(candidate, binding)?.let { candidate to it }
        }
        val sourceArtifact = binary?.first ?: artifact
        val sources = sourcesByArtifact.getOrPut(sourceArtifact) { findSources(sourceArtifact) }
        val owner = binding.ownerClass.substringAfterLast('.').substringBefore('$')
        val directory = binding.ownerClass.substringBeforeLast('.', "").replace('.', '/')
        val names = listOfNotNull(binary?.second?.file, "$owner.kt", "$owner.java", owner.removeSuffix("Kt") + ".kt").distinct()
        val paths = names.map { if (directory.isEmpty()) it else "$directory/$it" }
        val candidates = sequence {
            for (root in sources.localRoots) {
                for (relative in paths) {
                    val file = root.resolve(relative).normalize()
                    if (file.startsWith(root) && Files.isRegularFile(file)) yield(file)
                }
            }
            for (archive in sources.archives) {
                for (relative in paths) {
                    materialize(archive, relative)?.let { yield(it) }
                }
            }
        }
        candidates.firstNotNullOfOrNull { file ->
            locate(file, binding, binary?.second?.line)
        }
    }.getOrNull()

    private fun debugSource(binary: Path, binding: NativeBinding): DebugSource? = runCatching {
        ZipFile(binary.toFile()).use { jar ->
            val entry = jar.getEntry(binding.ownerClass.replace('.', '/') + ".class") ?: return null
            val model = jar.getInputStream(entry).use { ClassFile.of().parse(it.readAllBytes()) }
            val descriptor = binding.jvmParameters.joinToString("", "(", ")", transform = ::descriptor) + descriptor(binding.jvmResult)
            val method = model.methods().singleOrNull {
                it.methodName().equalsString(binding.method) && it.methodType().equalsString(descriptor)
            } ?: return null
            val file = model.attributes().filterIsInstance<SourceFileAttribute>().firstOrNull()?.sourceFile()?.stringValue()
            val line = method.code().orElse(null)?.elementList()?.filterIsInstance<LineNumber>()?.firstOrNull()?.line()
            DebugSource(file, line?.minus(1))
        }
    }.getOrNull()

    private fun descriptor(type: String): String {
        val primitive = primitiveDescriptors[type]
        return when {
            primitive != null -> primitive
            type.startsWith('[') -> type.replace('.', '/')
            type.endsWith("[]") -> "[" + descriptor(type.dropLast(2))
            else -> "L" + type.replace('.', '/') + ";"
        }
    }

    private fun locate(file: Path, binding: NativeBinding, debugLine: Int?): Location? {
        val text = Files.readString(file)
        val headers = (if (file.extension == "kt") kotlinHeader else javaHeader).findAll(text).toList()
        val starts = listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }
        val debugEnd = debugLine?.let { starts.getOrNull(it + 1) ?: text.length }
        val debugHeader = debugEnd?.let { end -> headers.lastOrNull { it.range.first < end } }
        val header = debugHeader?.takeIf { it.groupValues[1] == binding.method }
            ?: headers.filter { it.groupValues[1] == binding.method }
                .singleOrNull { sourceParametersMatch(text, it, file.extension == "kt", binding.jvmParameters) }
            ?: return null
        val offset = header.groups[1]!!.range.first
        val line = starts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }
        val column = offset - starts[line]
        return Location(file.toUri().toString(), Range(Position(line, column), Position(line, column + binding.method.length)))
    }

    private fun sourceParametersMatch(text: String, header: MatchResult, kotlin: Boolean, jvm: List<String>): Boolean {
        val start = header.range.last + 1
        var end = start
        var nesting = 1
        while (end < text.length && nesting > 0) {
            if (text[end] == '(') nesting++
            if (text[end] == ')') nesting--
            end++
        }
        if (nesting != 0) return false
        val parameters = MCFPPTemplateLiterals.splitTypes(text.substring(start, end - 1))
        if (parameters.size != jvm.size) return false
        return parameters.zip(jvm).all { (parameter, binary) ->
            val type = if (kotlin) parameter.substringAfter(':').substringBefore('=')
                else parameter.trim().substringBeforeLast(' ')
            val simple = type.substringBefore('<').substringAfterLast('.').trim().removeSuffix("?")
            val binaryName = binary.substringAfterLast('.').substringAfterLast('$')
            simple == binaryName || (kotlin && kotlinTypes[simple] == binary)
        }
    }

    private fun findSources(binary: Path): Sources {
        val name = binary.nameWithoutExtension + "-sources.jar"
        val configured = configuredRoots()
        val nearby = listOfNotNull(binary.parent, artifact.parent).distinct()
        val archives = (nearby.map { it.resolve(name) } + configured.flatMap { path ->
            if (path.fileName?.toString() == name) listOf(path) else listOf(path.resolve(name))
        }).filter(Files::isRegularFile).distinct()
        val maven = if (archives.isEmpty()) mavenArchives(name, binary.nameWithoutExtension) else emptyList()
        val local = configured + listOfNotNull(projectRoot).flatMap { root -> listOf(root.resolve("src/main/java"), root.resolve("src/main/kotlin")) }
        return Sources(local.filter(Files::isDirectory), archives + maven)
    }

    private fun mavenArchives(name: String, artifactName: String): List<Path> {
        val roots = listOfNotNull(System.getProperty("maven.repo.local")?.let(Path::of),
            Path.of(System.getProperty("user.home"), ".m2", "repository")).distinct()
        val version = versionSuffix.find(artifactName)?.value?.drop(1) ?: return emptyList()
        val artifactId = artifactName.removeSuffix("-$version")
        return roots.filter(Files::isDirectory).flatMap { root ->
            Files.walk(root).use { entries ->
                entries.filter { it.fileName.toString() == name && it.parent?.fileName?.toString() == version &&
                    it.parent?.parent?.fileName?.toString() == artifactId && Files.isRegularFile(it) }.toList()
            }
        }.singleOrNull()?.let(::listOf).orEmpty()
    }

    companion object {
        private val versionSuffix = Regex("""-\d[\w.+-]*$""")
        private val kotlinHeader = Regex("""\bfun\s+(?:<[^{}()]*?>\s+)?(?:[^\s(]+\.)?`?([\w$]+)`?\s*\(""")
        private val javaHeader = Regex("""(?m)^\s*(?:(?:public|protected|private|static|final|synchronized|native)\s+)*[\w.$<>\[\],?]+\s+([\w$]+)\s*\(""")
        private val primitiveDescriptors = mapOf("void" to "V", "boolean" to "Z", "byte" to "B", "char" to "C",
            "short" to "S", "int" to "I", "long" to "J", "float" to "F", "double" to "D")
        private val kotlinTypes = mapOf("Unit" to "void", "Boolean" to "boolean", "Byte" to "byte", "Char" to "char",
            "Short" to "short", "Int" to "int", "Long" to "long", "Float" to "float", "Double" to "double", "Any" to "java.lang.Object")
        private data class Archive(val path: Path, val modified: Long, val size: Long)
        private val extractedDirectories = ConcurrentHashMap<Archive, Path>()

        fun configuredRoots(): List<Path> = listOf("mcfpp.kotlin.source.roots", "mcfpp.sources.path").flatMap { property ->
            System.getProperty(property).orEmpty().split(File.pathSeparatorChar).filter(String::isNotBlank)
                .mapNotNull { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }
        }.distinct()

        fun adjacentSources(binary: Path): Path =
            binary.resolveSibling(binary.nameWithoutExtension + "-sources.jar")

        private fun materialize(path: Path, relative: String): Path? {
            val archive = Archive(path, Files.getLastModifiedTime(path).toMillis(), Files.size(path))
            val root = extractedDirectories.computeIfAbsent(archive) {
                Files.createTempDirectory("mcfpp-dependency-sources-").apply { toFile().deleteOnExit() }
            }
            val target = root.resolve(relative).normalize()
            if (!target.startsWith(root)) return null
            synchronized(root) {
                if (Files.isRegularFile(target)) return target
                ZipFile(path.toFile()).use { jar ->
                    val entry = jar.getEntry(relative) ?: return null
                    Files.createDirectories(target.parent)
                    generateSequence(target.parent) { it.parent }.takeWhile { it != root }.toList()
                        .asReversed().forEach { it.toFile().deleteOnExit() }
                    jar.getInputStream(entry).use { Files.copy(it, target) }
                    target.toFile().deleteOnExit()
                }
            }
            return target
        }
    }
}
