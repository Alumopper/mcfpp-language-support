package com.simplelanguage.lsp

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
    val normalArgumentRanges: List<Range> = emptyList()
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
    val diagnostics: List<Diagnostic>
)

object MCFPPParserSemanticExtractor {
    fun extract(uri: String, text: String): MCFPPParserSemanticData {
        val normalizedText = text.replace("\r\n", "\n")
        val diagnostics = mutableListOf<Diagnostic>()
        val errorListener = object : BaseErrorListener() {
            override fun syntaxError(
                recognizer: Recognizer<*, *>?,
                offendingSymbol: Any?,
                line: Int,
                charPositionInLine: Int,
                msg: String,
                e: RecognitionException?
            ) {
                diagnostics += Diagnostic(
                    diagnosticRange(normalizedText, line, charPositionInLine, offendingSymbol),
                    msg,
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
        val collector = SemanticCollector(uri, normalizedText)
        ParseTreeWalker.DEFAULT.walk(collector, parser.compilationUnit())
        return collector.build(
            diagnostics.distinctBy { listOf(it.range.start.line, it.range.start.character, it.message) }
        )
    }

    private fun diagnosticRange(text: String, line: Int, character: Int, offendingSymbol: Any?): Range {
        val token = offendingSymbol as? Token
        if (token != null && token.startIndex >= 0 && token.stopIndex >= token.startIndex) {
            return Range(
                offsetToPosition(text, token.startIndex),
                offsetToPosition(text, token.stopIndex + 1)
            )
        }
        val start = Position((line - 1).coerceAtLeast(0), character.coerceAtLeast(0))
        return Range(start, Position(start.line, start.character + 1))
    }

    private fun offsetToPosition(text: String, offset: Int): Position {
        val boundedOffset = offset.coerceIn(0, text.length)
        var line = 0
        var character = 0
        repeat(boundedOffset) { index ->
            if (text[index] == '\n') {
                line += 1
                character = 0
            } else {
                character += 1
            }
        }
        return Position(line, character)
    }

    private class SemanticCollector(
        private val uri: String,
        private val text: String
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
                is mcfppParser.InterfaceDeclarationContext -> recordInterface(ctx)
                is mcfppParser.EnumDeclarationContext -> recordEnum(ctx)
                is mcfppParser.FunctionDeclarationContext -> recordFunction(ctx.functionDeclarationPart(), null, ctx)
                is mcfppParser.InlineFunctionDeclarationContext -> recordFunction(ctx.functionDeclarationPart(), null, ctx)
                is mcfppParser.CompileTimeFuncDeclarationContext -> recordFunction(ctx.functionDeclarationPart(), null, ctx)
                is mcfppParser.NativeFuncDeclarationContext -> recordFunction(ctx.functionDeclarationPart(), null, ctx)
                is mcfppParser.TemplateFunctionDeclarationContext -> recordFunction(ctx.functionDeclarationPart(), null, ctx)
                is mcfppParser.ExtensionFunctionDeclarationContext -> recordExtensionFunction(ctx)
                is mcfppParser.TemplateConstructorDeclarationContext -> recordConstructor(ctx)
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
                is mcfppParser.ParameterContext -> recordParameter(ctx)
                is mcfppParser.EnumMemberContext -> recordEnumMember(ctx)
                is mcfppParser.CurlBlockContext -> recordBlockScope(ctx)
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
            if (node.parent is mcfppParser.JavaReferContext) {
                return
            }
            if (node.parent is mcfppParser.PropertyOperatorExpressionContext ||
                node.parent is mcfppParser.CommonBinaryOperatorExpressionContext ||
                node.parent is mcfppParser.ClassNameContext
            ) {
                return
            }
            if (token.tokenIndex in declarationTokenIndexes) {
                return
            }
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
                namespaceId?.getTokens(mcfppParser.Identifier).orEmpty()
                    .dropLast(1)
                    .joinToString(".") { it.text }
                    .takeIf { it.isNotEmpty() }
            } else {
                null
            }
            referencesByName.getOrPut(token.text) { mutableListOf() }.add(range)
            referenceOccurrencesByName.getOrPut(token.text) { mutableListOf() }.add(
                MCFPPReferenceOccurrence(
                    name = token.text,
                    range = range,
                    enclosingTypeScopeId = currentTypeScopeId(),
                    enclosingFunctionScopeId = currentFunctionScopeId(),
                    isCall = isCall,
                    namespaceQualifier = namespaceQualifier,
                    readOnlyArguments = functionCall?.arguments()?.readOnlyArgs()?.expressionList()?.expression()?.map { it.text }.orEmpty(),
                    normalArguments = functionCall?.arguments()?.normalArgs()?.expressionList()?.expression()?.map { it.text }.orEmpty(),
                    readOnlyArgumentRanges = functionCall?.arguments()?.readOnlyArgs()?.expressionList()?.expression()?.map(::contextRange).orEmpty(),
                    normalArgumentRanges = functionCall?.arguments()?.normalArgs()?.expressionList()?.expression()?.map(::contextRange).orEmpty()
                )
            )
        }

        fun build(diagnostics: List<Diagnostic>): MCFPPParserSemanticData {
            val dedupedSymbols = symbols.distinctBy { listOf(it.name, it.kind.name, it.range.start.line, it.range.start.character, it.containerName, it.receiverType).joinToString("|") }
            val mergedTypeMembers = typeMembers.mapValues { (_, members) ->
                members.distinctBy { listOf(it.name, it.kind.name, it.range.start.line, it.range.start.character, it.containerName).joinToString("|") }
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
            val identifiers = ctx.getTokens(mcfppParser.Identifier)
            if (identifiers.isEmpty()) {
                return
            }
            identifiers.forEach(::markDeclaration)
            recordSymbol(
                declarationToken = identifiers.last().symbol,
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
            val aliasTarget = ctx.type()?.text?.trim()
            val superTypes = collectSuperTypes(ctx.compoundDeclaration())
            val readOnlyParameters = extractParameters(
                declarationName.readOnlyParams()?.parameterList()?.parameter().orEmpty(),
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
                parameters = readOnlyParameters
            )
            typeStack.addLast(createScopeFrame(ctx, MCFPPScopeKind.TYPE, symbol.name))
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

        private fun recordDefaultTemplateConstructor(ctx: mcfppParser.TemplateDeclarationContext) {
            if (ctx.ABSTRACT() != null) {
                return
            }
            val owner = currentTypeName() ?: return
            if (typeMembers[owner].orEmpty().any { it.kind == SymbolKind.Constructor }) {
                return
            }
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
        }

        private fun recordObjectTemplate(ctx: mcfppParser.ObjectTemplateDeclarationContext) {
            val declarationName = ctx.compoundDeclaration()?.declarationName() ?: return
            val nameToken = declarationName.classWithoutNamespace()?.Identifier() ?: return
            markDeclaration(nameToken)
            val superTypes = collectSuperTypes(ctx.compoundDeclaration())
            val readOnlyParameters = extractParameters(
                declarationName.readOnlyParams()?.parameterList()?.parameter().orEmpty(),
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

        private fun recordInterface(ctx: mcfppParser.InterfaceDeclarationContext) {
            val declarationName = ctx.compoundDeclaration()?.declarationName() ?: return
            val nameToken = declarationName.classWithoutNamespace()?.Identifier() ?: return
            markDeclaration(nameToken)
            val superTypes = collectSuperTypes(ctx.compoundDeclaration())
            val readOnlyParameters = extractParameters(
                declarationName.readOnlyParams()?.parameterList()?.parameter().orEmpty(),
                true
            )
            typeReadOnlyParameters[nameToken.text] = readOnlyParameters
            val symbol = recordSymbol(
                declarationToken = nameToken.symbol,
                name = nameToken.text,
                kind = SymbolKind.Interface,
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

        private fun recordFunction(part: mcfppParser.FunctionDeclarationPartContext?, receiverType: String?, ownerCtx: ParserRuleContext) {
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
                accessModifier = accessModifier(ownerCtx)
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
                accessModifier = accessModifier(ctx)
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
                accessModifier = accessModifier(ctx)
            )
            if (currentType != null && currentFunction == null) {
                typeMembers.getOrPut(currentType) { mutableListOf() }.add(symbol)
            }
        }

        private fun recordParameter(ctx: mcfppParser.ParameterContext) {
            val nameToken = ctx.getTokens(mcfppParser.Identifier).firstOrNull() ?: return
            markDeclaration(nameToken)
            val typeName = ctx.getRuleContext(mcfppParser.TypeContext::class.java, 0)?.text?.trim()
            val isReadOnly = ctx.parent?.parent is mcfppParser.ReadOnlyParamsContext
            val isTypeParameter = isReadOnly && typeName.equals("Type", ignoreCase = true)
            variables[nameToken.text] = typeName
            recordSymbol(
                declarationToken = nameToken.symbol,
                name = nameToken.text,
                kind = if (isTypeParameter) SymbolKind.TypeParameter else SymbolKind.Variable,
                detail = typeName,
                typeName = typeName,
                receiverType = null,
                topLevel = false,
                containerName = currentFunctionName() ?: currentTypeName(),
                isParameter = true,
                isReadOnly = isReadOnly,
                isStatic = ctx.getToken(mcfppParser.STATIC, 0) != null
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
                addAll(extractParameters(ctx.readOnlyParams()?.parameterList()?.parameter().orEmpty(), true))
                addAll(extractParameters(ctx.normalParams()?.parameterList()?.parameter().orEmpty(), false))
            }
        }

        private fun extractParameters(
            contexts: List<mcfppParser.ParameterContext>,
            readOnly: Boolean
        ): List<MCFPPFunctionParameter> = contexts.mapNotNull { parameter ->
            val typeName = parameter.type()?.text?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val name = parameter.getTokens(mcfppParser.Identifier).firstOrNull()?.text
            val defaultValue = parameter.value()?.text?.trim()?.takeIf { it.isNotEmpty() }
            MCFPPFunctionParameter(
                name = name,
                typeName = typeName,
                isStatic = parameter.getToken(mcfppParser.STATIC, 0) != null,
                hasDefault = parameter.getToken(mcfppParser.ASSIGNMENT, 0) != null,
                defaultValue = defaultValue,
                isReadOnly = readOnly
            )
        }

        private fun recordEnumMember(ctx: mcfppParser.EnumMemberContext) {
            val owner = currentTypeName() ?: return
            val nameToken = ctx.getToken(mcfppParser.Identifier, 0) ?: return
            markDeclaration(nameToken)
            val symbol = recordSymbol(
                declarationToken = nameToken.symbol,
                name = nameToken.text,
                kind = SymbolKind.EnumMember,
                detail = null,
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
            isSynthetic: Boolean = false
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
                isSynthetic = isSynthetic
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
                offsetToPosition(text, ctx.start.startIndex),
                offsetToPosition(text, stopToken.stopIndex + 1)
            )
        }

        private fun tokenRange(token: Token): Range {
            val start = offsetToPosition(text, token.startIndex)
            val end = offsetToPosition(text, token.stopIndex + 1)
            return Range(start, end)
        }

        private fun offsetToPosition(text: String, offset: Int): Position {
            val boundedOffset = offset.coerceIn(0, text.length)
            var line = 0
            var character = 0
            var index = 0
            while (index < boundedOffset) {
                if (text[index] == '\n') {
                    line += 1
                    character = 0
                } else {
                    character += 1
                }
                index += 1
            }
            return Position(line, character)
        }
    }
}
