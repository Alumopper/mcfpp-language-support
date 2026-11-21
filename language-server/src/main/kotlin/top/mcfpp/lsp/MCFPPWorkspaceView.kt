package top.mcfpp.lsp

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SymbolKind
import java.util.concurrent.ConcurrentHashMap

internal class MCFPPWorkspaceView(
    private val projectIndex: MCFPPProjectIndex,
    private val openAnalyses: () -> Map<String, MCFPPDocumentAnalysis>
) {
    val calls = MCFPPCallQueries(this)

    fun namespaceForUri(uri: String): String? = projectIndex.namespaceForUri(uri)
    class SymbolCaches {
        @Volatile var workspace: List<MCFPPSymbol>? = null
        private val documents = ConcurrentHashMap<String, DocumentCaches>()

        fun forDocument(analysis: MCFPPDocumentAnalysis): DocumentCaches =
            documents.compute(analysis.uri) { _, cached ->
                cached?.takeIf { it.analysis === analysis } ?: DocumentCaches(analysis)
            }!!
    }

    class DocumentCaches(val analysis: MCFPPDocumentAnalysis) {
        @Volatile var visible: List<MCFPPSymbol>? = null
        @Volatile var semanticTokens: List<SemanticTokenEntry>? = null
        val members = ConcurrentHashMap<String, List<MCFPPSymbol>>()
    }

    @Volatile var symbolCaches = SymbolCaches()
        private set

    fun workspaceSymbols(query: String = ""): List<MCFPPSymbol> {
        val lowered = query.trim().lowercase()
        val symbols = cachedWorkspaceSymbols()
        if (lowered.isBlank()) {
            return symbols
        }
        return symbols
            .filter {
                it.name.lowercase().contains(lowered) ||
                    (it.containerName?.lowercase()?.contains(lowered) == true) ||
                    (it.namespaceName?.lowercase()?.contains(lowered) == true)
            }
            .sortedBy { it.name }
    }

    fun allAnalyses(): Map<String, MCFPPDocumentAnalysis> {
        return linkedMapOf<String, MCFPPDocumentAnalysis>().apply {
            putAll(projectIndex.snapshot().fileAnalyses)
            putAll(openAnalyses())
        }
    }

    private fun cachedWorkspaceSymbols(): List<MCFPPSymbol> {
        val caches = symbolCaches
        caches.workspace?.let { return it }
        return synchronized(caches) {
            caches.workspace ?: allAnalyses().values
                .flatMap { analysis -> analysis.topLevelSymbols + analysis.typeMembers.values.flatten() }
                .plus(projectIndex.snapshot().librarySymbols)
                .distinctBy(::symbolIdentityKey)
                .sortedBy { it.name }
                .also { caches.workspace = it }
        }
    }

    fun invalidateWorkspaceSymbolCache() {
        symbolCaches = SymbolCaches()
    }

    fun visibleGlobalSymbolsForDocument(analysis: MCFPPDocumentAnalysis): List<MCFPPSymbol> {
        val cache = symbolCaches.forDocument(analysis)
        cache.visible?.let { return it }
        val exactImports = analysis.imports.filter { it.importedName != "*" }
        val wildcardNamespaces = analysis.imports.filter { it.importedName == "*" }.map { it.namespace }.toSet()
        val currentNamespace = analysis.namespaceName ?: projectIndex.namespaceForUri(analysis.uri)
        return workspaceSymbols().filter { symbol ->
            if (symbol.isStdlib || symbol.uri == analysis.uri) {
                return@filter true
            }
            val namespace = symbol.namespaceName ?: projectIndex.namespaceForUri(symbol.uri) ?: return@filter false
            if (namespace == currentNamespace) {
                return@filter true
            }
            if (namespace in wildcardNamespaces) {
                return@filter true
            }
            exactImports.any { imported ->
                if (imported.namespace != namespace) {
                    false
                } else {
                    val owner = normalizeTypeName(symbol.containerName ?: symbol.receiverType)
                    (symbol.topLevel && imported.importedName == symbol.name) || owner == imported.importedName
                }
            }
        }.also { cache.visible = it }
    }

    fun resolveLocalSymbol(analysis: MCFPPDocumentAnalysis, word: String, position: Position): MCFPPSymbol? {
        val declarations = analysis.declarationsByName[word].orEmpty().filter { it.uri == analysis.uri }
        declarations.firstOrNull { containsPosition(it.range, position) }?.let {
            return it
        }
        val currentTypeScopeId = analysis.innermostTypeScope(position)?.id
        return declarations
            .filter {
                it.kind in setOf(
                    SymbolKind.Variable,
                    SymbolKind.Field,
                    SymbolKind.Function,
                    SymbolKind.Method,
                    SymbolKind.Constructor,
                    SymbolKind.EnumMember,
                    SymbolKind.Class,
                    SymbolKind.Enum,
                    SymbolKind.TypeParameter,
                    SymbolKind.Namespace
                )
            }
            .filter { isBeforeOrEqual(it.range.start, position) }
            .filter {
                when {
                    it.enclosingFunctionScopeId != null -> analysis.isScopeVisible(it.enclosingFunctionScopeId, position)
                    it.enclosingTypeScopeId != null -> it.enclosingTypeScopeId == currentTypeScopeId || it.kind in setOf(SymbolKind.Field, SymbolKind.Method, SymbolKind.Constructor)
                    else -> true
                }
            }
            .maxWithOrNull(
                compareBy<MCFPPSymbol>(
                    { scopePriority(it, analysis) },
                    { it.range.start.line },
                    { it.range.start.character }
                )
            ) ?: if (workspaceSymbols().any { it.kind == SymbolKind.EnumMember && it.name == word })
                contextualEnumMembers(analysis, position).firstOrNull { it.name == word } else null
    }

    private fun scopePriority(symbol: MCFPPSymbol, analysis: MCFPPDocumentAnalysis): Int {
        val scope = symbol.enclosingFunctionScopeId ?: symbol.enclosingTypeScopeId ?: return 0
        return analysis.scopeDepth(scope)
    }

    fun resolveGlobalSymbol(analysis: MCFPPDocumentAnalysis, name: String, position: Position): MCFPPSymbol? {
        resolveQualifiedGlobalSymbol(analysis, name, position)?.let { return it }
        val currentTypeName = analysis.innermostTypeScope(position)?.name
        val aliasedImport = analysis.imports.firstOrNull { it.alias == name }
        if (aliasedImport != null) {
            return workspaceSymbols()
                .asSequence()
                .filter { it.topLevel }
                .filter { it.name == aliasedImport.importedName && it.namespaceName == aliasedImport.namespace }
                .maxWithOrNull(compareBy<MCFPPSymbol>({ it.range.start.line }, { it.range.start.character }))
        }
        return visibleGlobalSymbolsForDocument(analysis)
            .asSequence()
            .filter { it.name == name }
            .filter { isAccessibleByOriginalName(analysis, it) }
            .filter { isGlobalSymbolVisibleAt(it, currentTypeName) }
            .maxWithOrNull(
                compareBy<MCFPPSymbol>(
                    { globalSymbolPriority(it, currentTypeName) },
                    { it.range.start.line },
                    { it.range.start.character }
                )
            )
    }

    fun resolveQualifiedGlobalSymbol(
        analysis: MCFPPDocumentAnalysis,
        name: String,
        position: Position
    ): MCFPPSymbol? {
        val wordStart = analysis.wordRange(position)?.start ?: position
        val namespace = namespaceQualifierBefore(analysis, wordStart)
            ?.let { resolveNamespaceQualifier(analysis, it) }
            ?: return null
        return workspaceSymbols()
            .asSequence()
            .filter { it.topLevel && it.namespaceName == namespace && it.name == name }
            .maxWithOrNull(compareBy<MCFPPSymbol>({ it.range.start.line }, { it.range.start.character }))
    }

    private fun isAccessibleByOriginalName(analysis: MCFPPDocumentAnalysis, symbol: MCFPPSymbol): Boolean {
        if (symbol.isStdlib || symbol.uri == analysis.uri) {
            return true
        }
        val namespace = symbol.namespaceName ?: projectIndex.namespaceForUri(symbol.uri) ?: return false
        val currentNamespace = analysis.namespaceName ?: projectIndex.namespaceForUri(analysis.uri)
        if (namespace == currentNamespace) {
            return true
        }
        if (analysis.imports.any { it.namespace == namespace && it.importedName == "*" && it.alias == null }) {
            return true
        }
        return analysis.imports.any {
            it.namespace == namespace && it.importedName == symbol.name && it.alias == null
        }
    }

    private fun isGlobalSymbolVisibleAt(symbol: MCFPPSymbol, currentTypeName: String?): Boolean {
        if (symbol.topLevel) {
            return true
        }
        if (currentTypeName == null) {
            return false
        }
        return symbol.containerName == currentTypeName || symbol.receiverType == currentTypeName
    }

    private fun globalSymbolPriority(symbol: MCFPPSymbol, currentTypeName: String?): Int {
        return when {
            currentTypeName != null && (symbol.containerName == currentTypeName || symbol.receiverType == currentTypeName) -> 2
            symbol.topLevel -> 1
            else -> 0
        }
    }

    fun namespaceQualifierBefore(analysis: MCFPPDocumentAnalysis, position: Position): String? {
        val line = analysis.line(position.line)
        val prefix = line.substring(0, position.character.coerceIn(0, line.length))
        val match = NAMESPACE_QUALIFIER_AT_CURSOR.find(prefix) ?: return null
        val beforeQualifier = prefix.substring(0, match.range.first).trimEnd()
        if (Regex("\\b(?:var|const|external)\\s*$").containsMatchIn(beforeQualifier) ||
            beforeQualifier.lastOrNull() in setOf('(', '<', ',')) {
            return null
        }
        return match.groupValues[1]
    }

    fun resolveImportedTypeName(analysis: MCFPPDocumentAnalysis, typeName: String): String? {
        if (typeName.any { it in "({<" }) return typeName
        val normalized = normalizeTypeName(typeName) ?: return null
        val imported = analysis.imports.firstOrNull { candidate ->
            candidate.importedName != "*" && candidate.alias == normalized
        } ?: return normalized
        val isType = workspaceSymbols().any { symbol ->
            symbol.topLevel && symbol.kind in TYPE_SYMBOL_KINDS &&
                symbol.namespaceName == imported.namespace && symbol.name == imported.importedName
        }
        return if (isType) imported.importedName else normalized
    }

    fun resolveTypeSymbol(typeName: String, owner: MCFPPSymbol): MCFPPSymbol? {
        val simpleName = normalizeTypeName(typeName) ?: return null
        val explicitNamespace = typeName
            .substringBefore('<')
            .substringBefore('?')
            .substringBeforeLast(':', missingDelimiterValue = "")
            .trim()
            .takeIf { it.isNotEmpty() }
        val candidates = workspaceSymbols().filter { candidate ->
            candidate.topLevel && candidate.kind in TYPE_SYMBOL_KINDS && candidate.name == simpleName
        }
        if (explicitNamespace != null) {
            return candidates.firstOrNull { it.namespaceName == explicitNamespace }
        }
        val ownerAnalysis = allAnalyses()[owner.uri]
        if (ownerAnalysis != null) {
            visibleGlobalSymbolsForDocument(ownerAnalysis)
                .firstOrNull { candidate ->
                    candidate.topLevel && candidate.kind in TYPE_SYMBOL_KINDS && candidate.name == simpleName
                }
                ?.let { return it }
        }
        return candidates.firstOrNull { it.namespaceName == owner.namespaceName }
            ?: candidates.singleOrNull()
    }

    private companion object {
        val NAMESPACE_QUALIFIER_AT_CURSOR = Regex("([A-Za-z_][A-Za-z0-9_.]*)\\s*:\\s*[A-Za-z_0-9]*$")
    }
}
