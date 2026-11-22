package top.mcfpp.lsp

import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.ParseTreeWalker
import org.antlr.v4.runtime.tree.TerminalNode
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import top.mcfpp.antlr.mcfppLexer
import top.mcfpp.antlr.mcfppParser
import top.mcfpp.antlr.mcfppParserBaseListener
import top.mcfpp.language.ContextBuiltins

internal fun stringLiteralType(source: String): String? {
    if (source.length < 2 || source.first() != source.last() || source.first() !in "\"'") return null
    val lexer = mcfppLexer(CharStreams.fromString(source)).apply { removeErrorListeners() }
    when (lexer.nextToken().type) {
        mcfppLexer.LineString -> return "string"
        mcfppLexer.DOUBLE_QUOTE_OPEN, mcfppLexer.TRIPLE_QUOTE_OPEN -> Unit
        else -> return null
    }
    while (true) {
        when (lexer.nextToken().type) {
            mcfppLexer.MultiLineStrExprStart -> return "text"
            mcfppLexer.DOUBLE_QUOTE_CLOSE, mcfppLexer.TRIPLE_QUOTE_CLOSE -> return "string"
            Token.EOF -> return null
        }
    }
}

enum class MCFPPScopeKind {
    TYPE,
    FUNCTION,
    BLOCK
}

data class MCFPPScopeRegion(
    val id: String,
    val kind: MCFPPScopeKind,
    val name: String,
    val range: Range,
    val parentScopeId: String?
)

data class MCFPPReferenceOccurrence(
    val name: String,
    val range: Range,
    val enclosingTypeScopeId: String?,
    val enclosingFunctionScopeId: String?,
    val isCall: Boolean = false,
    val namespaceQualifier: String? = null,
    val readOnlyArguments: List<String> = emptyList(),
    val normalArguments: List<String> = emptyList(),
    val readOnlyArgumentRanges: List<Range> = emptyList(),
    val normalArgumentRanges: List<Range> = emptyList(),
    val isNamedArgument: Boolean = false,
    val isAssignmentTarget: Boolean = false
)

data class MCFPPParserSemanticData(
    val symbols: List<MCFPPSymbol>,
    val variables: Map<String, String?>,
    val typeMembers: Map<String, List<MCFPPSymbol>>,
    val topLevelSymbols: List<MCFPPSymbol>,
    val referencesByName: Map<String, List<Range>>,
    val declarationsByName: Map<String, List<MCFPPSymbol>>,
    val referenceOccurrencesByName: Map<String, List<MCFPPReferenceOccurrence>>,
    val scopeRegions: List<MCFPPScopeRegion>,
    val diagnostics: List<Diagnostic>,
    val compilationUnit: mcfppParser.CompilationUnitContext? = null
)

object MCFPPParserSemanticExtractor {
    private data class ParserDiagnosticKey(val line: Int, val character: Int, val message: String)

    private class LineMap(text: String) {
        private val textLength = text.length
        private val starts = buildList {
            add(0)
            text.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
        }.toIntArray()

        fun position(offset: Int): Position {
            val bounded = offset.coerceIn(0, textLength)
            var low = 0
            var high = starts.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (starts[middle] <= bounded) low = middle + 1 else high = middle
            }
            val line = (low - 1).coerceAtLeast(0)
            return Position(line, bounded - starts[line])
        }
    }

    fun extract(uri: String, text: String): MCFPPParserSemanticData =
        extractNormalized(uri, text.replace("\r\n", "\n"))

    internal fun extractNormalized(uri: String, normalizedText: String): MCFPPParserSemanticData {
        val diagnostics = mutableListOf<Diagnostic>()
        val lineMap = LineMap(normalizedText)
        val errorListener = object : BaseErrorListener() {
            override fun syntaxError(
                recognizer: Recognizer<*, *>?,
                offendingSymbol: Any?,
                line: Int,
                charPositionInLine: Int,
                msg: String,
                e: RecognitionException?
            ) {
                val token = offendingSymbol as? Token
                val message = if (token != null && recognizer?.vocabulary?.getSymbolicName(token.type) == "REF")
                    "'ref expression' has been removed. Pass a ScoreRef or NBTRef<T> created from an explicit Minecraft address."
                else msg
                diagnostics += Diagnostic(
                    diagnosticRange(lineMap, line, charPositionInLine, offendingSymbol),
                    message,
                    DiagnosticSeverity.Error,
                    "mcfpp"
                )
            }
        }
        val lexer = mcfppLexer(CharStreams.fromString(normalizedText))
        lexer.removeErrorListeners()
        lexer.addErrorListener(errorListener)
        val parser = mcfppParser(CommonTokenStream(lexer)).apply {
            removeErrorListeners()
            addErrorListener(errorListener)
        }
        val collector = SemanticCollector(uri, normalizedText, diagnostics, lineMap)
        val compilationUnit = parser.compilationUnit()
        ParseTreeWalker.DEFAULT.walk(collector, compilationUnit)
        return collector.build(
            diagnostics.distinctBy { ParserDiagnosticKey(it.range.start.line, it.range.start.character, it.message) }
        ).copy(compilationUnit = compilationUnit)
    }

    private fun diagnosticRange(lineMap: LineMap, line: Int, character: Int, offendingSymbol: Any?): Range {
        val token = offendingSymbol as? Token
        if (token != null && token.startIndex >= 0 && token.stopIndex >= token.startIndex) {
            return Range(
                lineMap.position(token.startIndex),
                lineMap.position(token.stopIndex + 1)
            )
        }
        val start = Position((line - 1).coerceAtLeast(0), character.coerceAtLeast(0))
        return Range(start, Position(start.line, start.character + 1))
    }

    private class SemanticCollector(
        private val uri: String,
        private val text: String,
        private val diagnostics: MutableList<Diagnostic>,
        private val lineMap: LineMap
    ) : mcfppParserBaseListener() {
        private data class ScopeFrame(
            val ctx: ParserRuleContext,
            val id: String,
            val name: String,
            val kind: MCFPPScopeKind,
            val parentScopeId: String?
        )

        private val typeStack = ArrayDeque<ScopeFrame>()
        private val functionStack = ArrayDeque<ScopeFrame>()
        private val blockStack = ArrayDeque<ScopeFrame>()
        private val declarationTokenIndexes = mutableSetOf<Int>()
        private val assignmentTargets = mutableSetOf<Int>()
        private val enumBindings = mutableListOf<Pair<String, top.mcfpp.nbt.tags.Tag<*>>>()
        private val symbols = mutableListOf<MCFPPSymbol>()
        private val variables = linkedMapOf<String, String?>()
        private val typeMembers = linkedMapOf<String, MutableList<MCFPPSymbol>>()
        private val typeReadOnlyParameters = linkedMapOf<String, List<MCFPPFunctionParameter>>()
        private val topLevelSymbols = mutableListOf<MCFPPSymbol>()
        private val referencesByName = linkedMapOf<String, MutableList<Range>>()
        private val referenceOccurrencesByName = linkedMapOf<String, MutableList<MCFPPReferenceOccurrence>>()
        private val scopeRegions = mutableListOf<MCFPPScopeRegion>()

        override fun enterEveryRule(ctx: ParserRuleContext) {
            when (ctx) {
                is mcfppParser.NamespaceDeclarationContext -> recordNamespace(ctx)
                is mcfppParser.TypealiasDeclarationContext -> recordTypeAlias(ctx)
                is mcfppParser.TemplateDeclarationContext -> recordTemplate(ctx)
                is mcfppParser.ObjectTemplateDeclarationContext -> recordObjectTemplate(ctx)
                is mcfppParser.EnumDeclarationContext -> recordEnum(ctx)
                is mcfppParser.StatementExpressionContext -> ctx.varWithSelector()?.stop?.let { assignmentTargets.add(it.tokenIndex) }
                is mcfppParser.FunctionDeclarationContext -> recordFunction(ctx.functionDeclarationPart(), null, ctx)
                is mcfppParser.InlineFunctionDeclarationContext -> recordFunction(ctx.functionDeclarationPart(), null, ctx)
                is mcfppParser.CompileTimeFuncDeclarationContext -> recordFunction(ctx.functionDeclarationPart(), null, ctx)
                is mcfppParser.NativeFuncDeclarationContext -> recordFunction(ctx.functionDeclarationPart(), null, ctx)
                is mcfppParser.TemplateFunctionDeclarationContext ->
                    recordFunction(ctx.functionDeclarationPart(), null, ctx, isEntityMember = ctx.ENTITY() != null)
                is mcfppParser.ExtensionFunctionDeclarationContext -> recordExtensionFunction(ctx)
                is mcfppParser.TemplateConstructorDeclarationContext -> recordConstructor(ctx)
                is mcfppParser.TemplateFactoryDeclarationContext -> recordFactory(ctx)
                is mcfppParser.OperationOverrideDeclarationContext -> recordOperator(
                    operator = ctx.supportOperator(),
                    parameters = ctx.functionParams(),
                    returnType = ctx.functionReturnType()?.text,
                    ownerCtx = ctx
                )
                is mcfppParser.NativeOperationOverrideDeclarationContext -> recordOperator(
                    operator = ctx.supportOperator(),
                    parameters = ctx.functionParams(),
                    returnType = ctx.functionReturnType()?.text,
                    ownerCtx = ctx
                )
                is mcfppParser.FieldDeclarationContext -> recordField(ctx)
                is mcfppParser.TemplateFieldDeclarationContext -> recordTemplateField(ctx)
                is mcfppParser.ParameterContext -> recordParameter(ctx, false)
                is mcfppParser.ReadOnlyParameterContext -> recordParameter(ctx, true)
                is mcfppParser.EnumMemberContext -> recordEnumMember(ctx)
                is mcfppParser.CurlBlockContext -> recordBlockScope(ctx)
                is mcfppParser.InlineLambdaContext -> recordLambdaScope(ctx)
                is mcfppParser.LambdaParameterContext -> recordParameter(ctx, false)
                is mcfppParser.ContextParameterContext -> recordParameter(ctx, false)
                is mcfppParser.ForeachStatementContext -> recordForeachVariable(ctx)
            }
        }

        override fun exitEveryRule(ctx: ParserRuleContext) {
            if (typeStack.lastOrNull()?.ctx === ctx) {
                when (ctx) {
                    is mcfppParser.TemplateDeclarationContext -> recordDefaultTemplateConstructor(ctx)
                    is mcfppParser.ObjectTemplateDeclarationContext -> recordDefaultObjectConstructor(ctx)
                }
                recordScopeRegion(typeStack.removeLast())
            }
            if (functionStack.lastOrNull()?.ctx === ctx) {
                recordScopeRegion(functionStack.removeLast())
            }
            if (blockStack.lastOrNull()?.ctx === ctx) {
                recordScopeRegion(blockStack.removeLast())
            }
        }

        override fun visitTerminal(node: TerminalNode) {
            val token = node.symbol ?: return
            if (token.type != mcfppParser.Identifier) {
                return
            }
            if (typeStack.lastOrNull()?.ctx is mcfppParser.EnumDeclarationContext) return
            if (node.parent is mcfppParser.JavaReferContext) {
                return
            }
            if (node.parent is mcfppParser.PropertyOperatorExpressionContext ||
                node.parent is mcfppParser.CommonBinaryOperatorExpressionContext ||
                node.parent is mcfppParser.ClassNameContext ||
                node.parent is mcfppParser.NamespaceSegmentContext
            ) {
                return
            }
            if (token.tokenIndex in declarationTokenIndexes) {
                return
            }
            val namedArgument = node.parent is mcfppParser.CallArgumentContext &&
                (node.parent as mcfppParser.CallArgumentContext).ASSIGNMENT() != null
            val range = tokenRange(token)
            val namespaceId = node.parent as? mcfppParser.NamespaceIDContext
            if (namespaceId != null) {
                val identifiers = namespaceId.getTokens(mcfppParser.Identifier)
                if (namespaceId.getToken(mcfppParser.COLON, 0) != null && identifiers.lastOrNull()?.symbol?.tokenIndex != token.tokenIndex) {
                    return
                }
            }
            val functionCall = namespaceId?.parent as? mcfppParser.FunctionCallContext
            val isCall = functionCall != null && namespaceId.getTokens(mcfppParser.Identifier).lastOrNull()?.symbol?.tokenIndex == token.tokenIndex
            val namespaceQualifier = if (isCall) {
                namespaceId?.namespacePath()?.text
            } else {
                null
            }
            val readOnlyArguments = functionCall?.arguments()?.readOnlyArgs()?.expressionList()?.expression().orEmpty()
            val normalArguments = functionCall?.arguments()?.normalArgs().argumentExpressions()
            referencesByName.getOrPut(token.text) { mutableListOf() }.add(range)
            referenceOccurrencesByName.getOrPut(token.text) { mutableListOf() }.add(
                MCFPPReferenceOccurrence(
                    name = token.text,
                    range = range,
                    enclosingTypeScopeId = currentTypeScopeId(),
                    enclosingFunctionScopeId = currentFunctionScopeId(),
                    isCall = isCall,
                    namespaceQualifier = namespaceQualifier,
                    readOnlyArguments = readOnlyArguments.map { it.sourceText() },
                    normalArguments = normalArguments.map { it.sourceText() },
                    readOnlyArgumentRanges = readOnlyArguments.map(::contextRange),
                    normalArgumentRanges = normalArguments.map(::contextRange),
                    isNamedArgument = namedArgument,
                    isAssignmentTarget = token.tokenIndex in assignmentTargets
                )
            )
        }

        fun build(diagnostics: List<Diagnostic>): MCFPPParserSemanticData {
            val dedupedSymbols = symbols.distinctBy { listOf(it.name, it.kind.name, it.range.start.line, it.range.start.character, it.containerName, it.receiverType, it.parameters).joinToString("|") }
            val mergedTypeMembers = typeMembers.mapValues { (_, members) ->
                members.distinctBy { listOf(it.name, it.kind.name, it.range.start.line, it.range.start.character, it.containerName, it.parameters).joinToString("|") }
            }
            val dedupedTopLevel = topLevelSymbols.distinctBy { listOf(it.name, it.kind.name, it.range.start.line, it.range.start.character).joinToString("|") }
            val dedupedReferences = referencesByName.mapValues { (_, ranges) ->
                ranges.distinctBy { listOf(it.start.line, it.start.character, it.end.line, it.end.character).joinToString(":") }
            }
            return MCFPPParserSemanticData(
                symbols = dedupedSymbols,
                variables = variables,
                typeMembers = mergedTypeMembers,
                topLevelSymbols = dedupedTopLevel,
                referencesByName = dedupedReferences,
                declarationsByName = dedupedSymbols.groupBy { it.name },
                referenceOccurrencesByName = referenceOccurrencesByName.mapValues { (_, occurrences) ->
                    occurrences.distinctBy {
                        listOf(
                            it.name,
                            it.range.start.line,
                            it.range.start.character,
                            it.range.end.line,
                            it.range.end.character,
                            it.enclosingTypeScopeId,
                            it.enclosingFunctionScopeId,
                            it.isCall,
                            it.namespaceQualifier,
                            it.readOnlyArguments.joinToString(","),
                            it.normalArguments.joinToString(","),
                            it.readOnlyArgumentRanges.joinToString(",") { range -> "${range.start.line}:${range.start.character}" },
                            it.normalArgumentRanges.joinToString(",") { range -> "${range.start.line}:${range.start.character}" }
                        ).joinToString("|")
                    }
                },
                scopeRegions = scopeRegions.distinctBy { listOf(it.id, it.range.start.line, it.range.start.character, it.range.end.line, it.range.end.character).joinToString("|") },
                diagnostics = diagnostics
            )
        }

        private fun recordNamespace(ctx: mcfppParser.NamespaceDeclarationContext) {
            val identifiers = ctx.namespacePath()?.namespaceSegment()?.map { it.start }.orEmpty()
            if (identifiers.isEmpty()) {
                return
            }
            identifiers.forEach(::markDeclaration)
            recordSymbol(
                declarationToken = identifiers.last(),
                name = identifiers.joinToString(".") { it.text },
                kind = SymbolKind.Namespace,
                detail = identifiers.joinToString(".") { it.text },
                typeName = null,
                receiverType = null,
                topLevel = true,
                containerName = null,
                fullRange = contextRange(ctx)
            )
        }

        private fun recordTemplate(ctx: mcfppParser.TemplateDeclarationContext) {
            val declarationName = ctx.compoundDeclaration()?.declarationName() ?: ctx.declarationName() ?: return
            val nameToken = declarationName.classWithoutNamespace()?.Identifier() ?: return
            markDeclaration(nameToken)
            val name = nameToken.text
            val entityTemplate = ctx.ENTITY() != null
            val aliasTarget = ctx.type()?.text?.trim()
            val superTypes = collectSuperTypes(ctx.compoundDeclaration())
            val readOnlyParameters = extractParameters(
                declarationName.readOnlyParams()?.readOnlyParameterList()?.readOnlyParameter().orEmpty(),
                true
            )
            typeReadOnlyParameters[name] = readOnlyParameters
            val symbol = recordSymbol(
                declarationToken = nameToken.symbol,
                name = name,
                kind = SymbolKind.Class,
                detail = aliasTarget,
                typeName = name,
                receiverType = null,
                topLevel = true,
                containerName = null,
                superTypes = superTypes,
                fullRange = contextRange(ctx),
                parameters = readOnlyParameters,
                isEntityTemplate = entityTemplate,
                isDataTemplate = !entityTemplate,
                isAbstract = ctx.ABSTRACT() != null
            )
            typeStack.addLast(createScopeFrame(ctx, MCFPPScopeKind.TYPE, symbol.name))
            if (entityTemplate) recordEntitySummon(name, nameToken.symbol, ctx)
            if (aliasTarget != null) {
                val delegatedValue = recordSymbol(
                    declarationToken = nameToken.symbol,
                    name = "value",
                    kind = SymbolKind.Field,
                    detail = aliasTarget,
                    typeName = aliasTarget,
                    receiverType = null,
                    topLevel = false,
                    containerName = name,
                    accessModifier = MCFPPAccessModifier.PRIVATE,
                    isSynthetic = true
                )
                typeMembers.getOrPut(name) { mutableListOf() }.add(delegatedValue)
            }
        }

        private fun recordEntitySummon(owner: String, token: Token, ownerCtx: ParserRuleContext) {
            for (parameters in listOf(
                emptyList(),
                listOf(MCFPPFunctionParameter("d", "nbt")),
                listOf(MCFPPFunctionParameter("position", "pos3")),
                listOf(MCFPPFunctionParameter("position", "ContextPosition"))
            )) {
                val symbol = recordSymbol(
                    declarationToken = token,
                    name = "summon",
                    kind = SymbolKind.Method,
                    detail = "EntityRef<$owner>",
                    typeName = "EntityRef<$owner>",
                    receiverType = owner,
                    topLevel = false,
                    containerName = owner,
                    fullRange = contextRange(ownerCtx),
                    parameters = parameters,
                    isSynthetic = true
                )
                typeMembers.getOrPut(owner) { mutableListOf() }.add(symbol)
            }
        }

        private fun recordDefaultTemplateConstructor(ctx: mcfppParser.TemplateDeclarationContext) {
            if (ctx.ABSTRACT() != null) {
                return
            }
            val owner = currentTypeName() ?: return
            if (typeMembers[owner].orEmpty().any { it.kind == SymbolKind.Constructor && it.parameters.none { p -> !p.isReadOnly && !p.hasDefault } }) {
                return
            }
            if (ctx.ENTITY() == null && typeMembers[owner].orEmpty().any {
                it.kind == SymbolKind.Field && !it.isSynthetic && it.initializer == null && it.typeName?.endsWith('?') != true
            }) return
            val delegatedType = ctx.type()?.text?.trim()?.takeIf { it.isNotEmpty() }
            recordSyntheticConstructor(
                owner = owner,
                declarationToken = (ctx.declarationName() ?: ctx.compoundDeclaration()?.declarationName())
                    ?.classWithoutNamespace()
                    ?.Identifier()
                    ?.symbol
                    ?: return,
                parameters = delegatedType
                    ?.let { listOf(MCFPPFunctionParameter("value", it)) }
                    .orEmpty()
            )
        }

        private fun recordDefaultObjectConstructor(ctx: mcfppParser.ObjectTemplateDeclarationContext) {
            val owner = currentTypeName() ?: return
            if (typeMembers[owner].orEmpty().any { it.kind == SymbolKind.Constructor }) {
                return
            }
            val declarationToken = ctx.compoundDeclaration()
                ?.declarationName()
                ?.classWithoutNamespace()
                ?.Identifier()
                ?.symbol
                ?: return
            recordSyntheticConstructor(owner, declarationToken, emptyList())
        }

        private fun recordSyntheticConstructor(
            owner: String,
            declarationToken: Token,
            parameters: List<MCFPPFunctionParameter>
        ) {
            val symbol = recordSymbol(
                declarationToken = declarationToken,
                name = owner,
                kind = SymbolKind.Constructor,
                detail = owner,
                typeName = owner,
                receiverType = null,
                topLevel = false,
                containerName = owner,
                parameters = typeReadOnlyParameters[owner].orEmpty() + parameters,
                isSynthetic = true
            )
            typeMembers.getOrPut(owner) { mutableListOf() }.add(symbol)
            val factory = symbol.copy(name = "new", kind = SymbolKind.Method, isStatic = true)
            symbols.add(factory)
            typeMembers.getOrPut(owner) { mutableListOf() }.add(factory)
        }

        private fun recordObjectTemplate(ctx: mcfppParser.ObjectTemplateDeclarationContext) {
            val declarationName = ctx.compoundDeclaration()?.declarationName() ?: return
            val nameToken = declarationName.classWithoutNamespace()?.Identifier() ?: return
            markDeclaration(nameToken)
            val superTypes = collectSuperTypes(ctx.compoundDeclaration())
            val readOnlyParameters = extractParameters(
                declarationName.readOnlyParams()?.readOnlyParameterList()?.readOnlyParameter().orEmpty(),
                true
            )
            typeReadOnlyParameters[nameToken.text] = readOnlyParameters
            val symbol = recordSymbol(
                declarationToken = nameToken.symbol,
                name = nameToken.text,
                kind = SymbolKind.Class,
                detail = null,
                typeName = nameToken.text,
                receiverType = null,
                topLevel = true,
                containerName = null,
                superTypes = superTypes,
                fullRange = contextRange(ctx),
                parameters = readOnlyParameters
            )
            typeStack.addLast(createScopeFrame(ctx, MCFPPScopeKind.TYPE, symbol.name))
        }

        private fun recordEnum(ctx: mcfppParser.EnumDeclarationContext) {
            enumBindings.clear()
            val nameToken = ctx.getToken(mcfppParser.Identifier, 0) ?: return
            markDeclaration(nameToken)
            val symbol = recordSymbol(
                declarationToken = nameToken.symbol,
                name = nameToken.text,
                kind = SymbolKind.Enum,
                detail = null,
                typeName = nameToken.text,
                receiverType = null,
                topLevel = true,
                containerName = null,
                fullRange = contextRange(ctx)
            )
            typeStack.addLast(createScopeFrame(ctx, MCFPPScopeKind.TYPE, symbol.name))
        }

        private fun recordFunction(
            part: mcfppParser.FunctionDeclarationPartContext?,
            receiverType: String?,
            ownerCtx: ParserRuleContext,
            isEntityMember: Boolean = false
        ) {
            if (part == null) {
                return
            }
            val nameToken = part.getToken(mcfppParser.Identifier, 0) ?: return
            markDeclaration(nameToken)
            val currentType = currentTypeName()
            val returnType = part.functionReturnType()?.text?.trim()
            val kind = if (currentType != null || receiverType != null) SymbolKind.Method else SymbolKind.Function
            val symbol = recordSymbol(
                declarationToken = nameToken.symbol,
                name = nameToken.text,
                kind = kind,
                detail = returnType,
                typeName = returnType,
                receiverType = receiverType,
                topLevel = currentType == null && receiverType == null,
                containerName = currentType,
                fullRange = contextRange(ownerCtx),
                parameters = extractFunctionParameters(part.functionParams()),
                contextParameters = contextParameters(part.contextParameters() ?: ownerCtx.getRuleContext(mcfppParser.ContextParametersContext::class.java, 0)),
                accessModifier = accessModifier(ownerCtx),
                isEntityMember = isEntityMember,
                isNative = ownerCtx is mcfppParser.NativeFuncDeclarationContext
            )
            val memberOwner = receiverType ?: currentType
            if (memberOwner != null) {
                typeMembers.getOrPut(memberOwner) { mutableListOf() }.add(symbol)
            }
            functionStack.addLast(createScopeFrame(ownerCtx, MCFPPScopeKind.FUNCTION, symbol.name))
        }

        private fun recordExtensionFunction(ctx: mcfppParser.ExtensionFunctionDeclarationContext) {
            val identifiers = ctx.getTokens(mcfppParser.Identifier)
            val nameToken = identifiers.lastOrNull() ?: return
            markDeclaration(nameToken)
            val currentType = currentTypeName()
            val receiverType = ctx.getRuleContext(mcfppParser.TypeContext::class.java, 0)?.text?.trim()?.substringAfterLast(':')
            val returnType = ctx.functionReturnType()?.text?.trim()
            val symbol = recordSymbol(
                declarationToken = nameToken.symbol,
                name = nameToken.text,
                kind = if (currentType != null || receiverType != null) SymbolKind.Method else SymbolKind.Function,
                detail = returnType,
                typeName = returnType,
                receiverType = receiverType,
                topLevel = currentType == null && receiverType == null,
                containerName = currentType,
                fullRange = contextRange(ctx),
                parameters = extractFunctionParameters(ctx.functionParams())
                , contextParameters = contextParameters(ctx.contextParameters())
            )
            if (receiverType != null) {
                typeMembers.getOrPut(receiverType) { mutableListOf() }.add(symbol)
            }
            functionStack.addLast(createScopeFrame(ctx, MCFPPScopeKind.FUNCTION, symbol.name))
        }

        private fun recordConstructor(ctx: mcfppParser.TemplateConstructorDeclarationContext) {
            val owner = currentTypeName() ?: return
            val constructorToken = ctx.getToken(mcfppParser.CONSTRUCTOR, 0) ?: return
            val symbol = recordSymbol(
                declarationToken = constructorToken.symbol,
                name = owner,
                kind = SymbolKind.Constructor,
                detail = owner,
                typeName = owner,
                receiverType = null,
                topLevel = false,
                containerName = owner,
                fullRange = contextRange(ctx),
                parameters = typeReadOnlyParameters[owner].orEmpty() +
                    extractParameters(ctx.normalParams()?.parameterList()?.parameter().orEmpty(), false),
                accessModifier = accessModifier(ctx)
            )
            typeMembers.getOrPut(owner) { mutableListOf() }.add(symbol)
            functionStack.addLast(createScopeFrame(ctx, MCFPPScopeKind.FUNCTION, owner))
        }

        private fun recordFactory(ctx: mcfppParser.TemplateFactoryDeclarationContext) {
            val owner = currentTypeName() ?: return
            val token = ctx.NEW()?.symbol ?: return
            val parameters = extractParameters(ctx.normalParams()?.parameterList()?.parameter().orEmpty(), false)
            val symbol = recordSymbol(token, owner, SymbolKind.Constructor, owner, owner, null, false, owner,
                fullRange = contextRange(ctx), parameters = typeReadOnlyParameters[owner].orEmpty() + parameters,
                accessModifier = accessModifier(ctx))
            typeMembers.getOrPut(owner) { mutableListOf() }.add(symbol)
            val factory = symbol.copy(name = "new", kind = SymbolKind.Method, isStatic = true)
            symbols.add(factory)
            typeMembers.getOrPut(owner) { mutableListOf() }.add(factory)
            functionStack.addLast(createScopeFrame(ctx, MCFPPScopeKind.FUNCTION, "new"))
        }

        private fun recordOperator(
            operator: mcfppParser.SupportOperatorContext?,
            parameters: mcfppParser.FunctionParamsContext?,
            returnType: String?,
            ownerCtx: ParserRuleContext
        ) {
            val owner = currentTypeName() ?: return
            val operatorToken = operator?.start ?: return
            if (operatorToken.type == mcfppParser.Identifier) {
                markDeclaration(operatorToken)
            }
            val name = "operator ${operator.text}"
            val normalizedReturnType = returnType?.trim()
            val symbol = recordSymbol(
                declarationToken = operatorToken,
                name = name,
                kind = SymbolKind.Method,
                detail = normalizedReturnType,
                typeName = normalizedReturnType,
                receiverType = null,
                topLevel = false,
                containerName = owner,
                fullRange = contextRange(ownerCtx),
                parameters = extractFunctionParameters(parameters),
                accessModifier = accessModifier(ownerCtx)
            )
            typeMembers.getOrPut(owner) { mutableListOf() }.add(symbol)
            functionStack.addLast(createScopeFrame(ownerCtx, MCFPPScopeKind.FUNCTION, name))
        }

        private fun recordField(ctx: mcfppParser.FieldDeclarationContext) {
            val nameToken = ctx.getToken(mcfppParser.Identifier, 0) ?: return
            markDeclaration(nameToken)
            val currentType = currentTypeName()
            val currentFunction = currentFunctionName()
            val typeName = ctx.getRuleContext(mcfppParser.TypeContext::class.java, 0)?.text?.trim()
                ?: MCFPPDocumentIndex.referenceFactoryType(ctx.text.substringAfter('=', ""))
                ?: inferContextInitializer(ctx.expression()?.text)
                ?: inferCallInitializer(ctx.expression()?.text)
            variables[nameToken.text] = typeName
            val kind = if (currentType != null && currentFunction == null) SymbolKind.Field else SymbolKind.Variable
            val symbol = recordSymbol(
                declarationToken = nameToken.symbol,
                name = nameToken.text,
                kind = kind,
                detail = typeName,
                typeName = typeName,
                receiverType = null,
                topLevel = currentType == null && currentFunction == null && currentBlockScopeId() == null,
                containerName = currentFunction ?: currentType,
                accessModifier = accessModifier(ctx),
                initializer = ctx.expression()?.sourceText(),
                initializerStart = ctx.expression()?.let { contextRange(it).start },
                functionValue = functionValue(ctx.type(), ctx.expression())
            )
            if (currentType != null && currentFunction == null) {
                typeMembers.getOrPut(currentType) { mutableListOf() }.add(symbol)
            }
        }

        private fun recordTemplateField(ctx: mcfppParser.TemplateFieldDeclarationContext) {
            val nameToken = ctx.getToken(mcfppParser.Identifier, 0) ?: return
            markDeclaration(nameToken)
            val currentType = currentTypeName()
            val currentFunction = currentFunctionName()
            val typeName = ctx.getRuleContext(mcfppParser.TemplateTypeContext::class.java, 0)?.text?.trim()
            variables[nameToken.text] = typeName
            val kind = if (currentType != null && currentFunction == null) SymbolKind.Field else SymbolKind.Variable
            val symbol = recordSymbol(
                declarationToken = nameToken.symbol,
                name = nameToken.text,
                kind = kind,
                detail = typeName,
                typeName = typeName,
                receiverType = null,
                topLevel = currentType == null,
                containerName = currentFunction ?: currentType,
                accessModifier = accessModifier(ctx),
                isEntityMember = ctx.ENTITY() != null,
                initializer = ctx.expression()?.sourceText(),
                initializerStart = ctx.expression()?.let { contextRange(it).start }
            )
            if (currentType != null && currentFunction == null) {
                typeMembers.getOrPut(currentType) { mutableListOf() }.add(symbol)
            }
        }

        private fun recordParameter(ctx: ParserRuleContext, isReadOnly: Boolean) {
            val nameToken = ctx.getToken(mcfppParser.Identifier, 0)?.symbol ?: return
            markDeclaration(nameToken)
            val lambda = generateSequence(ctx.parent) { it.parent }.filterIsInstance<mcfppParser.InlineLambdaContext>().firstOrNull()
            val entry = lambda?.let { ContextBuiltins.entryBefore(text, it.start.startIndex) }
            val typeName = ctx.getRuleContext(mcfppParser.TypeContext::class.java, 0)?.text?.trim()
                ?: if (isReadOnly) "Type" else if (ctx is mcfppParser.LambdaParameterContext && entry != null)
                    blockStack.lastOrNull()?.name?.removePrefix("context:") else if (ctx is mcfppParser.LambdaParameterContext)
                    lambda?.let(::referenceReadType) else null
            val isTypeParameter = isReadOnly && typeName.equals("Type", ignoreCase = true)
            variables[nameToken.text] = typeName
            recordSymbol(
                declarationToken = nameToken,
                name = nameToken.text,
                kind = if (isTypeParameter) SymbolKind.TypeParameter else SymbolKind.Variable,
                detail = typeName,
                typeName = typeName,
                receiverType = null,
                topLevel = false,
                containerName = currentFunctionName() ?: currentTypeName(),
                isParameter = true,
                isContextProvider = ctx is mcfppParser.ContextParameterContext || ctx is mcfppParser.LambdaParameterContext && entry != null,
                isReadOnly = isReadOnly,
                functionValue = functionValue(ctx.getRuleContext(mcfppParser.TypeContext::class.java, 0), null)
            )
        }

        private fun recordTypeAlias(ctx: mcfppParser.TypealiasDeclarationContext) {
            val nameToken = ctx.getToken(mcfppParser.Identifier, 0) ?: return
            val targetType = ctx.type()?.text?.trim()?.takeIf { it.isNotEmpty() } ?: return
            markDeclaration(nameToken)
            recordSymbol(
                declarationToken = nameToken.symbol,
                name = nameToken.text,
                kind = SymbolKind.TypeParameter,
                detail = targetType,
                typeName = targetType,
                receiverType = null,
                topLevel = true,
                containerName = null,
                fullRange = contextRange(ctx)
            )
        }

        private fun extractFunctionParameters(ctx: mcfppParser.FunctionParamsContext?): List<MCFPPFunctionParameter> {
            if (ctx == null) {
                return emptyList()
            }
            return buildList {
                addAll(extractParameters(ctx.readOnlyParams()?.readOnlyParameterList()?.readOnlyParameter().orEmpty(), true))
                addAll(extractParameters(ctx.normalParams()?.parameterList()?.parameter().orEmpty(), false))
            }
        }

        private fun extractParameters(
            contexts: List<ParserRuleContext>,
            readOnly: Boolean
        ): List<MCFPPFunctionParameter> = contexts.mapNotNull { parameter ->
            val typeName = parameter.getRuleContext(mcfppParser.TypeContext::class.java, 0)?.text?.trim()?.takeIf { it.isNotEmpty() }
                ?: if (readOnly) "Type" else return@mapNotNull null
            val name = parameter.getToken(mcfppParser.Identifier, 0)?.text ?: return@mapNotNull null
            val defaultValue = parameter.getRuleContext(mcfppParser.ValueContext::class.java, 0)?.text?.trim()?.takeIf { it.isNotEmpty() }
            MCFPPFunctionParameter(
                name = name,
                typeName = typeName,
                hasDefault = parameter.getToken(mcfppParser.ASSIGNMENT, 0) != null,
                defaultValue = defaultValue,
                isReadOnly = readOnly
            )
        }

        private fun recordEnumMember(ctx: mcfppParser.EnumMemberContext) {
            val owner = currentTypeName() ?: return
            val nameToken = ctx.getToken(mcfppParser.Identifier, 0) ?: return
            markDeclaration(nameToken)
            val name = nameToken.text
            val binding = ctx.nbtValue()?.text ?: "\"$name\""
            val ordinal = enumBindings.size
            val parsed = try { top.mcfpp.backend.NbtEncoding.parse(binding) } catch (_: Exception) { null }
            val previous = parsed?.let { tag -> enumBindings.firstOrNull { top.mcfpp.model.compound.EnumBindings.equivalent(it.second, tag) } }
            val problem = when {
                parsed == null -> "Invalid NBT binding for $owner.$name"
                enumBindings.any { it.first == name } -> "Duplicate enum member '$owner.$name'"
                previous != null -> "Enum members '$owner.${previous.first}' and '$owner.$name' have equivalent NBT bindings"
                else -> null
            }
            if (problem != null) diagnostics += Diagnostic(tokenRange(nameToken.symbol), problem, DiagnosticSeverity.Error, "mcfpp")
            else if (parsed != null) enumBindings += name to parsed
            val symbol = recordSymbol(
                declarationToken = nameToken.symbol,
                name = nameToken.text,
                kind = SymbolKind.EnumMember,
                detail = "$owner.$name = $binding (ordinal $ordinal)",
                isReadOnly = true,
                isStatic = true,
                typeName = owner,
                receiverType = null,
                topLevel = false,
                containerName = owner
            )
            typeMembers.getOrPut(owner) { mutableListOf() }.add(symbol)
        }

        private fun currentTypeName(): String? = typeStack.lastOrNull()?.name

        private fun currentNamespaceName(): String? = topLevelSymbols.lastOrNull { it.kind == SymbolKind.Namespace }?.name

        private fun currentFunctionName(): String? = functionStack.lastOrNull()?.name

        private fun currentTypeScopeId(): String? = typeStack.lastOrNull()?.id

        private fun currentFunctionScopeId(): String? = functionStack.lastOrNull()?.id

        private fun currentBlockScopeId(): String? = blockStack.lastOrNull()?.id

        private fun markDeclaration(token: TerminalNode) {
            declarationTokenIndexes.add(token.symbol.tokenIndex)
        }

        private fun markDeclaration(token: Token) {
            declarationTokenIndexes.add(token.tokenIndex)
        }

        private fun functionValue(type: mcfppParser.TypeContext?, expression: mcfppParser.ExpressionContext?): MCFPPFunctionValue? {
            type?.typeBody()?.functionType()?.let { signature ->
                return MCFPPFunctionValue(signature.type().map { MCFPPFunctionParameter(null, it.text) },
                    signature.functionReturnType().text, signature.contextTypeParameters()?.type()?.map { it.text }.orEmpty())
            }
            // Braces also form NBT/template literals. An explicit value type wins;
            // an empty untyped literal has no evidence that it is a callback.
            if (type != null || expression == null) return null
            var node: org.antlr.v4.runtime.tree.ParseTree = expression
            while (node !is mcfppParser.InlineLambdaContext && node.childCount == 1) node = node.getChild(0)
            val lambda = node as? mcfppParser.InlineLambdaContext ?: return null
            if (lambda.lambdaParameterList() == null && lambda.lambdaBody()?.text.isNullOrBlank()) return null
            val parameters = lambda.lambdaParameterList()?.lambdaParameter().orEmpty().map {
                MCFPPFunctionParameter(it.Identifier().text, it.type()?.text ?: "?")
            }
            fun hasReturn(node: org.antlr.v4.runtime.tree.ParseTree): Boolean =
                node is mcfppParser.ReturnStatementContext || (0 until node.childCount).any { hasReturn(node.getChild(it)) }
            val tail = lambda.lambdaBody()?.lambdaTailExpression()?.expression()
            val result = tail?.text
            val resultType = when {
                result == null -> if (lambda.lambdaBody()?.let(::hasReturn) == true) null else "void"
                result.matches(Regex("[-+]?\\d+")) -> "int"
                result in setOf("true", "false") -> "bool"
                result.startsWith('"') || result.startsWith('\'') -> stringLiteralType(tail.sourceText())
                else -> parameters.firstOrNull { it.name == result }?.typeName
            }
            return MCFPPFunctionValue(parameters, resultType)
        }

        private fun recordSymbol(
            declarationToken: Token,
            name: String,
            kind: SymbolKind,
            detail: String?,
            typeName: String?,
            receiverType: String?,
            topLevel: Boolean,
            containerName: String?,
            superTypes: List<String> = emptyList(),
            enclosingTypeScopeId: String? = currentTypeScopeId(),
            enclosingFunctionScopeId: String? = currentBlockScopeId() ?: currentFunctionScopeId(),
            fullRange: Range? = null,
            parameters: List<MCFPPFunctionParameter> = emptyList(),
            isParameter: Boolean = false,
            isReadOnly: Boolean = false,
            isStatic: Boolean = false,
            accessModifier: MCFPPAccessModifier = MCFPPAccessModifier.PUBLIC,
            isSynthetic: Boolean = false,
            initializer: String? = null,
            initializerStart: Position? = null,
            functionValue: MCFPPFunctionValue? = null,
            contextParameters: List<MCFPPFunctionParameter> = emptyList(),
            isContextProvider: Boolean = false,
            isEntityTemplate: Boolean = false,
            isEntityMember: Boolean = false,
            isDataTemplate: Boolean = false,
            isAbstract: Boolean = false,
            isNative: Boolean = false
        ): MCFPPSymbol {
            val symbol = MCFPPSymbol(
                uri = uri,
                name = name,
                kind = kind,
                range = tokenRange(declarationToken),
                fullRange = fullRange ?: tokenRange(declarationToken),
                detail = detail,
                containerName = containerName,
                typeName = typeName,
                receiverType = receiverType,
                topLevel = topLevel,
                enclosingTypeScopeId = enclosingTypeScopeId,
                enclosingFunctionScopeId = enclosingFunctionScopeId,
                superTypes = superTypes,
                documentation = MCFPPDocumentation.findDocumentationBefore(text, declarationToken.line - 1),
                namespaceName = currentNamespaceName(),
                parameters = parameters,
                isParameter = isParameter,
                isReadOnly = isReadOnly,
                isStatic = isStatic,
                accessModifier = accessModifier,
                isSynthetic = isSynthetic,
                initializer = initializer,
                initializerStart = initializerStart,
                functionValue = functionValue,
                contextParameters = contextParameters,
                isContextProvider = isContextProvider,
                isEntityTemplate = isEntityTemplate,
                isEntityMember = isEntityMember,
                isDataTemplate = isDataTemplate,
                isAbstract = isAbstract,
                isNative = isNative
            )
            symbols += symbol
            if (topLevel) {
                topLevelSymbols += symbol
            }
            return symbol
        }

        private fun accessModifier(ctx: ParserRuleContext): MCFPPAccessModifier {
            var current: org.antlr.v4.runtime.tree.ParseTree? = ctx
            while (current != null) {
                if (current is mcfppParser.TemplateMemberDeclarationContext) {
                    return when (current.accessModifier()?.text) {
                        "private" -> MCFPPAccessModifier.PRIVATE
                        "protected" -> MCFPPAccessModifier.PROTECTED
                        else -> MCFPPAccessModifier.PUBLIC
                    }
                }
                current = current.parent
            }
            return MCFPPAccessModifier.PUBLIC
        }

        private fun collectSuperTypes(ctx: mcfppParser.CompoundDeclarationContext?): List<String> {
            return ctx?.extendName()
                ?.mapNotNull { normalizeDeclaredTypeName(it.className()?.text ?: it.text) }
                ?.distinct()
                .orEmpty()
        }

        private fun normalizeDeclaredTypeName(typeName: String?): String? {
            if (typeName.isNullOrBlank()) {
                return null
            }
            return typeName.substringBefore('<').substringBefore('?').trim()
                .takeIf { it.isNotEmpty() }
        }

        private fun createScopeFrame(ctx: ParserRuleContext, kind: MCFPPScopeKind, name: String): ScopeFrame {
            val parentScopeId = currentBlockScopeId() ?: currentFunctionScopeId() ?: currentTypeScopeId()
            val scopeId = createScopeId(kind, name, ctx.start.startIndex)
            return ScopeFrame(ctx = ctx, id = scopeId, name = name, kind = kind, parentScopeId = parentScopeId)
        }

        private fun createScopeId(kind: MCFPPScopeKind, name: String, startIndex: Int): String {
            return "${kind.name.lowercase()}:$name:$startIndex"
        }

        private fun recordBlockScope(ctx: mcfppParser.CurlBlockContext) {
            val blockName = currentBlockOwnerName()
            blockStack.addLast(createScopeFrame(ctx, MCFPPScopeKind.BLOCK, blockName))
        }

        private fun recordLambdaScope(ctx: mcfppParser.InlineLambdaContext) {
            val entry = ContextBuiltins.entryBefore(text, ctx.start.startIndex)
            val ambient = blockStack.lastOrNull { it.name.startsWith("context:") }?.name?.removePrefix("context:")
            val name = if (entry == null) "lambda" else "context:${ContextBuiltins.callbackType(entry, ambient)}"
            blockStack.addLast(createScopeFrame(ctx, MCFPPScopeKind.BLOCK, name))
            if (entry != null) return // Context callbacks require an explicit parameter.
            if (ctx.lambdaParameterList() != null) return
            val body = ctx.lambdaBody() ?: return
            val uses = Regex("\\bit\\b").findAll(body.text).toList()
            if (uses.isEmpty()) return
            val range = Range(lineMap.position(ctx.start.startIndex), lineMap.position(ctx.start.stopIndex + 1))
            symbols += MCFPPSymbol(uri, "it", SymbolKind.Variable, range,
                containerName = currentFunctionName(), enclosingFunctionScopeId = currentBlockScopeId(),
                isParameter = true, isSynthetic = true, typeName = referenceReadType(ctx), detail = "implicit lambda parameter")
        }

        private fun referenceReadType(ctx: mcfppParser.InlineLambdaContext): String? {
            val receiver = top.mcfpp.language.MemberExpressions.tryReadReceiver(text, ctx.start.startIndex) ?: return null
            val scopes = (blockStack.map { it.id } + functionStack.map { it.id }).toSet()
            val type = MCFPPDocumentIndex.referenceFactoryType(receiver) ?: symbols.lastOrNull {
                it.name == receiver && (it.topLevel || it.enclosingFunctionScopeId in scopes)
            }?.typeName ?: return null
            return if (type.startsWith("NBTRef<") && type.endsWith('>')) type.substring(7, type.length - 1)
                else if (type == "ScoreRef") "int" else null
        }

        private fun contextParameters(ctx: mcfppParser.ContextParametersContext?): List<MCFPPFunctionParameter> =
            ctx?.contextParameter()?.map { MCFPPFunctionParameter(it.Identifier().text, it.type().text) }.orEmpty()

        private fun inferContextInitializer(expression: String?): String? {
            if (expression == null) return null
            if (expression.startsWith('@')) return "entity"
            if (!expression.matches(Regex("[A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*"))) return null
            val parts = expression.split('.')
            val scopes = (blockStack.map { it.id } + functionStack.map { it.id }).toSet()
            var type = symbols.lastOrNull { it.name == parts.first() && it.enclosingFunctionScopeId in scopes }?.typeName ?: return null
            for (member in parts.drop(1)) type = top.mcfpp.language.EntityBuiltins.member(type, member)?.type() ?: return null
            return type
        }

        /** Infers `Type(...)`, `factory(...)` and `template.summon(...)` initializers. */
        private fun inferCallInitializer(expression: String?): String? {
            val text = expression?.trim() ?: return null
            top.mcfpp.language.MemberExpressions.calledMemberReceiver(text, "merge")?.let { receiver ->
                val owner = if (receiver.startsWith('{') && receiver.endsWith('}')) "nbt"
                    else variables[receiver] ?: inferCallInitializer(receiver)
                top.mcfpp.language.NbtBuiltins.member(owner, "merge")?.let { return it.type() }
            }
            val direct = Regex("^(?:[A-Za-z_][\\w.]*:)?([A-Za-z_]\\w*)\\s*(?:<[^<>]*>)?\\s*\\(").find(text)
            if (direct != null && top.mcfpp.language.MemberExpressions.endsAtCall(text, direct.range.last)) {
                val name = direct.groupValues[1]
                symbols.lastOrNull { it.name == name && it.topLevel && it.kind == SymbolKind.Class }?.let { return it.name }
                symbols.lastOrNull {
                    it.name == name && it.typeName != null &&
                        it.kind in setOf(SymbolKind.Function, SymbolKind.Method, SymbolKind.Constructor)
                }?.let { return it.typeName }
            }
            val member = Regex("^([A-Za-z_]\\w*)\\s*\\.\\s*([A-Za-z_]\\w*)\\s*(?:<[^<>]*>)?\\s*\\(").find(text) ?: return null
            val receiver = member.groupValues[1]
            val name = member.groupValues[2]
            if (!top.mcfpp.language.MemberExpressions.endsAtCall(text, member.range.last)) return null
            val owner = variables[receiver]?.substringBefore('<')?.trim()
                ?: symbols.lastOrNull { it.name == receiver && it.typeName != null }?.typeName?.substringBefore('<')
                ?: return null
            return typeMembers[owner].orEmpty().firstOrNull { it.name == name }?.typeName
                ?: top.mcfpp.language.EntityBuiltins.member(owner, name)?.type()
                ?: top.mcfpp.language.NbtBuiltins.member(owner, name)?.type()
        }

        private fun recordForeachVariable(ctx: mcfppParser.ForeachStatementContext) {
            val nameToken = ctx.getToken(mcfppParser.Identifier, 0) ?: return
            markDeclaration(nameToken)
            variables[nameToken.text] = null
            val loopScopeId = ctx.block()
                ?.curlBlock()
                ?.let { createScopeId(MCFPPScopeKind.BLOCK, currentBlockOwnerName(), it.start.startIndex) }
                ?: currentBlockScopeId()
                ?: currentFunctionScopeId()
            recordSymbol(
                declarationToken = nameToken.symbol,
                name = nameToken.text,
                kind = SymbolKind.Variable,
                detail = null,
                typeName = null,
                receiverType = null,
                topLevel = false,
                containerName = currentFunctionName() ?: currentTypeName(),
                enclosingFunctionScopeId = loopScopeId
            )
        }

        private fun currentBlockOwnerName(): String {
            return currentFunctionName() ?: currentTypeName() ?: "block"
        }

        private fun recordScopeRegion(frame: ScopeFrame) {
            scopeRegions += MCFPPScopeRegion(
                id = frame.id,
                kind = frame.kind,
                name = frame.name,
                range = contextRange(frame.ctx),
                parentScopeId = frame.parentScopeId
            )
        }

        private fun contextRange(ctx: ParserRuleContext): Range {
            val stopToken = ctx.stop ?: ctx.start
            return Range(
                lineMap.position(ctx.start.startIndex),
                lineMap.position(stopToken.stopIndex + 1)
            )
        }

        private fun tokenRange(token: Token): Range {
            val start = lineMap.position(token.startIndex)
            val end = lineMap.position(token.stopIndex + 1)
            return Range(start, end)
        }

    }
}
