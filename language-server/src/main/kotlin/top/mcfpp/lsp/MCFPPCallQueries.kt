package top.mcfpp.lsp

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SymbolKind
import top.mcfpp.antlr.mcfppParser

internal class MCFPPCallQueries(private val workspace: MCFPPWorkspaceView) {
    fun resolveCallableSymbols(
        analysis: MCFPPDocumentAnalysis,
        context: MCFPPCallContext,
        position: Position
    ): List<MCFPPSymbol> {
        if (context.qualifierSeparator == null) {
            val value = workspace.resolveLocalSymbol(analysis, context.name, position)
                ?: workspace.visibleGlobalSymbolsForDocument(analysis).firstOrNull {
                    it.name == context.name && it.kind in setOf(SymbolKind.Variable, SymbolKind.Field)
                }
            if (value != null && value.kind in setOf(SymbolKind.Variable, SymbolKind.Field)) {
                return callableValues(analysis, value, emptySet())
            }
        }
        val symbols = when (context.qualifierSeparator) {
            '.' -> {
                val receiverType = context.qualifier?.let { resolveReceiverTypeAt(analysis, it, position) }
                    ?: return emptyList()
                workspace.accessibleMemberSymbolsForType(analysis, receiverType, position).filter { symbol ->
                    symbol.kind in CALLABLE_SYMBOL_KINDS &&
                        symbol.name == context.name
                }
            }
            ':' -> {
                val namespace = context.qualifier?.let { resolveNamespaceQualifier(analysis, it) } ?: return emptyList()
                workspace.workspaceSymbols().filter { symbol ->
                    symbol.kind in CALLABLE_SYMBOL_KINDS && symbol.name == context.name && symbol.namespaceName == namespace
                }
            }
            else -> {
                val aliasedImport = analysis.imports.firstOrNull { imported -> imported.alias == context.name }
                if (aliasedImport != null) {
                    workspace.workspaceSymbols().filter { symbol ->
                        symbol.kind in CALLABLE_SYMBOL_KINDS &&
                            symbol.name == aliasedImport.importedName &&
                            symbol.namespaceName == aliasedImport.namespace
                    }
                } else {
                    val currentType = analysis.innermostTypeScope(position)?.name
                    workspace.visibleGlobalSymbolsForDocument(analysis).filter { symbol ->
                        symbol.kind in CALLABLE_SYMBOL_KINDS &&
                            symbol.name == context.name &&
                            (
                                symbol.topLevel ||
                                    symbol.containerName == currentType ||
                                    symbol.receiverType == currentType ||
                                    (symbol.kind == SymbolKind.Constructor && symbol.containerName == context.name)
                                )
                    }
                }
            }
        }
        return symbols
            .filter { workspace.isMemberAccessible(analysis, it, position) }
            .filter { symbol ->
                context.argumentGroup != MCFPPArgumentGroup.READ_ONLY || symbol.parameters.any { it.isReadOnly }
            }
            .distinctBy { symbolIdentityKey(it) }
    }

    private fun callableValues(analysis: MCFPPDocumentAnalysis, value: MCFPPSymbol, visited: Set<String>): List<MCFPPSymbol> {
        val key = symbolIdentityKey(value)
        if (key in visited) return emptyList()
        value.functionValue?.let { signature ->
            val declared = value.initializer?.let { reference -> workspace.visibleGlobalSymbolsForDocument(analysis).firstOrNull { it.name == reference } }
            val contexts = declared?.contextParameters ?: signature.contextTypes.mapIndexed { index, type -> MCFPPFunctionParameter("context${index + 1}", type) }
            return listOf(value.copy(parameters = signature.parameters, contextParameters = contexts, typeName = signature.returnType, detail = signature.returnType))
        }
        val reference = value.initializer?.takeIf { it.matches(Regex("(?:[A-Za-z_][A-Za-z0-9_.]*:)?[A-Za-z_][A-Za-z0-9_]*")) }
            ?: return emptyList()
        val name = reference.substringAfterLast(':')
        if (':' !in reference) {
            workspace.resolveLocalSymbol(analysis, name, value.range.start)?.takeIf {
                it.kind in setOf(SymbolKind.Variable, SymbolKind.Field)
            }?.let { alias ->
                return callableValues(analysis, alias, visited + key).map {
                    value.copy(parameters = it.parameters, typeName = it.typeName, detail = it.detail)
                }
            }
        }
        val namespace = reference.substringBefore(':', "").takeIf { it.isNotEmpty() }
            ?.let { resolveNamespaceQualifier(analysis, it) }
        val imported = analysis.imports.firstOrNull { it.alias == name }
        return workspace.visibleGlobalSymbolsForDocument(analysis).filter {
            it.kind in CALLABLE_SYMBOL_KINDS &&
                it.name == (imported?.importedName ?: name) &&
                (namespace == null || it.namespaceName == namespace) &&
                (imported == null || it.namespaceName == imported.namespace)
        }.map { value.copy(parameters = it.parameters, typeName = it.typeName, detail = it.detail) }
    }

    fun resolveReceiverTypeAt(analysis: MCFPPDocumentAnalysis, receiver: String, position: Position): String? {
        val expression = receiver.trim().removeSuffix("!!").removeSuffix("?").removeSurrounding("(", ")").trim()
        MCFPPDocumentIndex.referenceFactoryType(expression)?.let { return it }
        if (expression.startsWith('{') && expression.endsWith('}')) return "nbt"
        stringLiteralType(expression)?.let { return it }
        if (expression.startsWith('@') && lastTopLevelMemberSeparator(expression) < 0) return "entity"
        if (IDENTIFIER_REGEX.matches(expression)) {
            val local = workspace.resolveLocalSymbol(analysis, expression, position)
            local?.typeName?.let { return it }
            local?.initializer?.takeIf { it.trim() != expression }?.let {
                val initializer = local.initializerStart?.let { start -> analysis.expressionAt(start, it) }
                inferArgumentType(analysis, it, local.range.start, initializer)?.let { type -> return type }
            }
        }
        if (expression == "this") {
            val owner = analysis.innermostTypeScope(position)?.name ?: return null
            return if (isEntityMemberContext(analysis, owner, position)) MCFPPEntityTemplates.referenceType(owner) else owner
        }
        if (expression == "super") {
            val owner = analysis.innermostTypeScope(position)?.name ?: return null
            val parent = workspace.visibleGlobalSymbolsForDocument(analysis).firstOrNull {
                it.name == owner && it.kind in setOf(SymbolKind.Class)
            }?.superTypes?.firstOrNull()?.let(::normalizeTypeName)
            return if (parent != null && isEntityMemberContext(analysis, owner, position))
                MCFPPEntityTemplates.referenceType(parent) else parent
        }
        if (expression.endsWith(')')) {
            inferExpressionCallReturnType(analysis, expression, position)
                ?.let { return workspace.resolveImportedTypeName(analysis, it) }
        }
        val memberSeparator = lastTopLevelMemberSeparator(expression)
        if (memberSeparator > 0) {
            val ownerExpression = expression.substring(0, memberSeparator).removeSuffix("?")
            val memberName = expression.substring(memberSeparator + 1).trim()
            if (IDENTIFIER_REGEX.matches(memberName)) {
                val ownerType = resolveReceiverTypeAt(analysis, ownerExpression, position)
                if (ownerType != null) {
                    return workspace.receiverMembers(analysis, ownerExpression, ownerType, position)
                        .firstOrNull { it.name == memberName }
                        ?.typeName
                        ?.let { workspace.resolveImportedTypeName(analysis, it) }
                }
            }
        }
        if (!IDENTIFIER_REGEX.matches(expression)) inferArgumentType(analysis, expression, position)?.let { return it }
        return workspace.resolveReceiverType(analysis, expression)
    }

    /** `this` inside `entity func name()` is the template's `EntityRef`, not the template value. */

    private fun isEntityMemberContext(analysis: MCFPPDocumentAnalysis, type: String, position: Position): Boolean =
        analysis.symbols.any { symbol ->
            symbol.isEntityMember && symbol.kind == SymbolKind.Method &&
                normalizeTypeName(symbol.containerName) == normalizeTypeName(type) &&
                containsPosition(symbol.fullRange, position)
        }

    private fun inferExpressionCallReturnType(
        analysis: MCFPPDocumentAnalysis,
        expression: String,
        position: Position
    ): String? {
        if (CALL_EXPRESSION.matches(expression)) {
            inferNestedCallReturnType(analysis, expression, position)?.let { return it }
        }
        val close = expression.indexOfLast { !it.isWhitespace() }
        if (close < 0 || expression[close] != ')') return null
        var depth = 1
        var open = close - 1
        while (open >= 0 && depth > 0) {
            when (expression[open]) {
                ')' -> depth++
                '(' -> depth--
            }
            open--
        }
        open++
        if (depth != 0) return null
        var nameEnd = open
        var readOnlyArguments = ""
        var genericClose = open - 1
        while (genericClose >= 0 && expression[genericClose].isWhitespace()) genericClose--
        if (genericClose >= 0 && expression[genericClose] == '>') {
            var angleDepth = 1
            var genericOpen = genericClose - 1
            while (genericOpen >= 0 && angleDepth > 0) {
                when (expression[genericOpen]) {
                    '>' -> angleDepth++
                    '<' -> angleDepth--
                }
                genericOpen--
            }
            genericOpen++
            if (angleDepth != 0) return null
            readOnlyArguments = expression.substring(genericOpen, genericClose + 1)
            nameEnd = genericOpen
        }
        var nameStart = nameEnd - 1
        while (nameStart >= 0 && (expression[nameStart].isLetterOrDigit() || expression[nameStart] == '_')) nameStart--
        val name = expression.substring(nameStart + 1, nameEnd).takeIf { IDENTIFIER_REGEX.matches(it) } ?: return null
        var separator = nameStart
        while (separator >= 0 && expression[separator].isWhitespace()) separator--
        if (separator < 0 || expression[separator] != '.') return null
        val ownerExpression = expression.substring(0, separator).trim()
        val ownerType = resolveReceiverTypeAt(analysis, ownerExpression, position) ?: return null
        val syntheticCall = name + readOnlyArguments + expression.substring(open, close + 1)
        val callPosition = Position(0, syntheticCall.lastIndexOf(')'))
        val context = MCFPPCallContextFinder.findCandidates(syntheticCall, callPosition).firstOrNull() ?: return null
        val symbol = workspace.accessibleMemberSymbolsForType(analysis, ownerType, position)
            .asSequence()
            .filter { it.kind in CALLABLE_SYMBOL_KINDS && it.name == name }
            .maxByOrNull { signatureMatchScore(it, context, analysis, position) }
            ?: return null
        val returnType = symbol.typeName ?: symbol.detail ?: return null
        return substituteTypeParameters(returnType, typeParameterSubstitutions(symbol, context.readOnlyArguments))
    }

    fun signatureMatchScore(
        symbol: MCFPPSymbol,
        context: MCFPPCallContext,
        analysis: MCFPPDocumentAnalysis,
        position: Position,
        argumentExpressions: List<mcfppParser.ExpressionContext?> = emptyList()
    ): Int {
        val parameters = symbol.parameters.filter {
            it.isReadOnly == (context.argumentGroup == MCFPPArgumentGroup.READ_ONLY)
        }
        val nonBlankArguments = context.arguments.indexOfLast { it.isNotBlank() }.let { last ->
            if (last < 0) 0 else last + 1
        }
        var score = 0
        score += when {
            parameters.isEmpty() && nonBlankArguments == 0 -> 24
            context.activeParameter < parameters.size -> 30
            else -> -40 * (context.activeParameter - parameters.size + 1)
        }
        val required = parameters.count { !it.hasDefault }
        score += when {
            nonBlankArguments > parameters.size -> -30 * (nonBlankArguments - parameters.size)
            nonBlankArguments >= required -> 12
            else -> -4 * (required - nonBlankArguments)
        }
        context.arguments.forEachIndexed { index, argument ->
            val parameter = parameters.getOrNull(index) ?: return@forEachIndexed
            val argumentType = inferArgumentType(analysis, argument, position, argumentExpressions.getOrNull(index))
                ?: return@forEachIndexed
            val parameterType = substituteTypeParameters(
                parameter.typeName,
                typeParameterSubstitutions(symbol, context.readOnlyArguments)
            )
            if (argument.trimStart().startsWith('{') && workspace.templateSchema(analysis).fields(parameterType) != null) {
                score += 20
                return@forEachIndexed
            }
            score += typeCompatibilityScore(argumentType, parameterType, analysis)
        }
        return score
    }

    private val argumentTypeInferences = ThreadLocal.withInitial { mutableSetOf<String>() }

    fun inferArgumentType(
        analysis: MCFPPDocumentAnalysis,
        rawArgument: String,
        position: Position,
        expression: mcfppParser.ExpressionContext? = null
    ): String? {
        val key = "${analysis.uri}|${position.line}:${position.character}|$rawArgument"
        val pending = argumentTypeInferences.get()
        if (!pending.add(key)) return null
        return try {
            inferArgumentTypeUnchecked(analysis, rawArgument, position, expression)
        } finally {
            pending.remove(key)
            if (pending.isEmpty()) argumentTypeInferences.remove()
        }
    }

    private fun inferArgumentTypeUnchecked(
        analysis: MCFPPDocumentAnalysis,
        rawArgument: String,
        position: Position,
        expression: mcfppParser.ExpressionContext?
    ): String? {
        val argument = rawArgument.trim()
        if (argument.isEmpty()) return null
        MCFPPDocumentIndex.referenceFactoryType(argument)?.let { return it }
        stringLiteralType(argument)?.let { return it }
        if (argument == "true" || argument == "false") return "bool"
        if (argument.startsWith('@') && lastTopLevelMemberSeparator(argument) < 0) return "entity"
        if (argument.startsWith('{') && argument.endsWith('}')) return "nbt"
        if (SIGNED_INTEGER.matches(argument)) {
            return when (argument.last().lowercaseChar()) {
                'b' -> "byte"
                's' -> "short"
                'l' -> "long"
                else -> "int"
            }
        }
        if (SIGNED_DECIMAL.matches(argument)) {
            return if (argument.last().lowercaseChar() == 'd') "double" else "float"
        }
        if (expression == null && CALL_EXPRESSION.matches(argument)) {
            inferNestedCallReturnType(analysis, argument, position)?.let { return it }
        }
        if (IDENTIFIER_REGEX.matches(argument)) {
            val symbol = workspace.resolveLocalSymbol(analysis, argument, position)
                ?: workspace.resolveGlobalSymbol(analysis, argument, position)
            symbol?.typeName?.let { return it }
            symbol?.initializer?.let { initializer ->
                val owner = workspace.allAnalyses()[symbol.uri] ?: analysis
                val parsed = symbol.initializerStart?.let { owner.expressionAt(it, initializer) }
                return inferArgumentType(owner, initializer, symbol.selectionRange.start, parsed)
            }
        }
        val checker = workspace.typeChecker(analysis)
        return if (expression != null) checker.expressionType(expression)
        else checker.expressionType(argument, MCFPPTextChanges.positionToOffset(analysis.analysisText, position))
    }

    private fun inferNestedCallReturnType(
        analysis: MCFPPDocumentAnalysis,
        callText: String,
        position: Position
    ): String? {
        val closingOffset = callText.lastIndexOf(')').takeIf { it >= 0 } ?: callText.length
        val callPosition = offsetToPosition(callText, closingOffset)
        val context = MCFPPCallContextFinder.findCandidates(callText, callPosition).firstOrNull() ?: return null
        val symbol = resolveCallableSymbols(analysis, context, position)
            .maxByOrNull { signatureMatchScore(it, context, analysis, position) }
            ?: return null
        val returnType = symbol.typeName ?: symbol.detail ?: return null
        return substituteTypeParameters(returnType, typeParameterSubstitutions(symbol, context.readOnlyArguments))
    }

    private fun typeCompatibilityScore(
        argumentType: String,
        parameterType: String,
        analysis: MCFPPDocumentAnalysis
    ): Int {
        val argument = normalizeTypeName(workspace.expandTypeAlias(analysis, argumentType))?.lowercase() ?: return 0
        val parameter = normalizeTypeName(workspace.expandTypeAlias(analysis, parameterType))?.lowercase() ?: return 0
        return when {
            argument == parameter -> 16
            parameter == "any" -> 4
            argument in NUMERIC_TYPES && parameter in NUMERIC_TYPES ->
                if (workspace.typeChecker(analysis).isAssignable(parameterType, argumentType)) 7 else -8
            else -> workspace.inheritanceDistance(analysis, argument, parameter)
                ?.let { distance -> 14 - distance.coerceAtMost(8) }
                ?: -8
        }
    }

    fun resolveCallTarget(
        analysis: MCFPPDocumentAnalysis,
        occurrence: MCFPPReferenceOccurrence
    ): MCFPPSymbol? {
        val receiver = if (occurrence.namespaceQualifier == null) {
            analysis.receiverBefore(occurrence.range.end)
        } else {
            null
        }
        val normalContext = MCFPPCallContext(
            name = occurrence.name,
            qualifier = occurrence.namespaceQualifier ?: receiver,
            qualifierSeparator = when {
                occurrence.namespaceQualifier != null -> ':'
                receiver != null -> '.'
                else -> null
            },
            argumentGroup = MCFPPArgumentGroup.NORMAL,
            activeParameter = (occurrence.normalArguments.size - 1).coerceAtLeast(0),
            arguments = occurrence.normalArguments,
            readOnlyArguments = occurrence.readOnlyArguments,
            argumentPositions = occurrence.normalArgumentRanges.map { it.start }
        )
        val candidates = resolveCallableSymbols(analysis, normalContext, occurrence.range.start)
        if (candidates.isEmpty()) return null
        val normalExpressions = normalContext.parsedArguments(analysis)
        val readOnlyContext = normalContext.copy(
            argumentGroup = MCFPPArgumentGroup.READ_ONLY,
            activeParameter = occurrence.readOnlyArguments.lastIndex,
            arguments = occurrence.readOnlyArguments,
            argumentPositions = occurrence.readOnlyArgumentRanges.map { it.start }
        ).takeIf { it.arguments.isNotEmpty() }
        val readOnlyExpressions = readOnlyContext?.parsedArguments(analysis).orEmpty()
        return candidates.map { candidate ->
            val inferred = candidate.inferTypesFromArguments.mapValues { (_, index) ->
                occurrence.normalArguments.getOrNull(index)?.let { argument ->
                    inferArgumentType(analysis, argument, occurrence.normalArgumentRanges.getOrNull(index)?.start
                        ?: occurrence.range.start, normalExpressions.getOrNull(index))
                } ?: "any"
            }
            MCFPPValueMembers.substitute(candidate, inferred)
        }.maxByOrNull { candidate ->
            var score = signatureMatchScore(candidate, normalContext, analysis, occurrence.range.start, normalExpressions)
            if (readOnlyContext != null) {
                score += signatureMatchScore(
                    candidate,
                    readOnlyContext,
                    analysis,
                    occurrence.range.start,
                    readOnlyExpressions
                )
            }
            score
        }
    }

    private companion object {
        val SIGNED_INTEGER = Regex("[+-]?\\d+[bBsSlL]?")
        val SIGNED_DECIMAL = Regex("[+-]?(?:\\d+\\.\\d*|\\d*\\.\\d+)(?:[eE][+-]?\\d+)?[fFdD]?")
        val CALL_EXPRESSION = Regex("([A-Za-z_][A-Za-z0-9_.:]*)\\s*(?:<.*>)?\\s*\\(.*\\)")
        val NUMERIC_TYPES = setOf("byte", "short", "int", "long", "float", "double")
    }
}
