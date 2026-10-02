package com.simplelanguage.lsp

import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ParserRuleContext
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind

class MCFPPTypeChecker(
    private val document: MCFPPDocumentAnalysis,
    private val visibleGlobals: List<MCFPPSymbol>,
    private val resolveLocalSymbol: (String, Position) -> MCFPPSymbol?,
    private val resolveGlobalSymbol: (String, Position) -> MCFPPSymbol?,
    private val resolveCallSymbol: (MCFPPReferenceOccurrence) -> MCFPPSymbol?,
    private val resolveMemberSymbol: (String, String, Position) -> MCFPPSymbol?,
    private val resolveImportedType: (String) -> String = { it }
) {
    private val inferredSymbolTypes = linkedMapOf<String, String>()

    fun collectDiagnostics(): List<Diagnostic> {
        val lexer = mcfppLexer(CharStreams.fromString(document.analysisText))
        lexer.removeErrorListeners()
        lexer.addErrorListener(BaseErrorListener())
        val parser = mcfppParser(CommonTokenStream(lexer))
        parser.removeErrorListeners()
        parser.addErrorListener(BaseErrorListener())
        val diagnostics = mutableListOf<Diagnostic>()
        val collector = DiagnosticCollector(diagnostics)
        collector.visitCompilationUnit(parser.compilationUnit())
        return diagnostics.distinctBy { listOf(it.range.start.line, it.range.start.character, it.message).joinToString("|") }
    }

    private inner class DiagnosticCollector(
        private val diagnostics: MutableList<Diagnostic>
    ) : mcfppParserBaseVisitor<Unit>() {
        private val functionReturnTypes = ArrayDeque<String>()

        override fun visitFunctionDeclaration(ctx: mcfppParser.FunctionDeclarationContext) {
            val declaration = ctx.functionDeclarationPart()
            withFunctionReturnContract(
                functionName = declaration?.Identifier()?.text,
                typeName = declaration?.functionReturnType()?.text,
                body = ctx.curlBlock(),
                diagnosticRange = declaration?.Identifier()?.symbol?.let(::tokenRange) ?: contextRange(ctx)
            ) {
                super.visitFunctionDeclaration(ctx)
            }
        }

        override fun visitInlineFunctionDeclaration(ctx: mcfppParser.InlineFunctionDeclarationContext) {
            val declaration = ctx.functionDeclarationPart()
            withFunctionReturnContract(
                functionName = declaration?.Identifier()?.text,
                typeName = declaration?.functionReturnType()?.text,
                body = ctx.curlBlock(),
                diagnosticRange = declaration?.Identifier()?.symbol?.let(::tokenRange) ?: contextRange(ctx)
            ) {
                super.visitInlineFunctionDeclaration(ctx)
            }
        }

        override fun visitCompileTimeFuncDeclaration(ctx: mcfppParser.CompileTimeFuncDeclarationContext) {
            val declaration = ctx.functionDeclarationPart()
            withFunctionReturnContract(
                functionName = declaration?.Identifier()?.text,
                typeName = declaration?.functionReturnType()?.text,
                body = ctx.curlBlock(),
                diagnosticRange = declaration?.Identifier()?.symbol?.let(::tokenRange) ?: contextRange(ctx)
            ) {
                super.visitCompileTimeFuncDeclaration(ctx)
            }
        }

        override fun visitTemplateFunctionDeclaration(ctx: mcfppParser.TemplateFunctionDeclarationContext) {
            val declaration = ctx.functionDeclarationPart()
            withFunctionReturnContract(
                functionName = declaration?.Identifier()?.text,
                typeName = declaration?.functionReturnType()?.text,
                body = ctx.curlBlock(),
                diagnosticRange = declaration?.Identifier()?.symbol?.let(::tokenRange) ?: contextRange(ctx)
            ) {
                super.visitTemplateFunctionDeclaration(ctx)
            }
        }

        override fun visitExtensionFunctionDeclaration(ctx: mcfppParser.ExtensionFunctionDeclarationContext) {
            val identifier = ctx.getTokens(mcfppParser.Identifier).lastOrNull()
            withFunctionReturnContract(
                functionName = identifier?.text,
                typeName = ctx.functionReturnType()?.text,
                body = ctx.curlBlock(),
                diagnosticRange = identifier?.symbol?.let(::tokenRange) ?: contextRange(ctx)
            ) {
                super.visitExtensionFunctionDeclaration(ctx)
            }
        }

        override fun visitOperationOverrideDeclaration(ctx: mcfppParser.OperationOverrideDeclarationContext) {
            val operator = ctx.supportOperator()
            withFunctionReturnContract(
                functionName = "operator ${operator?.text.orEmpty()}",
                typeName = ctx.functionReturnType()?.text,
                body = ctx.curlBlock(),
                diagnosticRange = operator?.let(::contextRange) ?: contextRange(ctx)
            ) {
                super.visitOperationOverrideDeclaration(ctx)
            }
        }

        override fun visitNativeOperationOverrideDeclaration(ctx: mcfppParser.NativeOperationOverrideDeclarationContext) {
            val operator = ctx.supportOperator()
            withFunctionReturnContract(
                functionName = "operator ${operator?.text.orEmpty()}",
                typeName = ctx.functionReturnType()?.text,
                body = null,
                diagnosticRange = operator?.let(::contextRange) ?: contextRange(ctx)
            ) {
                super.visitNativeOperationOverrideDeclaration(ctx)
            }
        }

        override fun visitTemplateConstructorDeclaration(ctx: mcfppParser.TemplateConstructorDeclarationContext) {
            withFunctionReturnType("void") {
                super.visitTemplateConstructorDeclaration(ctx)
            }
        }

        override fun visitFieldDeclaration(ctx: mcfppParser.FieldDeclarationContext) {
            val expectedType = ctx.type()?.text?.trim()
            val initializer = ctx.expression()
            val variableName = ctx.Identifier()?.text
            val declarationPosition = ctx.Identifier()?.symbol?.let(::tokenPosition)
            val declarationSymbol = if (variableName != null && declarationPosition != null) {
                resolveLocalSymbol(variableName, declarationPosition) ?: resolveGlobalSymbol(variableName, declarationPosition)
            } else {
                null
            }
            if (expectedType.isNullOrBlank() && initializer != null && declarationSymbol != null) {
                inferExpressionType(initializer)?.let { inferredType ->
                    inferredSymbolTypes[symbolKey(declarationSymbol)] = inferredType
                }
            }
            if (!expectedType.isNullOrBlank() && initializer != null) {
                val actualType = inferExpressionType(initializer)
                if (!isAssignable(expectedType, actualType)) {
                    diagnostics += typeMismatchDiagnostic(
                        ctx.Identifier()?.symbol?.let(::tokenRange) ?: contextRange(ctx),
                        expectedType,
                        actualType,
                        ctx.Identifier()?.text ?: "value"
                    )
                }
            }
            super.visitFieldDeclaration(ctx)
        }

        override fun visitStatementExpression(ctx: mcfppParser.StatementExpressionContext) {
            val target = ctx.varWithSelector()
            val value = ctx.expression()
            if (target != null && value != null && ctx.assignmentOperator() != null) {
                val expectedType = inferVarWithSelectorType(target)
                val actualType = inferExpressionType(value)
                if (!isAssignable(expectedType, actualType)) {
                    diagnostics += typeMismatchDiagnostic(
                        contextRange(target),
                        expectedType,
                        actualType,
                        target.text
                    )
                }
            }
            super.visitStatementExpression(ctx)
        }

        override fun visitReturnStatement(ctx: mcfppParser.ReturnStatementContext) {
            val expectedType = functionReturnTypes.lastOrNull()
            val expression = ctx.expression()
            if (expectedType != null) {
                val diagnostic = when {
                    expectedType == "void" && expression != null -> Diagnostic(
                        contextRange(expression),
                        "Void function cannot return a value",
                        DiagnosticSeverity.Error,
                        "mcfpp"
                    )
                    expectedType != "void" && expression == null -> Diagnostic(
                        contextRange(ctx),
                        "Return value required: expected `$expectedType`",
                        DiagnosticSeverity.Error,
                        "mcfpp"
                    )
                    expression != null -> {
                        val actualType = inferExpressionType(expression)
                        if (!isAssignable(expectedType, actualType)) {
                            Diagnostic(
                                contextRange(expression),
                                "Return type mismatch: expected `$expectedType`, found `${actualType ?: "unknown"}`",
                                DiagnosticSeverity.Error,
                                "mcfpp"
                            )
                        } else {
                            null
                        }
                    }
                    else -> null
                }
                diagnostic?.let(diagnostics::add)
            }
            super.visitReturnStatement(ctx)
        }

        override fun visitFunctionCall(ctx: mcfppParser.FunctionCallContext) {
            val occurrence = functionCallOccurrence(ctx)
            val target = occurrence?.let(resolveCallSymbol)
            if (target != null) {
                validateFunctionCall(ctx, target)
            }
            super.visitFunctionCall(ctx)
        }

        override fun visitIfStatement(ctx: mcfppParser.IfStatementContext) {
            checkCondition(ctx.bucketExpression())
            super.visitIfStatement(ctx)
        }

        override fun visitElseIfStatement(ctx: mcfppParser.ElseIfStatementContext) {
            checkCondition(ctx.bucketExpression())
            super.visitElseIfStatement(ctx)
        }

        override fun visitWhileStatement(ctx: mcfppParser.WhileStatementContext) {
            checkCondition(ctx.bucketExpression())
            super.visitWhileStatement(ctx)
        }

        override fun visitDoWhileStatement(ctx: mcfppParser.DoWhileStatementContext) {
            checkCondition(ctx.bucketExpression())
            super.visitDoWhileStatement(ctx)
        }

        private fun checkCondition(ctx: mcfppParser.BucketExpressionContext?) {
            val expression = ctx?.expression() ?: return
            val actualType = inferExpressionType(expression)
            if (!isAssignable("bool", actualType)) {
                diagnostics += Diagnostic(
                    contextRange(expression),
                    "Condition must be `bool`, found `${actualType ?: "unknown"}`",
                    DiagnosticSeverity.Error,
                    "mcfpp"
                )
            }
        }

        private fun withFunctionReturnContract(
            functionName: String?,
            typeName: String?,
            body: mcfppParser.CurlBlockContext?,
            diagnosticRange: Range,
            visitBody: () -> Unit
        ) {
            val returnType = typeName?.trim()?.takeIf { it.isNotEmpty() } ?: "void"
            withFunctionReturnType(returnType, visitBody)
            if (returnType != "void" && body != null && !guaranteesReturn(body)) {
                diagnostics += Diagnostic(
                    diagnosticRange,
                    "Function `${functionName ?: "anonymous"}` must return a value on every path",
                    DiagnosticSeverity.Error,
                    "mcfpp"
                )
            }
        }

        private fun withFunctionReturnType(typeName: String, block: () -> Unit) {
            functionReturnTypes.addLast(typeName)
            try {
                block()
            } finally {
                functionReturnTypes.removeLast()
            }
        }

        private fun guaranteesReturn(block: mcfppParser.CurlBlockContext): Boolean {
            return block.statement().any(::guaranteesReturn)
        }

        private fun guaranteesReturn(block: mcfppParser.BlockContext): Boolean {
            return block.curlBlock()?.let(::guaranteesReturn)
                ?: block.statement()?.let(::guaranteesReturn)
                ?: false
        }

        private fun guaranteesReturn(statement: mcfppParser.StatementContext): Boolean {
            if (statement.returnStatement() != null) {
                return true
            }
            statement.doWhileStatement()?.let { loop ->
                if (guaranteesReturn(loop.block())) {
                    return true
                }
            }
            val conditional = statement.ifStatement() ?: return false
            val elseBlock = conditional.elseStatement()?.block() ?: return false
            return guaranteesReturn(conditional.block()) &&
                conditional.elseIfStatement().all { guaranteesReturn(it.block()) } &&
                guaranteesReturn(elseBlock)
        }

        private fun validateFunctionCall(ctx: mcfppParser.FunctionCallContext, target: MCFPPSymbol) {
            val readOnlyArguments = ctx.arguments()?.readOnlyArgs()?.expressionList()?.expression().orEmpty()
            val typeSubstitutions = typeParameterSubstitutions(target, readOnlyArguments.map { it.text })
            validateArgumentGroup(
                callRange = contextRange(ctx),
                functionName = target.name,
                groupLabel = "Read-only argument",
                parameters = target.parameters.filter { it.isReadOnly },
                arguments = readOnlyArguments,
                typeSubstitutions = emptyMap()
            )
            validateArgumentGroup(
                callRange = contextRange(ctx),
                functionName = target.name,
                groupLabel = "Argument",
                parameters = target.parameters.filterNot { it.isReadOnly },
                arguments = ctx.arguments()?.normalArgs()?.expressionList()?.expression().orEmpty(),
                typeSubstitutions = typeSubstitutions
            )
        }

        private fun validateArgumentGroup(
            callRange: Range,
            functionName: String,
            groupLabel: String,
            parameters: List<MCFPPFunctionParameter>,
            arguments: List<mcfppParser.ExpressionContext>,
            typeSubstitutions: Map<String, String>
        ) {
            val requiredCount = parameters.count { !it.hasDefault }
            if (arguments.size !in requiredCount..parameters.size) {
                val expectedCount = if (requiredCount == parameters.size) {
                    parameters.size.toString()
                } else {
                    "$requiredCount..${parameters.size}"
                }
                diagnostics += Diagnostic(
                    callRange,
                    "$groupLabel count mismatch for `$functionName`: expected $expectedCount, found ${arguments.size}",
                    DiagnosticSeverity.Error,
                    "mcfpp"
                )
            }
            arguments.forEachIndexed { index, expression ->
                val parameter = parameters.getOrNull(index) ?: return@forEachIndexed
                val actualType = inferExpressionType(expression)
                val expectedType = substituteTypeParameters(parameter.typeName, typeSubstitutions)
                if (!isAssignable(expectedType, actualType)) {
                    diagnostics += Diagnostic(
                        contextRange(expression),
                        "$groupLabel type mismatch for `$functionName` at #${index + 1}: expected `$expectedType`, found `${actualType ?: "unknown"}`",
                        DiagnosticSeverity.Error,
                        "mcfpp"
                    )
                }
            }
        }
    }

    private fun inferExpressionType(ctx: mcfppParser.ExpressionContext?): String? {
        if (ctx == null) {
            return null
        }
        ctx.primary()?.let { return inferPrimaryType(it) }
        return inferCommonBinaryOperatorType(ctx.commonBinaryOperatorExpression())
    }

    private fun inferCommonBinaryOperatorType(ctx: mcfppParser.CommonBinaryOperatorExpressionContext?): String? {
        if (ctx == null) {
            return null
        }
        if (ctx.op.isNotEmpty()) {
            return "any"
        }
        return inferConditionalOrType(ctx.conditionalOrExpression(0))
    }

    private fun inferConditionalOrType(ctx: mcfppParser.ConditionalOrExpressionContext?): String? {
        if (ctx == null) {
            return null
        }
        if (ctx.op.isNotEmpty()) {
            return "bool"
        }
        return inferConditionalAndType(ctx.conditionalAndExpression(0))
    }

    private fun inferConditionalAndType(ctx: mcfppParser.ConditionalAndExpressionContext?): String? {
        if (ctx == null) {
            return null
        }
        if (ctx.op.isNotEmpty()) {
            return "bool"
        }
        return inferEqualityType(ctx.equalityExpression(0))
    }

    private fun inferEqualityType(ctx: mcfppParser.EqualityExpressionContext?): String? {
        if (ctx == null) {
            return null
        }
        if (ctx.op.isNotEmpty()) {
            return "bool"
        }
        return inferRelationalType(ctx.relationalExpression(0))
    }

    private fun inferRelationalType(ctx: mcfppParser.RelationalExpressionContext?): String? {
        if (ctx == null) {
            return null
        }
        if (ctx.op.isNotEmpty()) {
            return "bool"
        }
        return inferAdditiveType(ctx.additiveExpression(0))
    }

    private fun inferAdditiveType(ctx: mcfppParser.AdditiveExpressionContext?): String? {
        if (ctx == null) {
            return null
        }
        val operands = ctx.multiplicativeExpression().mapNotNull(::inferMultiplicativeType)
        if (operands.isEmpty()) {
            return null
        }
        if (ctx.op.any { it.text == "+" } && operands.any { normalizeBaseType(it) in setOf("string", "text") }) {
            return "text"
        }
        return widestNumericType(operands) ?: operands.first()
    }

    private fun inferMultiplicativeType(ctx: mcfppParser.MultiplicativeExpressionContext?): String? {
        if (ctx == null) {
            return null
        }
        val operands = ctx.castExpression().mapNotNull(::inferCastType)
        return widestNumericType(operands) ?: operands.firstOrNull()
    }

    private fun inferCastType(ctx: mcfppParser.CastExpressionContext?): String? {
        if (ctx == null) {
            return null
        }
        return ctx.type()?.text?.trim() ?: inferUnaryType(ctx.unaryExpression())
    }

    private fun inferUnaryType(ctx: mcfppParser.UnaryExpressionContext?): String? {
        if (ctx == null) {
            return null
        }
        if (ctx.EXCL() != null) {
            return "bool"
        }
        if (ctx.SUB() != null) {
            return inferUnaryType(ctx.unaryExpression())
        }
        return inferVarWithSelectorType(ctx.rightVarExpression()?.varWithSelector())
    }

    private fun inferVarWithSelectorType(ctx: mcfppParser.VarWithSelectorContext?): String? {
        if (ctx == null) {
            return null
        }
        var currentType = inferJvmAccessType(ctx.jvmAccessExpression())
        ctx.selector().forEach { selector ->
            currentType = inferMemberVarType(currentType, selector.`var`())
        }
        return currentType
    }

    private fun inferJvmAccessType(ctx: mcfppParser.JvmAccessExpressionContext?): String? {
        if (ctx == null) {
            return null
        }
        return inferPropertyOperatorType(ctx.propertyOperator())
    }

    private fun inferPropertyOperatorType(ctx: mcfppParser.PropertyOperatorContext?): String? {
        if (ctx == null) {
            return null
        }
        return inferPrimaryType(ctx.primary())
    }

    private fun inferPrimaryType(ctx: mcfppParser.PrimaryContext?): String? {
        if (ctx == null) {
            return null
        }
        ctx.range()?.let { return "int" }
        ctx.value()?.let { return inferValueType(it) }
        ctx.`var`()?.let { return inferVarType(it) }
        if (ctx.THIS() != null) {
            return document.innermostTypeScope(tokenPosition(ctx.start))?.name
        }
        if (ctx.SUPER() != null) {
            val currentType = document.innermostTypeScope(tokenPosition(ctx.start))?.name ?: return null
            return visibleGlobals.firstOrNull { it.name == currentType && it.topLevel }?.superTypes?.firstOrNull()
        }
        if (ctx.type() != null) {
            return "Type"
        }
        return null
    }

    private fun inferVarType(ctx: mcfppParser.VarContext?): String? {
        if (ctx == null) {
            return null
        }
        ctx.bucketExpression()?.expression()?.let { return inferExpressionType(it) }
        ctx.varWithSuffix()?.let { return inferVarWithSuffixType(it) }
        ctx.functionCall()?.let { return inferFunctionCallType(it) }
        return null
    }

    private fun inferVarWithSuffixType(ctx: mcfppParser.VarWithSuffixContext?): String? {
        if (ctx == null) {
            return null
        }
        val identifier = ctx.Identifier()?.text ?: return null
        val position = tokenPosition(ctx.start)
        val symbol = resolveLocalSymbol(identifier, position)
            ?: resolveGlobalSymbol(identifier, position)
        var typeName = symbol?.typeName
            ?: symbol?.let { inferredSymbolTypes[symbolKey(it)] }
        repeat(ctx.identifierSuffix().size) {
            typeName = applyIndexType(typeName)
        }
        return typeName
    }

    private fun inferFunctionCallType(ctx: mcfppParser.FunctionCallContext?): String? {
        if (ctx == null) {
            return null
        }
        val occurrence = functionCallOccurrence(ctx) ?: return null
        val symbol = resolveCallSymbol(occurrence) ?: return null
        val returnType = symbol.typeName ?: symbol.detail ?: return null
        return substituteTypeParameters(returnType, typeParameterSubstitutions(symbol, occurrence.readOnlyArguments))
    }

    private fun functionCallOccurrence(ctx: mcfppParser.FunctionCallContext): MCFPPReferenceOccurrence? {
        val identifiers = ctx.namespaceID()?.getTokens(mcfppParser.Identifier).orEmpty()
        val nameToken = identifiers.lastOrNull()?.symbol ?: return null
        val position = tokenPosition(nameToken)
        return MCFPPReferenceOccurrence(
            name = nameToken.text,
            range = tokenRange(nameToken),
            enclosingTypeScopeId = document.innermostTypeScope(position)?.id,
            enclosingFunctionScopeId = document.innermostFunctionScope(position)?.id,
            isCall = true,
            namespaceQualifier = identifiers.dropLast(1).joinToString(".") { it.text }.takeIf { it.isNotEmpty() },
            readOnlyArguments = ctx.arguments()?.readOnlyArgs()?.expressionList()?.expression()?.map { it.text }.orEmpty(),
            normalArguments = ctx.arguments()?.normalArgs()?.expressionList()?.expression()?.map { it.text }.orEmpty(),
            readOnlyArgumentRanges = ctx.arguments()?.readOnlyArgs()?.expressionList()?.expression()?.map(::contextRange).orEmpty(),
            normalArgumentRanges = ctx.arguments()?.normalArgs()?.expressionList()?.expression()?.map(::contextRange).orEmpty()
        )
    }

    private fun inferMemberVarType(ownerType: String?, ctx: mcfppParser.VarContext?): String? {
        if (ownerType == null || ctx == null) {
            return null
        }
        ctx.functionCall()?.let { return inferFunctionCallType(it) }
        ctx.varWithSuffix()?.let {
            val name = it.Identifier()?.text ?: return null
            var typeName = resolveMemberSymbol(ownerType, name, tokenPosition(it.start))?.typeName
            repeat(it.identifierSuffix().size) {
                typeName = applyIndexType(typeName)
            }
            return typeName
        }
        ctx.bucketExpression()?.expression()?.let { return inferExpressionType(it) }
        return null
    }

    private fun inferValueType(ctx: mcfppParser.ValueContext): String? {
        if (ctx.LineString() != null || ctx.multiLineStringLiteral() != null) {
            return "string"
        }
        if (ctx.TargetSelector() != null) {
            return "selector"
        }
        if (ctx.NULL() != null) {
            return "null"
        }
        ctx.coordinate()?.let {
            return if (it.coordinateDimension().size >= 3) "vec3" else "vec2"
        }
        ctx.nbtValue()?.let { return inferNbtType(it) }
        return null
    }

    private fun inferNbtType(ctx: mcfppParser.NbtValueContext): String? {
        return when {
            ctx.nbtBool() != null -> "bool"
            ctx.nbtByte() != null -> "byte"
            ctx.nbtShort() != null -> "short"
            ctx.nbtInt() != null -> "int"
            ctx.nbtLong() != null -> "long"
            ctx.nbtFloat() != null -> "float"
            ctx.nbtDouble() != null -> "double"
            ctx.nbtList() != null -> "list<any>"
            ctx.nbtByteArray() != null -> "ByteArray"
            ctx.nbtIntArray() != null -> "IntArray"
            ctx.nbtLongArray() != null -> "LongArray"
            ctx.LineString() != null -> "string"
            else -> "nbt"
        }
    }

    private fun isAssignable(expectedType: String?, actualType: String?): Boolean {
        if (expectedType.isNullOrBlank() || actualType.isNullOrBlank()) {
            return true
        }
        val resolvedExpectedType = expandTypeAlias(expectedType)
        val resolvedActualType = expandTypeAlias(actualType)
        val expected = normalizeDeclaredType(resolvedExpectedType)
        val actual = normalizeDeclaredType(resolvedActualType)
        if (expected == actual || expected == "any" || actual == "null") {
            return true
        }
        if (expected == "text" && actual == "string") {
            return true
        }
        if (expected in numericRanks && actual in numericRanks) {
            return numericRanks.getValue(actual) <= numericRanks.getValue(expected)
        }
        val expectedGeneric = parseGenericType(resolvedExpectedType)
        val actualGeneric = parseGenericType(resolvedActualType)
        if (expectedGeneric != null && actualGeneric != null && expectedGeneric.first == actualGeneric.first && expectedGeneric.second.size == actualGeneric.second.size) {
            return expectedGeneric.second.zip(actualGeneric.second).all { (left, right) -> isAssignable(left, right) }
        }
        if (expected == actual.substringAfterLast(':')) {
            return true
        }
        return isSubtype(actual, expected)
    }

    private fun expandTypeAlias(typeName: String, visited: Set<String> = emptySet()): String {
        val normalized = normalizeDeclaredType(typeName)
        if (normalized in visited) {
            return typeName
        }
        val aliasTarget = visibleGlobals
            .asSequence()
            .filter { it.topLevel && it.kind == SymbolKind.TypeParameter }
            .firstOrNull { normalizeDeclaredType(it.name) == normalized }
            ?.typeName
            ?.takeIf { normalizeDeclaredType(it) != normalized }
            ?: return typeName
        return expandTypeAlias(aliasTarget, visited + normalized)
    }

    private fun isSubtype(actualType: String, expectedType: String): Boolean {
        val typesByName = visibleGlobals
            .asSequence()
            .filter { it.topLevel && it.superTypes.isNotEmpty() }
            .groupBy { normalizeDeclaredType(it.name) }
        val pending = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        pending.addLast(actualType)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) {
                continue
            }
            typesByName[current].orEmpty().forEach { symbol ->
                symbol.superTypes.forEach { superType ->
                    val normalizedSuperType = normalizeDeclaredType(superType)
                    if (normalizedSuperType == expectedType) {
                        return true
                    }
                    if (normalizedSuperType !in visited) {
                        pending.addLast(normalizedSuperType)
                    }
                }
            }
        }
        return false
    }

    private fun widestNumericType(types: List<String>): String? {
        val ranked = types.mapNotNull { type -> numericRanks[normalizeBaseType(type)]?.let { rank -> rank to normalizeBaseType(type) } }
        return ranked.maxByOrNull { it.first }?.second
    }

    private fun applyIndexType(typeName: String?): String? {
        val parsed = parseGenericType(typeName) ?: return null
        return when (parsed.first) {
            "list", "ByteArray", "IntArray", "LongArray" -> parsed.second.firstOrNull() ?: when (parsed.first) {
                "ByteArray" -> "byte"
                "IntArray" -> "int"
                "LongArray" -> "long"
                else -> "any"
            }
            "map", "dict" -> parsed.second.getOrNull(1) ?: "any"
            else -> null
        }
    }

    private fun parseGenericType(typeName: String?): Pair<String, List<String>>? {
        if (typeName.isNullOrBlank()) {
            return null
        }
        val trimmed = typeName.trim()
        val start = trimmed.indexOf('<')
        val end = trimmed.lastIndexOf('>')
        if (start < 0 || end <= start) {
            return normalizeDeclaredType(trimmed) to emptyList()
        }
        val head = normalizeDeclaredType(trimmed.substring(0, start))
        val body = trimmed.substring(start + 1, end)
        val args = mutableListOf<String>()
        var depth = 0
        var current = StringBuilder()
        body.forEach { ch ->
            when {
                ch == '<' -> {
                    depth += 1
                    current.append(ch)
                }
                ch == '>' -> {
                    depth -= 1
                    current.append(ch)
                }
                ch == ',' && depth == 0 -> {
                    args += current.toString().trim()
                    current = StringBuilder()
                }
                else -> current.append(ch)
            }
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) {
            args += tail
        }
        return head to args
    }

    private fun normalizeDeclaredType(typeName: String?): String {
        val base = typeName.orEmpty().substringBefore('?').substringBefore('<').trim()
        return resolveImportedType(base).substringAfterLast(':').trim()
    }

    private fun typeParameterSubstitutions(
        symbol: MCFPPSymbol,
        readOnlyArguments: List<String>
    ): Map<String, String> = symbol.parameters
        .filter { it.isReadOnly && normalizeDeclaredType(it.typeName).equals("type", ignoreCase = true) }
        .zip(readOnlyArguments)
        .mapNotNull { (parameter, argument) ->
            val name = parameter.name ?: return@mapNotNull null
            argument.trim().takeIf { it.isNotEmpty() }?.let { name to it }
        }
        .toMap()

    private fun substituteTypeParameters(typeName: String, substitutions: Map<String, String>): String {
        return substitutions.entries.fold(typeName) { current, (parameter, argument) ->
            current.replace(
                Regex("(?<![A-Za-z0-9_])${Regex.escape(parameter)}(?![A-Za-z0-9_])"),
                Regex.escapeReplacement(argument)
            )
        }
    }

    private fun symbolKey(symbol: MCFPPSymbol): String {
        return listOf(
            symbol.uri,
            symbol.name,
            symbol.range.start.line,
            symbol.range.start.character,
            symbol.enclosingFunctionScopeId ?: "",
            symbol.enclosingTypeScopeId ?: ""
        ).joinToString("|")
    }

    private fun normalizeBaseType(typeName: String): String = normalizeDeclaredType(typeName)

    private fun typeMismatchDiagnostic(range: Range, expectedType: String?, actualType: String?, subject: String): Diagnostic {
        return Diagnostic(
            range,
            "Type mismatch for `$subject`: expected `${expectedType ?: "unknown"}`, found `${actualType ?: "unknown"}`",
            DiagnosticSeverity.Error,
            "mcfpp"
        )
    }

    private fun tokenPosition(token: org.antlr.v4.runtime.Token): Position = Position(token.line - 1, token.charPositionInLine)

    private fun tokenRange(token: org.antlr.v4.runtime.Token): Range {
        val start = tokenPosition(token)
        return Range(start, Position(start.line, start.character + token.text.length))
    }

    private fun contextRange(ctx: ParserRuleContext): Range {
        val start = tokenPosition(ctx.start)
        val stop = ctx.stop ?: ctx.start
        return Range(start, Position(stop.line - 1, stop.charPositionInLine + stop.text.length))
    }

    companion object {
        private val numericRanks = mapOf(
            "byte" to 1,
            "short" to 2,
            "int" to 3,
            "long" to 4,
            "float" to 5,
            "double" to 6
        )
    }
}
