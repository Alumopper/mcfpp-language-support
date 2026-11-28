package top.mcfpp.lsp

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import top.mcfpp.api.resources.PackResources
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

internal class MCFPPResourceIndex private constructor(val targets: Map<PackResources.Key, List<Location>>) {
    companion object {
        fun scan(project: MCFPPProjectConfig): MCFPPResourceIndex {
            val definitions = linkedMapOf<PackResources.Key, MutableList<Location>>()
            fun put(key: PackResources.Key, file: Path, position: Position) {
                val location = Location(file.toUri().toString(), Range(position, position))
                definitions.getOrPut(key) { mutableListOf() }.add(location)
            }
            fun scan(root: Path, assets: Boolean) {
                if (!root.isDirectory()) return
                Files.walk(root).use { paths ->
                    paths.filter { it.isRegularFile() }.forEach { file ->
                        PackResources.definition(root, file, assets)?.let { put(it, file, Position(0, 0)) }
                        if (assets && file.name == "sounds.json" && root.relativize(file).nameCount == 2) {
                            val namespace = root.relativize(file).getName(0).toString()
                            runCatching { file.readText() }.getOrNull()?.let { text ->
                                PackResources.sounds(namespace, text).forEach { (key, offset) ->
                                    put(key, file, offsetToPosition(text, offset))
                                }
                            }
                        }
                    }
                }
            }
            scan(project.dataPath, false)
            scan(project.assetsPath, true)
            return MCFPPResourceIndex(definitions)
        }
    }
}
