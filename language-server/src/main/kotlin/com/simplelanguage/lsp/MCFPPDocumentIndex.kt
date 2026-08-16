package com.simplelanguage.lsp

import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.jsonrpc.messages.Either

private enum class ContainerKind {
    TYPE,
    FUNCTION,
    BLOCK
}

private data class ContainerFrame(
    val name: String,
    val kind: ContainerKind
)

private data class PendingContainer(
    val name: String,
    val kind: ContainerKind
)

data class MCFPPImport(
    val namespace: String,
    val importedName: String,
    val alias: String?,
    val source: String? = null,
    val aliasRange: Range? = null
)

data class MCFPPFunctionParameter(
    val name: String?,
    val typeName: String,
    val isStatic: Boolean = false,
    val hasDefault: Boolean = false,
    val defaultValue: String? = null,
    val isReadOnly: Boolean = false
) {
    fun label(): String = buildString {
        if (isStatic) append("static ")
        if (name != null) {
            append(name)
            append(" as ")
        }
        append(typeName)
        if (hasDefault) {
            append(" = ")
            append(defaultValue ?: "...")
        }
    }
}

enum class MCFPPAccessModifier {
    PUBLIC,
    PROTECTED,
    PRIVATE
}

data class MCFPPSymbol(
    val uri: String,
    val name: String,
    val kind: SymbolKind,
    val range: Range,
    val fullRange: Range = range,
    val detail: String? = null,
    val containerName: String? = null,
    val typeName: String? = null,
    val receiverType: String? = null,
    val topLevel: Boolean = false,
    val enclosingTypeScopeId: String? = null,
    val enclosingFunctionScopeId: String? = null,
    val superTypes: List<String> = emptyList(),
    val documentation: String? = null,
    val namespaceName: String? = null,
    val isExternal: Boolean = false,
    val isStdlib: Boolean = false,
    val parameters: List<MCFPPFunctionParameter> = emptyList(),
    val isParameter: Boolean = false,
    val isReadOnly: Boolean = false,
    val isStatic: Boolean = false,
    val accessModifier: MCFPPAccessModifier = MCFPPAccessModifier.PUBLIC,
    val isSynthetic: Boolean = false
) {
    val selectionRange: Range = range

    fun location(): Location = Location(uri, selectionRange)

    fun toWorkspaceSymbol(): WorkspaceSymbol = WorkspaceSymbol(
        name,
        kind,
        Either.forLeft(location()),
        containerName
    )

    fun toCompletionKind(): CompletionItemKind = when (kind) {
        SymbolKind.Class -> CompletionItemKind.Class
        SymbolKind.Interface -> CompletionItemKind.Interface
        SymbolKind.Enum -> CompletionItemKind.Enum
        SymbolKind.Method,
        SymbolKind.Function,
        SymbolKind.Constructor -> CompletionItemKind.Function
        SymbolKind.Field,
        SymbolKind.Property -> CompletionItemKind.Field
        SymbolKind.Variable -> CompletionItemKind.Variable
        SymbolKind.Namespace,
        SymbolKind.Module -> CompletionItemKind.Module
        SymbolKind.TypeParameter -> CompletionItemKind.TypeParameter
        else -> CompletionItemKind.Text
    }
}

data class MCFPPDocumentAnalysis(
    val uri: String,
    val text: String,
    val namespaceName: String?,
    val imports: List<MCFPPImport>,
    val symbols: List<MCFPPSymbol>,
    val variables: Map<String, String?>,
    val typeMembers: Map<String, List<MCFPPSymbol>>,
    val topLevelSymbols: List<MCFPPSymbol>,
    val referencesByName: Map<String, List<Range>> = emptyMap(),
    val declarationsByName: Map<String, List<MCFPPSymbol>> = emptyMap(),
    val referenceOccurrencesByName: Map<String, List<MCFPPReferenceOccurrence>> = emptyMap(),
    val scopeRegions: List<MCFPPScopeRegion> = emptyList(),
    val parserDiagnostics: List<Diagnostic> = emptyList()
) {
    private val lines: List<String> = text.split("\n")
    private val scopesById: Map<String, MCFPPScopeRegion> = scopeRegions.associateBy { it.id }

    fun line(line: Int): String = lines.getOrElse(line) { "" }

    fun wordAt(position: Position): String? {
        val line = line(position.line)
        if (line.isEmpty()) {
            return null
        }

        val index = position.character.coerceIn(0, line.length)
        val candidateIndex = when {
            index < line.length && isWordChar(line[index]) -> index
            index > 0 && isWordChar(line[index - 1]) -> index - 1
            else -> return null
        }

        var start = candidateIndex
        while (start > 0 && isWordChar(line[start - 1])) {
            start--
        }

        var end = candidateIndex
        while (end < line.length && isWordChar(line[end])) {
            end++
        }

        return line.substring(start, end)
    }

    fun wordRange(position: Position): Range? {
        val line = line(position.line)
        if (line.isEmpty()) {
            return null
        }

        val index = position.character.coerceIn(0, line.length)
        val candidateIndex = when {
            index < line.length && isWordChar(line[index]) -> index
            index > 0 && isWordChar(line[index - 1]) -> index - 1
            else -> return null
        }

        var start = candidateIndex
        while (start > 0 && isWordChar(line[start - 1])) {
            start--
        }

        var end = candidateIndex
        while (end < line.length && isWordChar(line[end])) {
            end++
        }

        return Range(Position(position.line, start), Position(position.line, end))
    }

    fun receiverBefore(position: Position): String? {
        val line = line(position.line)
        if (line.isEmpty()) {
            return null
        }

        val sliceEnd = position.character.coerceIn(0, line.length)
        val prefix = line.substring(0, sliceEnd)
        var separator = prefix.length - 1
        while (separator >= 0 && (prefix[separator].isWhitespace() || isWordChar(prefix[separator]))) {
            separator--
        }
        if (separator < 0 || prefix[separator] != '.') {
            return null
        }

        var index = separator - 1
        var parentheses = 0
        var brackets = 0
        var angles = 0
        while (index >= 0) {
            when (prefix[index]) {
                ')' -> parentheses++
                '(' -> if (parentheses > 0) parentheses-- else break
                ']' -> brackets++
                '[' -> if (brackets > 0) brackets-- else break
                '>' -> angles++
                '<' -> if (angles > 0) angles-- else break
                '=', ';', '{', '}', ',', '+', '-', '*', '/', '%', '!', '&', '|', '?' ->
                    if (parentheses == 0 && brackets == 0 && angles == 0) break
                else -> Unit
            }
            if (prefix[index].isWhitespace() && parentheses == 0 && brackets == 0 && angles == 0) {
                val left = prefix.getOrNull(index - 1)
                val right = prefix.getOrNull(index + 1)
                if (left !in setOf('.', ':') && right !in setOf('.', ':', '(')) {
                    break
                }
            }
            index--
        }
        return prefix.substring(index + 1, separator).trim().takeIf { it.isNotEmpty() }
    }

    private fun isWordChar(char: Char): Boolean = char.isLetterOrDigit() || char == '_'

    fun innermostFunctionScope(position: Position): MCFPPScopeRegion? = innermostScope(position, MCFPPScopeKind.FUNCTION)

    fun innermostTypeScope(position: Position): MCFPPScopeRegion? = innermostScope(position, MCFPPScopeKind.TYPE)

    fun innermostBlockScope(position: Position): MCFPPScopeRegion? = innermostScope(position, MCFPPScopeKind.BLOCK)

    fun isScopeVisible(scopeId: String?, position: Position): Boolean {
        if (scopeId == null) {
            return true
        }
        val functionScope = innermostFunctionScope(position)
        val typeScope = innermostTypeScope(position)
        return when {
            functionScope != null && isScopeInChain(functionScope.id, scopeId) -> true
            typeScope != null && isScopeInChain(typeScope.id, scopeId) -> true
            else -> false
        }
    }

    private fun innermostScope(position: Position, kind: MCFPPScopeKind): MCFPPScopeRegion? {
        return scopeRegions
            .filter { it.kind == kind && containsPosition(it.range, position) }
            .maxWithOrNull(compareBy<MCFPPScopeRegion>({ scopeDepth(it.id) }, { it.range.start.line }, { it.range.start.character }))
    }

    private fun isScopeInChain(startScopeId: String, targetScopeId: String): Boolean {
        var current: String? = startScopeId
        while (current != null) {
            if (current == targetScopeId) {
                return true
            }
            current = scopesById[current]?.parentScopeId
        }
        return false
    }

    private fun scopeDepth(scopeId: String): Int {
        var current: String? = scopeId
        var depth = 0
        while (current != null) {
            depth += 1
            current = scopesById[current]?.parentScopeId
        }
        return depth
    }

    private fun containsPosition(range: Range, position: Position): Boolean {
        val startsBeforeOrAt = range.start.line < position.line || (range.start.line == position.line && range.start.character <= position.character)
        val endsAfterOrAt = range.end.line > position.line || (range.end.line == position.line && range.end.character >= position.character)
        return startsBeforeOrAt && endsAfterOrAt
    }

}

object MCFPPDocumentIndex {
    val keywords: List<String> = listOf(
        "namespace", "import", "typealias", "data", "object", "interface", "enum", "func",
        "constructor", "if", "else", "while", "for", "do", "try", "store", "execute",
        "return", "break", "continue", "inline", "const", "static", "override", "abstract",
        "native", "dynamic", "public", "protected", "private", "impl", "extends", "operator",
        "global", "var", "get", "set", "from", "as"
    )

    val builtinTypes: List<String> = listOf(
        "int", "long", "byte", "short", "float", "double", "bool", "string", "text", "selector",
        "entity", "nbt", "any", "void", "list", "map", "dict", "Type", "ByteArray", "IntArray",
        "LongArray", "vec2", "vec3", "vec4"
    )

    val builtinValues: List<String> = listOf("true", "false", "null", "this", "super")

    private val namespacePattern = Regex("^\\s*namespace\\s+([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)")
    private val importPattern = Regex("^\\s*import\\s+([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*):([A-Za-z_][A-Za-z0-9_]*|\\*)(?:\\s+as\\s+([A-Za-z_][A-Za-z0-9_]*))?(?:\\s+from\\s+([A-Za-z_][A-Za-z0-9_]*))?")
    private val typeAliasPattern = Regex("^\\s*typealias\\s+(.+?)\\s+as\\s+([A-Za-z_][A-Za-z0-9_]*)")
    private val dataPattern = Regex("^\\s*(?:final\\s+)?(?:object\\s+)?data\\s+(?:abstract\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\b")
    private val interfacePattern = Regex("^\\s*interface\\s+([A-Za-z_][A-Za-z0-9_]*)\\b")
    private val enumPattern = Regex("^\\s*enum\\s+([A-Za-z_][A-Za-z0-9_]*)\\b")
    private val functionPattern = Regex("^\\s*(?:inline\\s+|const\\s+)?func\\s+(?:([A-Za-z_][A-Za-z0-9_<>!?|:&\\[\\]]*)\\s*\\.\\s*)?([A-Za-z_][A-Za-z0-9_]*)\\s*(?:<[^>]*>\\s*)?\\(")
    private val constructorPattern = Regex("^\\s*constructor\\s*\\(")
    private val variablePattern = Regex("^\\s*(?:(?:const|dynamic|import)\\s+)?var\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?:as\\s+([^=]+?))?(?:\\s*=.*)?$")
    private val memberFieldPattern = Regex("^\\s*(?:const\\s+)?(?:var\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*(?:as\\s+([^={]+?))?(?:\\s*(?:=|\\{|$))")
    private val enumMemberPattern = Regex("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(?:=|,|$)")

    fun analyze(uri: String, text: String): MCFPPDocumentAnalysis {
        val symbols = mutableListOf<MCFPPSymbol>()
        val imports = mutableListOf<MCFPPImport>()
        val variables = linkedMapOf<String, String?>()
        val typeMembers = linkedMapOf<String, MutableList<MCFPPSymbol>>()
        val topLevelSymbols = mutableListOf<MCFPPSymbol>()
        val normalizedText = text.replace("\r\n", "\n")
        val lines = normalizedText.split("\n")
        val containers = ArrayDeque<ContainerFrame>()

        var namespaceName: String? = null
        var pendingContainer: PendingContainer? = null
        var inTripleComment = false
        var inDoubleComment = false
        var inBraceDocComment = false

        lines.forEachIndexed { lineIndex, rawLine ->
            var line = rawLine
            val trimmed = rawLine.trim()

            if (trimmed.startsWith("###")) {
                inTripleComment = !inTripleComment
                return@forEachIndexed
            }
            if (inTripleComment) {
                return@forEachIndexed
            }
            if (trimmed.startsWith("##")) {
                inDoubleComment = !inDoubleComment
                return@forEachIndexed
            }
            if (inDoubleComment) {
                return@forEachIndexed
            }
            if (trimmed.startsWith("#{") && !trimmed.contains("}#")) {
                inBraceDocComment = true
                return@forEachIndexed
            }
            if (inBraceDocComment) {
                if (trimmed.contains("}#")) {
                    inBraceDocComment = false
                }
                return@forEachIndexed
            }
            if (trimmed.startsWith("#{") && trimmed.contains("}#")) {
                return@forEachIndexed
            }

            line = stripInlineComment(line)
            if (line.isBlank()) {
                pendingContainer = updateContainerStack(line, containers, pendingContainer)
                return@forEachIndexed
            }

            val currentType = containers.lastOrNull { it.kind == ContainerKind.TYPE }?.name
            val currentFunction = containers.lastOrNull { it.kind == ContainerKind.FUNCTION }?.name
            val effectiveContainer = currentFunction ?: currentType

            namespacePattern.find(line)?.let { match ->
                val name = match.groupValues[1]
                namespaceName = name
                val symbol = createSymbol(uri, lineIndex, normalizedText, line, name, SymbolKind.Namespace, name, null, null, true, name)
                symbols += symbol
                topLevelSymbols += symbol
            }

            importPattern.find(line)?.let { match ->
                val aliasGroup = match.groups[3]
                imports += MCFPPImport(
                    namespace = match.groupValues[1],
                    importedName = match.groupValues[2],
                    alias = match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() },
                    source = match.groupValues.getOrNull(4)?.takeIf { it.isNotBlank() },
                    aliasRange = aliasGroup?.let { group ->
                        Range(
                            Position(lineIndex, group.range.first),
                            Position(lineIndex, group.range.last + 1)
                        )
                    }
                )
            }

            typeAliasPattern.find(line)?.let { match ->
                val target = match.groupValues[2]
                val detail = match.groupValues[1].trim()
                val symbol = createSymbol(uri, lineIndex, normalizedText, line, target, SymbolKind.TypeParameter, detail, null, detail, true, namespaceName)
                symbols += symbol
                topLevelSymbols += symbol
            }

            val typeMatch = dataPattern.find(line) ?: interfacePattern.find(line) ?: enumPattern.find(line)
            if (typeMatch != null) {
                val name = typeMatch.groupValues[1]
                val kind = when {
                    line.contains("interface") -> SymbolKind.Interface
                    line.contains("enum") -> SymbolKind.Enum
                    else -> SymbolKind.Class
                }
                val symbol = createSymbol(uri, lineIndex, normalizedText, line, name, kind, null, null, name, true, namespaceName)
                symbols += symbol
                topLevelSymbols += symbol
                if (line.contains("{")) {
                    pendingContainer = PendingContainer(name, ContainerKind.TYPE)
                }
            }

            functionPattern.find(line)?.let { match ->
                val receiver = match.groups[1]?.value?.trim().takeIf { !it.isNullOrBlank() }
                val name = match.groups[2]?.value ?: return@let
                val returnType = extractReturnType(line)
                val kind = if (currentType != null || receiver != null) SymbolKind.Method else SymbolKind.Function
                val symbol = createSymbol(uri, lineIndex, normalizedText, line, name, kind, returnType, currentType, returnType, currentType == null, namespaceName)
                    .copy(receiverType = receiver)
                symbols += symbol
                if (symbol.topLevel) {
                    topLevelSymbols += symbol
                }
                val memberOwner = receiver ?: currentType
                if (memberOwner != null && currentFunction == null) {
                    typeMembers.getOrPut(memberOwner) { mutableListOf() } += symbol
                }
                if (line.contains("{")) {
                    pendingContainer = PendingContainer(name, ContainerKind.FUNCTION)
                }
            }

            if (constructorPattern.containsMatchIn(line) && currentType != null) {
                val symbol = createSymbol(uri, lineIndex, normalizedText, line, currentType, SymbolKind.Constructor, null, currentType, currentType, false, namespaceName)
                symbols += symbol
                typeMembers.getOrPut(currentType) { mutableListOf() } += symbol
                if (line.contains("{")) {
                    pendingContainer = PendingContainer(currentType, ContainerKind.FUNCTION)
                }
            }

            val variableMatch = when {
                currentType != null && currentFunction == null -> memberFieldPattern.find(line)
                else -> variablePattern.find(line)
            }
            variableMatch?.let { match ->
                val name = match.groupValues[1]
                if (name !in keywords) {
                    val typeName = match.groups[2]?.value?.trim()?.takeIf { it.isNotBlank() }
                    variables[name] = typeName
                    val kind = if (currentType != null && currentFunction == null) SymbolKind.Field else SymbolKind.Variable
                    val symbol = createSymbol(uri, lineIndex, normalizedText, line, name, kind, typeName, effectiveContainer, typeName, currentType == null, namespaceName)
                    symbols += symbol
                    if (symbol.topLevel) {
                        topLevelSymbols += symbol
                    }
                    if (currentType != null && currentFunction == null) {
                        typeMembers.getOrPut(currentType) { mutableListOf() } += symbol
                    }
                }
            }

            if (currentType != null && currentFunction == null && containers.lastOrNull()?.name == currentType && containers.lastOrNull()?.kind == ContainerKind.TYPE) {
                if (enumMemberPattern.matches(line.trim()) && !line.contains("func") && !line.contains("var") && !line.contains("data") && !line.contains("interface")) {
                    val memberName = enumMemberPattern.find(line.trim())?.groupValues?.get(1)
                    if (!memberName.isNullOrBlank()) {
                        val symbol = createSymbol(uri, lineIndex, normalizedText, line, memberName, SymbolKind.EnumMember, null, currentType, currentType, false, namespaceName)
                        symbols += symbol
                        typeMembers.getOrPut(currentType) { mutableListOf() } += symbol
                    }
                }
            }

            pendingContainer = updateContainerStack(line, containers, pendingContainer)
        }

        val parserSemantic = MCFPPParserSemanticExtractor.extract(uri, normalizedText)
        val parserDeclarationKeys = parserSemantic.symbols.mapTo(mutableSetOf(), ::semanticDeclarationKey)
        val fallbackSymbols = symbols.filterNot { semanticDeclarationKey(it) in parserDeclarationKeys }
        val mergedSymbols = (parserSemantic.symbols + fallbackSymbols)
            .distinctBy { listOf(it.name, it.kind, it.range.start.line, it.range.start.character, it.containerName, it.receiverType).joinToString("|") }
        val mergedVariables = linkedMapOf<String, String?>().apply {
            putAll(variables)
            parserSemantic.variables.forEach { (name, typeName) ->
                this[name] = typeName ?: this[name]
            }
        }
        val mergedTypeMembers = linkedMapOf<String, List<MCFPPSymbol>>()
        (typeMembers.keys + parserSemantic.typeMembers.keys).forEach { owner ->
            mergedTypeMembers[owner] = (
                parserSemantic.typeMembers[owner].orEmpty() +
                    typeMembers[owner].orEmpty().filterNot { semanticDeclarationKey(it) in parserDeclarationKeys }
                )
                .distinctBy { listOf(it.name, it.kind, it.range.start.line, it.range.start.character, it.containerName, it.receiverType).joinToString("|") }
        }
        val mergedTopLevelSymbols = (
            parserSemantic.topLevelSymbols +
                topLevelSymbols.filterNot { semanticDeclarationKey(it) in parserDeclarationKeys }
            )
            .distinctBy { listOf(it.name, it.kind, it.range.start.line, it.range.start.character).joinToString("|") }
        return MCFPPDocumentAnalysis(
            uri = uri,
            text = normalizedText,
            namespaceName = namespaceName,
            imports = imports,
            symbols = mergedSymbols,
            variables = mergedVariables,
            typeMembers = mergedTypeMembers,
            topLevelSymbols = mergedTopLevelSymbols,
            referencesByName = parserSemantic.referencesByName,
            declarationsByName = mergedSymbols.groupBy { it.name },
            referenceOccurrencesByName = parserSemantic.referenceOccurrencesByName,
            scopeRegions = parserSemantic.scopeRegions,
            parserDiagnostics = parserSemantic.diagnostics
        )
    }

    private fun semanticDeclarationKey(symbol: MCFPPSymbol): String = listOf(
        symbol.name,
        symbol.kind.name,
        symbol.range.start.line,
        symbol.containerName.orEmpty(),
        symbol.receiverType.orEmpty()
    ).joinToString("|")

    private fun updateContainerStack(
        line: String,
        containers: ArrayDeque<ContainerFrame>,
        pendingContainer: PendingContainer?
    ): PendingContainer? {
        val opens = line.count { it == '{' }
        val closes = line.count { it == '}' }
        var nextPending = pendingContainer

        if (opens > 0) {
            if (nextPending != null) {
                containers.addLast(ContainerFrame(nextPending.name, nextPending.kind))
                repeat(opens - 1) {
                    containers.addLast(ContainerFrame("<block>", ContainerKind.BLOCK))
                }
                nextPending = null
            } else {
                repeat(opens) {
                    containers.addLast(ContainerFrame("<block>", ContainerKind.BLOCK))
                }
            }
        }

        repeat(closes) {
            if (containers.isNotEmpty()) {
                containers.removeLast()
            }
        }

        return nextPending
    }

    private fun createSymbol(
        uri: String,
        lineIndex: Int,
        text: String,
        line: String,
        name: String,
        kind: SymbolKind,
        detail: String?,
        containerName: String?,
        typeName: String?,
        topLevel: Boolean,
        namespaceName: String?
    ): MCFPPSymbol {
        val start = line.indexOf(name).coerceAtLeast(0)
        val range = Range(Position(lineIndex, start), Position(lineIndex, start + name.length))
        return MCFPPSymbol(
            uri = uri,
            name = name,
            kind = kind,
            range = range,
            detail = detail,
            containerName = containerName,
            typeName = typeName,
            topLevel = topLevel,
            documentation = MCFPPDocumentation.findDocumentationBefore(text, lineIndex),
            namespaceName = namespaceName
        )
    }

    private fun extractReturnType(line: String): String? {
        val arrow = line.indexOf("->")
        if (arrow < 0) {
            return null
        }
        val bodyStart = line.indexOf('{').let { if (it < 0) line.length else it }
        return line.substring(arrow + 2, bodyStart).trim().takeIf { it.isNotBlank() }
    }

    private fun stripInlineComment(line: String): String {
        if (line.trimStart().startsWith("/")) {
            return line
        }

        var inSingle = false
        var inDouble = false
        for (index in line.indices) {
            val char = line[index]
            val escaped = index > 0 && line[index - 1] == '\\'
            when {
                char == '\'' && !escaped && !inDouble -> inSingle = !inSingle
                char == '"' && !escaped && !inSingle -> inDouble = !inDouble
                char == '#' && !inSingle && !inDouble -> return line.substring(0, index)
            }
        }
        return line
    }
}
