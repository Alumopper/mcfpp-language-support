package top.mcfpp.lsp

import org.antlr.v4.runtime.ParserRuleContext
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import top.mcfpp.antlr.mcfppParser
import top.mcfpp.api.resources.PackResources
import top.mcfpp.api.resources.VanillaResources
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

internal class MCFPPResourceQueries(private val workspace: MCFPPWorkspaceView, private val projectIndex: MCFPPProjectIndex) {
    private data class Reference(val kind: String, val id: String, val range: Range)
    private data class CompletionSite(val kinds: Set<String>, val prefix: String, val range: Range)
    private val vanillaByVersion = ConcurrentHashMap<String, VanillaResources>()

    fun completions(analysis: MCFPPDocumentAnalysis, position: Position): List<CompletionItem>? {
        val site = references(analysis, position).firstOrNull()?.let {
            CompletionSite(setOf(it.kind), analysis.line(position.line).substring(it.range.start.character, position.character), it.range)
        } ?: incompleteStringSite(analysis, position) ?: return null
        val projectTargets = projectIndex.resourcesForUri(analysis.uri)?.targets.orEmpty()
        val vanilla = vanilla(analysis)
        return site.kinds.flatMap { kind ->
            val tag = site.prefix.startsWith("#")
            val keyKind = PackResources.key(kind, if (tag) "#target" else "target").kind()
            val prefix = site.prefix.removePrefix("#")
            val ids = vanilla.ids(keyKind).asSequence() +
                projectTargets.keys.asSequence().filter { it.kind() == keyKind }.map { it.id() } +
                (if (keyKind == "function") functionTargets(analysis.uri).keys.asSequence() else emptySequence())
            ids.filter { it.startsWith(prefix) || ':' !in prefix && it.substringAfter(':').startsWith(prefix) }
                .distinct().sorted().map { id ->
                    val value = if (tag) "#$id" else id
                    CompletionItem(value).apply {
                        this.kind = CompletionItemKind.Value
                        detail = kind
                        filterText = if (':' in prefix || id.startsWith(prefix)) value
                            else (if (tag) "#" else "") + id.substringAfter(':')
                        textEdit = Either.forLeft(TextEdit(site.range, value))
                    }
                }.toList()
        }.distinctBy { it.label }
    }

    fun definition(analysis: MCFPPDocumentAnalysis, position: Position): Pair<Range, List<Location>>? {
        val ref = references(analysis, position).firstOrNull() ?: return null
        val key = PackResources.key(ref.kind, ref.id)
        return ref.range to (key?.let { targets(analysis.uri, it) }.orEmpty())
    }

    fun diagnostics(analysis: MCFPPDocumentAnalysis): List<Diagnostic> {
        val vanilla = vanilla(analysis)
        return references(analysis).mapNotNull { ref ->
            val key = PackResources.key(ref.kind, ref.id)
            val message = when {
                key == null -> "Invalid ${ref.kind} resource ID '${ref.id}'"
                vanilla.contains(key) || exists(analysis.uri, key) -> return@mapNotNull null
                key.id().startsWith("minecraft:") && !vanilla.knows(key.kind()) -> return@mapNotNull null
                else -> "Unknown ${ref.kind} resource '${ref.id}'"
            }
            Diagnostic(ref.range, message, DiagnosticSeverity.Warning, "mcfpp").apply { setCode("mcfpp.resource") }
        }.toList()
    }

    private fun vanilla(analysis: MCFPPDocumentAnalysis): VanillaResources =
        vanillaByVersion.computeIfAbsent(analysis.targetVersion, VanillaResources::forVersion)

    private fun exists(uri: String, key: PackResources.Key): Boolean =
        projectIndex.resourcesForUri(uri)?.targets?.containsKey(key) == true ||
            key.kind() == "function" && key.id() in functionTargets(uri)

    private fun targets(uri: String, key: PackResources.Key): List<Location> {
        val files = projectIndex.resourcesForUri(uri)?.targets?.get(key)
        return files ?: if (key.kind() == "function") functionTargets(uri)[key.id()].orEmpty() else emptyList()
    }

    private fun functionTargets(uri: String): Map<String, List<Location>> {
        val project = projectIndex.sourceProject(uri) ?: return emptyMap()
        return workspace.symbolCaches.resourceFunctions.computeIfAbsent(project) {
            val declarations = workspace.allAnalyses().values.asSequence().filter { document ->
                runCatching { Path.of(URI.create(document.uri)).startsWith(project.sourcePath) }.getOrDefault(false)
            }.flatMap { it.topLevelSymbols.asSequence() }
                .filter { it.kind == SymbolKind.Function && it.parameters.isEmpty() && !it.isNative && !it.isMacro }
                .groupBy { "${it.namespaceName ?: projectIndex.namespaceForUri(it.uri)}:${it.name}" }
            buildMap {
                for (name in listOf("init", "load", "tick")) put("${project.rootNamespace}:$name", emptyList())
                declarations.forEach { (id, symbols) ->
                    val path = id.substringAfter(':')
                    val generatedId = id.substringBefore(':') + ":" + buildString {
                        for ((index, char) in path.withIndex()) {
                            if (char.isUpperCase() && index > 0) append('_')
                            append(char.lowercaseChar())
                        }
                    }
                    if (PackResources.validId(generatedId)) put(generatedId, symbols.map { it.location() })
                }
                project.entrypoints.forEach { (id, target) ->
                    val locations = declarations[target]?.map { it.location() } ?: return@forEach
                    if (PackResources.validId(id)) put(id, locations)
                }
            }
        }
    }

    private fun references(analysis: MCFPPDocumentAnalysis, position: Position? = null): Sequence<Reference> =
        analysis.referenceOccurrencesByName.values.asSequence().flatten().filter { it.isCall }.flatMap { occurrence ->
            val args = occurrence.normalArguments.zip(occurrence.normalArgumentRanges)
                .filterNot { (raw, _) -> isExplicitContextArgument(raw) }
            val literals = args.mapIndexedNotNull { index, (raw, range) ->
                if (position != null && !containsPosition(range, position)) return@mapIndexedNotNull null
                val id = stringContents(raw) ?: return@mapIndexedNotNull null
                val content = Range(Position(range.start.line, range.start.character + 1),
                    Position(range.end.line, range.end.character - 1))
                if (position != null && !containsPosition(content, position)) return@mapIndexedNotNull null
                Triple(index, id, content)
            }
            if (literals.isEmpty()) return@flatMap emptySequence()
            val positionalCall = occurrence.copy(normalArguments = args.map { it.first }, normalArgumentRanges = args.map { it.second })
            val target = workspace.calls.resolveCallTarget(analysis, positionalCall) ?: return@flatMap emptySequence()
            val parameters = target.parameters.filterNot { it.isReadOnly }
            literals.asSequence().mapNotNull { (index, id, range) ->
                parameters.getOrNull(index)?.resourceKind?.let { Reference(it, id, range) }
            }
        }

    private fun incompleteStringSite(analysis: MCFPPDocumentAnalysis, position: Position): CompletionSite? {
        val text = analysis.analysisText
        val caret = MCFPPTextChanges.positionToOffset(text, position)
        for (context in MCFPPCallContextFinder.findCandidates(text, position)) {
            if (context.argumentGroup != MCFPPArgumentGroup.NORMAL) continue
            val argument = context.arguments.last().trimStart()
            if (argument.firstOrNull() !in QUOTES || !argument.drop(1).all(::idCharacter)) continue
            val prefix = argument.drop(1)
            val start = caret - prefix.length
            var end = caret
            while (end < text.length && idCharacter(text[end])) end++
            val arguments = context.arguments.filterNot(::isExplicitContextArgument)
            val adjusted = context.copy(arguments = arguments.dropLast(1) + (argument + argument.first()),
                activeParameter = arguments.lastIndex, argumentPositions = emptyList())
            val candidates = workspace.calls.resolveCallableSymbols(analysis, adjusted, position).map { symbol ->
                val instantiated = workspace.calls.instantiate(symbol, adjusted, analysis, position)
                workspace.calls.signatureMatchScore(instantiated, adjusted, analysis, position) to
                    instantiated.parameters.filterNot { it.isReadOnly }.getOrNull(adjusted.activeParameter)?.resourceKind
            }
            val highest = candidates.maxOfOrNull { it.first } ?: continue
            val best = candidates.filter { it.first == highest }.map { it.second }
            if (null in best) continue
            val kinds = best.filterNotNull().toSet()
            if (kinds.isEmpty()) continue
            return CompletionSite(kinds, prefix, Range(offsetToPosition(text, start), offsetToPosition(text, end)))
        }
        return null
    }

    companion object {
        private val QUOTES = setOf('"', '\'')
        private val EXPLICIT_CONTEXT_ARGUMENT = Regex("^\\s*[A-Za-z_][A-Za-z0-9_]*\\s*=(?!=)")

        private fun isExplicitContextArgument(raw: String): Boolean = EXPLICIT_CONTEXT_ARGUMENT.containsMatchIn(raw)

        fun stringContents(raw: String): String? {
            if (raw.length < 2 || raw.first() !in QUOTES || raw.first() != raw.last()) return null
            val quote = raw.first()
            val value = StringBuilder()
            var offset = 1
            while (offset < raw.lastIndex) {
                var char = raw[offset++]
                when (char) {
                    '\n', '\r', quote -> return null
                    '\\' -> {
                        if (offset >= raw.lastIndex) return null
                        char = raw[offset++]
                        if (char != quote && char != '\\' && !(quote == '"' && char == '$' && raw[offset] == '{')) {
                            return null
                        }
                    }
                    '$' -> if (quote == '"' && raw[offset] == '{') return null
                }
                value.append(char)
            }
            return value.toString()
        }

        private fun idCharacter(char: Char): Boolean = char.isLetterOrDigit() || char in "_./:-#"
    }
}

private val RESOURCE_KIND = Regex("[a-z0-9_]+(?:/[a-z0-9_]+)*")

internal fun ParserRuleContext.resourceKind(): String? {
    val parameter = this as? mcfppParser.ParameterContext ?: return null
    if (parameter.type()?.text != "string") return null
    val annotation = parameter.annotation().singleOrNull { it.id?.text == "Resource" } ?: return null
    val source = annotation.annotationArgs()?.value()?.singleOrNull()?.LineString()?.text ?: return null
    return MCFPPResourceQueries.stringContents(source)?.takeIf(RESOURCE_KIND::matches)?.let(PackResources::resourceKind)
}
