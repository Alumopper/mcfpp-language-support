package top.mcfpp.lsp

import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentPositionParams

internal fun MCFPPWorkspaceView.resolveSymbolAt(params: TextDocumentPositionParams, analysis: MCFPPDocumentAnalysis): MCFPPSymbol? {
    templateFieldAt(analysis, params.position)?.let { return it }
    val word = analysis.wordAt(params.position) ?: return null
    val references = analysis.referenceOccurrencesByName[word].orEmpty()
    references.firstOrNull { it.isNamedArgument && containsPosition(it.range, params.position) }
        ?.let { return resolveNamedArgument(analysis, it) }
    references.firstOrNull { it.isCall && containsPosition(it.range, params.position) }
        ?.let { calls.resolveCallTarget(analysis, it) }?.let { return it }
    val receiver = analysis.receiverBefore(params.position)
    resolveQualifiedGlobalSymbol(analysis, word, params.position)?.let { return it }
    if (receiver != null) {
        val typeName = calls.resolveReceiverTypeAt(analysis, receiver, params.position)
        if (typeName != null) {
            return receiverMembers(analysis, receiver, typeName, params.position).firstOrNull { it.name == word }
        }
    }
    resolveLocalSymbol(analysis, word, params.position)?.let {
        return it
    }
    return resolveGlobalSymbol(analysis, word, params.position)
}

internal fun MCFPPWorkspaceView.resolveReferenceOccurrence(
    analysis: MCFPPDocumentAnalysis,
    occurrence: MCFPPReferenceOccurrence
): MCFPPSymbol? {
    if (occurrence.isCall) {
        return calls.resolveCallTarget(analysis, occurrence)
    }
    resolveQualifiedGlobalSymbol(analysis, occurrence.name, occurrence.range.start)?.let { return it }
    val receiver = analysis.receiverBefore(occurrence.range.end)
    if (receiver != null) {
        val receiverType = calls.resolveReceiverTypeAt(analysis, receiver, occurrence.range.start)
        if (receiverType != null) {
            return receiverMembers(analysis, receiver, receiverType, occurrence.range.start)
                .firstOrNull { it.name == occurrence.name }
        }
    }
    return resolveLocalSymbol(analysis, occurrence.name, occurrence.range.start)
        ?: resolveGlobalSymbol(analysis, occurrence.name, occurrence.range.start)
}

internal fun MCFPPWorkspaceView.collectReferenceRanges(document: MCFPPDocumentAnalysis, target: MCFPPSymbol, includeDeclaration: Boolean): List<Range> {
    return buildList {
        addAll(
            document.referenceOccurrencesByName[target.name].orEmpty()
                .filter { matchesScopedReference(document, it, target) }
                .map { it.range }
        )
        if (includeDeclaration) {
            addAll(
                document.declarationsByName[target.name].orEmpty()
                    .filter { matchesScopedDeclaration(it, target) }
                    .map { it.range }
            )
        }
    }.distinctBy { listOf(it.start.line, it.start.character, it.end.line, it.end.character).joinToString(":") }
}

private fun MCFPPWorkspaceView.matchesScopedReference(
    document: MCFPPDocumentAnalysis,
    reference: MCFPPReferenceOccurrence,
    target: MCFPPSymbol
): Boolean {
    if (reference.isNamedArgument) return resolveNamedArgument(document, reference)?.let { sameSymbol(it, target) } == true
    val targetOwner = target.containerName ?: target.receiverType
    if (targetOwner != null) {
        val receiver = document.receiverBefore(reference.range.end)
        val receiverType = receiver?.let { calls.resolveReceiverTypeAt(document, it, reference.range.start) }
        if (receiverType != null) {
            return reference.name == target.name && isTypeOrSubtype(document, receiverType, targetOwner)
        }
    }
    val resolved = resolveLocalSymbol(document, reference.name, reference.range.start)
        ?: resolveGlobalSymbol(document, reference.name, reference.range.start)
    return resolved != null && sameSymbol(resolved, target)
}

private fun MCFPPWorkspaceView.matchesScopedDeclaration(candidate: MCFPPSymbol, target: MCFPPSymbol): Boolean {
    return candidate.uri == target.uri &&
        candidate.kind == target.kind &&
        candidate.range == target.range &&
        candidate.enclosingTypeScopeId == target.enclosingTypeScopeId &&
        candidate.enclosingFunctionScopeId == target.enclosingFunctionScopeId
}

private fun MCFPPWorkspaceView.resolveNamedArgument(document: MCFPPDocumentAnalysis, reference: MCFPPReferenceOccurrence): MCFPPSymbol? {
    val call = MCFPPCallContextFinder.findCandidates(document.text, reference.range.start).firstOrNull() ?: return null
    val arguments = call.parsedArguments(document)
    val callable = calls.resolveCallableSymbols(document, call, reference.range.start)
        .maxByOrNull { calls.signatureMatchScore(it, call, document, reference.range.start, arguments) } ?: return null
    val owner = allAnalyses()[callable.uri] ?: return null
    return owner.symbols.firstOrNull { it.isParameter && it.name == reference.name && containsPosition(callable.fullRange, it.range.start) }
}
