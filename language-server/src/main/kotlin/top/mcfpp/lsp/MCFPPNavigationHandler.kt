package top.mcfpp.lsp

import org.eclipse.lsp4j.DeclarationParams
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightKind
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.ImplementationParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.TypeDefinitionParams
import org.eclipse.lsp4j.jsonrpc.messages.Either

internal class MCFPPNavigationHandler(private val workspace: MCFPPWorkspaceView) {
    fun definition(params: TextDocumentPositionParams, analysis: MCFPPDocumentAnalysis,
                   linkSupport: Boolean = false): Either<List<Location>, List<LocationLink>> {
        workspace.resources.definition(analysis, params.position)?.let { (origin, targets) ->
            return if (linkSupport && targets.isNotEmpty())
                Either.forRight(targets.map { LocationLink(it.uri, it.range, it.range, origin) })
            else Either.forLeft(targets)
        }
        val target = workspace.resolveSymbolAt(params, analysis)
        val locations = target?.let { symbol ->
            if (!symbol.isExternal || symbol.uri.endsWith(".mcfpp")) MCFPPValueMembers.resolveDeclaration(symbol.location())
            else symbol.nativeImplementation?.let(MCFPPValueMembers::resolveImplementation)
        } ?: return Either.forLeft(emptyList())
        val origin = analysis.wordRange(params.position)
        return if (linkSupport && origin != null) {
            Either.forRight(listOf(LocationLink(locations.uri, locations.range, locations.range, origin)))
        } else Either.forLeft(listOf(locations))
    }

    fun declaration(params: DeclarationParams, analysis: MCFPPDocumentAnalysis): Either<List<Location>, List<LocationLink>> =
        definition(params, analysis)

    fun references(params: ReferenceParams, analysis: MCFPPDocumentAnalysis): List<Location> {
        val target = workspace.resolveSymbolAt(params, analysis) ?: return emptyList()
        return findReferences(target, params.context.isIncludeDeclaration)
    }

    fun documentSymbol(analysis: MCFPPDocumentAnalysis): List<Either<SymbolInformation, DocumentSymbol>> =
        buildDocumentSymbols(analysis).map { Either.forRight<SymbolInformation, DocumentSymbol>(it) }

    fun typeDefinition(params: TypeDefinitionParams, analysis: MCFPPDocumentAnalysis): Either<List<Location>, List<LocationLink>> {
        val target = workspace.resolveSymbolAt(params, analysis) ?: return Either.forLeft(emptyList())
        val symbol = if (target.kind in TYPE_DEFINITION_SYMBOL_KINDS) {
            target
        } else {
            target.typeName?.let { workspace.resolveTypeSymbol(it, target) }
        }
        val locations = symbol
            ?.takeIf { !it.isExternal }
            ?.location()
            ?.let(::listOf)
            .orEmpty()
        return Either.forLeft(locations)
    }

    fun implementation(params: ImplementationParams, analysis: MCFPPDocumentAnalysis): Either<List<Location>, List<LocationLink>> {
        val target = workspace.resolveSymbolAt(params, analysis) ?: return Either.forLeft(emptyList())
        val native = workspace.withNativeMetadata(target).nativeImplementation?.let(MCFPPValueMembers::resolveImplementation)
        if (native != null) return Either.forLeft(listOf(native))
        return if (target.isExternal) Either.forLeft(emptyList()) else Either.forLeft(findImplementations(target))
    }

    fun documentHighlight(params: DocumentHighlightParams, analysis: MCFPPDocumentAnalysis): List<DocumentHighlight> {
        val target = workspace.resolveSymbolAt(params, analysis) ?: return emptyList()
        return workspace.collectReferenceRanges(analysis, target, includeDeclaration = true)
            .map { DocumentHighlight(it, DocumentHighlightKind.Text) }
    }

    private fun buildDocumentSymbols(analysis: MCFPPDocumentAnalysis): List<DocumentSymbol> {
        return analysis.topLevelSymbols
            .sortedWith(compareBy({ it.range.start.line }, { it.range.start.character }))
            .map { symbol ->
                val children = if (symbol.kind in setOf(SymbolKind.Class, SymbolKind.Enum)) {
                    analysis.typeMembers[symbol.name]
                        .orEmpty()
                        .sortedWith(compareBy({ it.range.start.line }, { it.range.start.character }))
                        .map(::toDocumentSymbol)
                } else {
                    emptyList()
                }
                toDocumentSymbol(symbol, children)
            }
    }

    private fun toDocumentSymbol(symbol: MCFPPSymbol, children: List<DocumentSymbol> = emptyList()): DocumentSymbol {
        val documentSymbol = DocumentSymbol()
        documentSymbol.name = symbol.name
        documentSymbol.kind = symbol.kind
        documentSymbol.range = symbol.fullRange
        documentSymbol.selectionRange = symbol.selectionRange
        documentSymbol.detail = symbol.detail ?: symbol.typeName ?: symbol.superTypes.takeIf { it.isNotEmpty() }?.joinToString(", ")
        if (children.isNotEmpty()) {
            documentSymbol.children = children
        }
        return documentSymbol
    }

    private fun findImplementations(target: MCFPPSymbol): List<Location> {
        val workspace = workspace.workspaceSymbols()
        val typeKinds = setOf(SymbolKind.Class, SymbolKind.Enum)
        val implementerTypes = when (target.kind) {
            SymbolKind.Class -> workspace
                .filter { it.topLevel && it.kind in typeKinds }
                .filter { candidate -> isSubtype(candidate, target) }
            else -> emptyList()
        }
        val memberImplementations = when (target.kind) {
            SymbolKind.Method,
            SymbolKind.Function,
            SymbolKind.Field,
            SymbolKind.Property,
            SymbolKind.Constructor,
            SymbolKind.EnumMember -> {
                val ownerType = target.containerName ?: target.receiverType
                if (ownerType == null) {
                    emptyList()
                } else {
                    val ownerTypeSymbol = workspace.firstOrNull { candidate ->
                        candidate.topLevel &&
                            candidate.kind in typeKinds &&
                            candidate.name == ownerType &&
                            (target.namespaceName == null || candidate.namespaceName == target.namespaceName)
                    }
                    val implementerTypeKeys = workspace
                        .filter { it.topLevel && it.kind in typeKinds }
                        .filter { candidate -> ownerTypeSymbol != null && isSubtype(candidate, ownerTypeSymbol) }
                        .map { it.namespaceName to it.name }
                        .toSet()
                    workspace.filter { candidate ->
                        candidate.name == target.name &&
                            candidate.kind == target.kind &&
                            parameterSignatureKey(candidate) == parameterSignatureKey(target) &&
                            candidate.location() != target.location() &&
                            ((candidate.containerName != null && (candidate.namespaceName to candidate.containerName) in implementerTypeKeys) ||
                                (candidate.receiverType != null && (candidate.namespaceName to candidate.receiverType) in implementerTypeKeys))
                    }
                }
            }
            else -> emptyList()
        }
        return (implementerTypes + memberImplementations)
            .distinctBy { listOf(it.uri, it.range.start.line, it.range.start.character, it.kind, it.name).joinToString("|") }
            .map { it.location() }
    }

    private fun isSubtype(candidate: MCFPPSymbol, target: MCFPPSymbol): Boolean {
        val pending = ArrayDeque<MCFPPSymbol>()
        val visited = mutableSetOf<String>()
        pending.addLast(candidate)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(symbolIdentityKey(current))) {
                continue
            }
            current.superTypes.forEach { typeName ->
                val parent = workspace.resolveTypeSymbol(typeName, current) ?: return@forEach
                if (sameSymbol(parent, target)) {
                    return true
                }
                pending.addLast(parent)
            }
        }
        return false
    }

    private fun findReferences(target: MCFPPSymbol, includeDeclaration: Boolean): List<Location> {
        return workspace.allAnalyses().values.flatMap { document ->
            buildList {
                addAll(workspace.collectReferenceRanges(document, target, includeDeclaration).map { Location(document.uri, it) })
            }
        }
            .distinctBy { listOf(it.uri, it.range.start.line, it.range.start.character, it.range.end.line, it.range.end.character).joinToString("|") }
    }
}
