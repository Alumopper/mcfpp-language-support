package top.mcfpp.lsp

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SymbolKind

internal fun MCFPPWorkspaceView.resolveReceiverType(analysis: MCFPPDocumentAnalysis, receiver: String): String? {
    resolveGlobalSymbol(analysis, receiver, Position(0, 0))
        ?.takeIf { it.kind in TYPE_SYMBOL_KINDS }
        ?.let { return it.name }
    return resolveImportedTypeName(analysis, receiver)
}

private fun MCFPPWorkspaceView.enumDeclaration(analysis: MCFPPDocumentAnalysis, type: String): MCFPPSymbol? {
    val simple = type.substringAfterLast(':')
    val namespace = if (':' in type) resolveNamespaceQualifier(analysis, type.substringBeforeLast(':')) else null
    val imported = analysis.imports.firstOrNull { namespace == null && it.importedName != "*" && (it.alias ?: it.importedName) == simple }
    val targetNamespace = namespace ?: imported?.namespace
    val currentNamespace = analysis.namespaceName ?: namespaceForUri(analysis.uri)
    return (if (namespace != null) workspaceSymbols() else visibleGlobalSymbolsForDocument(analysis)).asSequence()
        .filter { it.kind == SymbolKind.Enum && it.name == (imported?.importedName ?: simple) }
        .filter { targetNamespace == null || it.namespaceName == targetNamespace }
        .maxWithOrNull(compareBy({ it.uri == analysis.uri }, { it.namespaceName == currentNamespace }, { !it.isStdlib }))
}

private fun MCFPPWorkspaceView.enumMembers(owner: MCFPPSymbol) = workspaceSymbols().filter {
    it.kind == SymbolKind.EnumMember && it.containerName == owner.name && it.uri == owner.uri && it.namespaceName == owner.namespaceName
}

internal fun MCFPPWorkspaceView.contextualEnumMembers(analysis: MCFPPDocumentAnalysis, position: Position): List<MCFPPSymbol> {
    val offset = MCFPPTextChanges.positionToOffset(analysis.text, position)
    var start = offset
    while (start > 0 && Character.isUnicodeIdentifierPart(analysis.text[start - 1])) start--
    val expected = templateContext(analysis).first.expectedValueTypes(start).distinct().singleOrNull() ?: return emptyList()
    return enumDeclaration(analysis, expected)?.let(::enumMembers).orEmpty()
}

internal fun MCFPPWorkspaceView.receiverMembers(analysis: MCFPPDocumentAnalysis, receiver: String, type: String, position: Position): List<MCFPPSymbol> {
    val owner = enumDeclaration(analysis, type) ?: return accessibleMemberSymbolsForType(analysis, type, position)
    val static = receiver.substringAfterLast(':') == owner.name || analysis.imports.any {
        it.alias == receiver && it.importedName == owner.name && it.namespace == owner.namespaceName
    }
    if (static && resolveLocalSymbol(analysis, receiver, position)?.kind in setOf(null, SymbolKind.Enum))
        return enumMembers(owner)
    return top.mcfpp.language.EnumProperties.TYPES.map { (name, result) ->
        owner.copy(name = name, kind = SymbolKind.Property, typeName = result, receiverType = type,
            containerName = owner.name, topLevel = false, isReadOnly = true, isSynthetic = true,
            documentation = "Read-only enum property.")
    }
}

internal fun MCFPPWorkspaceView.memberSymbolsForType(
    analysis: MCFPPDocumentAnalysis,
    receiverType: String
): List<MCFPPSymbol> {
    MCFPPEntityTemplates.templateOf(receiverType)?.let { template ->
        return entityReferenceMembers(analysis, template)
    }
    val normalizedReceiverType = normalizeTypeName(receiverType) ?: return emptyList()
    val cacheKey = receiverType
    val memberSymbolsCache = symbolCaches.forDocument(analysis).members
    memberSymbolsCache[cacheKey]?.let { return it }
    val intrinsicReceiver = if (normalizedReceiverType == "GameRule" && ':' !in receiverType.substringBefore('<')) {
        visibleGlobalSymbolsForDocument(analysis).firstOrNull { it.name == "GameRule" && it.kind == SymbolKind.Class }
            ?.namespaceName?.let { "$it:$receiverType" } ?: receiverType
    } else receiverType
    val intrinsic = MCFPPValueMembers.members(intrinsicReceiver)
    templateSchema(analysis).members(receiverType)?.let { declared ->
        val members = mergeMemberMetadata(declared, intrinsic)
        memberSymbolsCache[cacheKey] = members
        return members
    }
    val visibleSymbols = visibleGlobalSymbolsForDocument(analysis)
    val membersByOwner = visibleSymbols
        .asSequence()
        .filter { it.containerName != null || it.receiverType != null }
        .groupBy { normalizeTypeName(it.containerName ?: it.receiverType).orEmpty() }
    val typesByName = visibleSymbols
        .asSequence()
        .filter { it.topLevel && it.kind in TYPE_SYMBOL_KINDS }
        .groupBy { normalizeTypeName(it.name).orEmpty() }
    val pending = ArrayDeque<String>()
    val visitedTypes = mutableSetOf<String>()
    val members = linkedMapOf<String, MCFPPSymbol>()
    pending.addLast(normalizedReceiverType)
    while (pending.isNotEmpty()) {
        val currentType = pending.removeFirst()
        if (!visitedTypes.add(currentType)) {
            continue
        }
        membersByOwner[currentType].orEmpty()
            .filterNot { it.isEntityMember }
            .forEach { symbol ->
                val key = listOf(symbol.name, symbol.kind.name, parameterSignatureKey(symbol)).joinToString("|")
                members.putIfAbsent(key, symbol)
            }
        // Intrinsic summon declarations are reconstructed by the compiler when a
        // library is imported, so they do not appear in its exported members.
        if (membersByOwner[currentType].orEmpty().none { it.name == "summon" }) {
            typesByName[currentType].orEmpty().firstOrNull { it.isEntityTemplate }?.let { template ->
                for (parameters in listOf(emptyList(), listOf(MCFPPFunctionParameter("d", "nbt")))) {
                    val summon = template.copy(name = "summon", kind = SymbolKind.Method,
                        containerName = currentType, receiverType = currentType, topLevel = false,
                        detail = "EntityRef<$currentType>", typeName = "EntityRef<$currentType>",
                        parameters = parameters, isEntityTemplate = false, isEntityMember = false, isSynthetic = true)
                    val key = listOf(summon.name, summon.kind.name, parameterSignatureKey(summon)).joinToString("|")
                    members.putIfAbsent(key, summon)
                }
            }
        }
        typesByName[currentType].orEmpty().forEach { typeSymbol ->
            typeSymbol.superTypes.forEach { superType ->
                normalizeTypeName(superType)
                    ?.takeIf { it !in visitedTypes }
                    ?.let(pending::addLast)
            }
        }
        top.mcfpp.language.EntityBuiltins.parent(currentType)?.let(pending::addLast)
    }
    return mergeMemberMetadata(members.values, intrinsic).also { memberSymbolsCache[cacheKey] = it }
}

private fun mergeMemberMetadata(declared: Collection<MCFPPSymbol>, intrinsic: List<MCFPPSymbol>): List<MCFPPSymbol> =
    (declared.filterNot { candidate -> candidate.isSynthetic && intrinsic.any {
        it.name == candidate.name && parameterSignatureKey(it).filterNot(Char::isWhitespace) ==
            parameterSignatureKey(candidate).filterNot(Char::isWhitespace)
    } } + intrinsic).distinctBy { it.name to parameterSignatureKey(it).filterNot(Char::isWhitespace) }

/**
 * Members of `EntityRef<T>`: the entity members declared on the template ancestry
 * plus the compiler-owned members of the built-in entity kind that ancestry reaches.
 */

private fun MCFPPWorkspaceView.entityReferenceMembers(analysis: MCFPPDocumentAnalysis, template: String): List<MCFPPSymbol> {
    val cacheKey = "EntityRef<$template>"
    val memberSymbolsCache = symbolCaches.forDocument(analysis).members
    memberSymbolsCache[cacheKey]?.let { return it }
    val symbols = visibleGlobalSymbolsForDocument(analysis)
    val owners = MCFPPEntityTemplates.owners(symbols, template)
    val members = linkedMapOf<String, MCFPPSymbol>()
    owners.forEach { owner ->
        symbols.asSequence()
            .filter { it.isEntityMember && normalizeTypeName(it.containerName) == owner }
            .forEach { symbol ->
                val key = listOf(symbol.name, symbol.kind.name, parameterSignatureKey(symbol)).joinToString("|")
                members.putIfAbsent(key, symbol)
            }
        if (owner == "entity" || top.mcfpp.language.EntityBuiltins.TYPES.contains(owner)) symbols.asSequence()
            .filter { it.containerName != null && normalizeTypeName(it.containerName) == owner && !it.isEntityMember }
            .forEach { symbol ->
                val key = listOf(symbol.name, symbol.kind.name, parameterSignatureKey(symbol)).joinToString("|")
                members.putIfAbsent(key, symbol)
            }
    }
    return members.values.toList().also { memberSymbolsCache[cacheKey] = it }
}

internal fun MCFPPWorkspaceView.accessibleMemberSymbolsForType(
    analysis: MCFPPDocumentAnalysis,
    receiverType: String,
    position: Position
): List<MCFPPSymbol> {
    val currentType = analysis.innermostTypeScope(position)?.name
    return memberSymbolsForType(analysis, receiverType).filter { symbol ->
        isMemberAccessible(analysis, symbol, position, currentType)
    }
}

internal fun MCFPPWorkspaceView.isMemberAccessible(
    analysis: MCFPPDocumentAnalysis,
    symbol: MCFPPSymbol,
    position: Position,
    currentType: String? = analysis.innermostTypeScope(position)?.name
): Boolean {
    val owner = symbol.containerName ?: symbol.receiverType ?: return true
    return when (symbol.accessModifier) {
        MCFPPAccessModifier.PUBLIC -> true
        MCFPPAccessModifier.PRIVATE -> currentType != null && normalizeTypeName(currentType) == normalizeTypeName(owner)
        MCFPPAccessModifier.PROTECTED -> currentType != null && isTypeOrSubtype(analysis, currentType, owner)
    }
}

internal fun MCFPPWorkspaceView.isTypeOrSubtype(
    analysis: MCFPPDocumentAnalysis,
    actualType: String,
    expectedType: String
): Boolean {
    val expected = normalizeTypeName(expectedType) ?: return false
    val actual = normalizeTypeName(actualType) ?: return false
    if (actual == expected) {
        return true
    }
    return inheritanceDistance(analysis, actual.lowercase(), expected.lowercase()) != null
}

internal fun MCFPPWorkspaceView.inheritanceDistance(
    analysis: MCFPPDocumentAnalysis,
    actualType: String,
    expectedType: String
): Int? {
    if (actualType == expectedType) {
        return 0
    }
    val typesByName = visibleGlobalSymbolsForDocument(analysis)
        .asSequence()
        .filter { it.topLevel && it.kind in TYPE_SYMBOL_KINDS }
        .groupBy { it.name.lowercase() }
    val pending = ArrayDeque<Pair<String, Int>>()
    val visited = mutableSetOf<String>()
    pending.addLast(actualType to 0)
    while (pending.isNotEmpty()) {
        val (current, distance) = pending.removeFirst()
        if (!visited.add(current)) {
            continue
        }
        for (symbol in typesByName[current].orEmpty()) {
            for (superType in symbol.superTypes) {
                val normalizedSuperType = normalizeTypeName(superType)?.lowercase() ?: continue
                if (normalizedSuperType == expectedType) {
                    return distance + 1
                }
                if (normalizedSuperType !in visited) {
                    pending.addLast(normalizedSuperType to distance + 1)
                }
            }
        }
    }
    return null
}

internal fun MCFPPWorkspaceView.expandTypeAlias(
    analysis: MCFPPDocumentAnalysis,
    typeName: String,
    visited: Set<String> = emptySet()
): String {
    val normalized = normalizeTypeName(typeName) ?: return typeName
    if (normalized in visited) {
        return typeName
    }
    val aliasTarget = visibleGlobalSymbolsForDocument(analysis)
        .asSequence()
        .filter { it.topLevel && it.kind == SymbolKind.TypeParameter }
        .firstOrNull { normalizeTypeName(it.name) == normalized }
        ?.typeName
        ?.takeIf { normalizeTypeName(it) != normalized }
        ?: return typeName
    return expandTypeAlias(analysis, aliasTarget, visited + normalized)
}
