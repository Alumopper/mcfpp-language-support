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
import top.mcfpp.language.ContextBuiltins

class MCFPPTypeChecker(
    private val document: MCFPPDocumentAnalysis,
    private val visibleGlobals: List<MCFPPSymbol>,
    private val resolveLocalSymbol: (String, Position) -> MCFPPSymbol?,
    private val resolveGlobalSymbol: (String, Position) -> MCFPPSymbol?,
    private val resolveCallSymbol: (MCFPPReferenceOccurrence) -> MCFPPSymbol?,
    private val resolveMemberSymbol: (String, String, Position) -> MCFPPSymbol?,
    private val resolveImportedType: (String) -> String = { it },
    private val contextualLiteralType: (Int, Int) -> String? = { _, _ -> null }
) {
    private val inferredSymbolTypes = linkedMapOf<String, String>()
    private val assignmentChecks = mutableSetOf<Pair<String?, String?>>()
    private val inferringSymbols = mutableSetOf<String>()

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
        private val providedContexts = ArrayDeque<Pair<Position, List<Pair<String, String?>>>>()

        override fun visitContextScopeStatement(ctx: mcfppParser.ContextScopeStatementContext) {
            providedContexts.addLast(tokenPosition(ctx.block().start) to ctx.normalArgs().argumentExpressions().map { it.text to inferExpressionType(it) })
            try { super.visitContextScopeStatement(ctx) } finally { providedContexts.removeLast() }
        }

        override fun visitVarWithSelector(ctx: mcfppParser.VarWithSelectorContext) {
            var owner = inferJvmAccessType(ctx.jvmAccessExpression())
            for (selector in ctx.selector()) {
                val name = selector.memberKeyword()?.text ?: selector.`var`()?.varWithSuffix()?.Identifier()?.text
                    ?: selector.`var`()?.functionCall()?.namespaceID()?.text
                if (owner != null && ContextBuiltins.isType(owner) && name != null && top.mcfpp.language.EntityBuiltins.member(owner, name) == null)
                    diagnostics += Diagnostic(contextRange(selector), "Context `$owner` has no member `$name`", DiagnosticSeverity.Error, "mcfpp")
                owner = name?.let { top.mcfpp.language.EntityBuiltins.member(owner, it)?.type() }
                    ?: inferMemberVarType(owner, selector.`var`())
            }
            super.visitVarWithSelector(ctx)
        }

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
            diagnostics += Diagnostic(tokenRange(ctx.start), "`constructor` has been removed. Use `new(...) { return { ... } }` to return a complete object.", DiagnosticSeverity.Error, "mcfpp")
            withFunctionReturnType("void") {
                super.visitTemplateConstructorDeclaration(ctx)
            }
        }

        override fun visitTemplateFactoryDeclaration(ctx: mcfppParser.TemplateFactoryDeclarationContext) {
            val owner = document.innermostTypeScope(tokenPosition(ctx.start))?.name ?: "any"
            withFunctionReturnContract("new", owner, ctx.curlBlock(), tokenRange(ctx.start)) {
                super.visitTemplateFactoryDeclaration(ctx)
            }
        }

        override fun visitPrimary(ctx: mcfppParser.PrimaryContext) {
            if (ctx.THIS() != null && generateSequence(ctx.parent) { it.parent }
                    .takeWhile { it !is mcfppParser.TemplateFunctionDeclarationContext }
                    .any { it is mcfppParser.TemplateFactoryDeclarationContext }) {
                diagnostics += Diagnostic(contextRange(ctx), "A `new` factory has no `this` receiver. Return a complete object explicitly.", DiagnosticSeverity.Error, "mcfpp")
            }
            super.visitPrimary(ctx)
        }

        override fun visitElvisExpression(ctx: mcfppParser.ElvisExpressionContext) {
            val expected = inferAdditiveType(ctx.additiveExpression())?.removeSuffix("?")
            ctx.elvisOperand().forEach { operand ->
                val actual = operand.additiveExpression()?.let(::inferAdditiveType)
                if (!isAssignable(expected, actual)) diagnostics += typeMismatchDiagnostic(contextRange(operand), expected, actual, "Elvis fallback")
            }
            super.visitElvisExpression(ctx)
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
                (declarationSymbol.functionValue?.typeName() ?: inferExpressionType(initializer))?.let { inferredType ->
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
                val expectedType = displayAssignmentType(target) ?: inferVarWithSelectorType(target)
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

        override fun visitInlineLambda(ctx: mcfppParser.InlineLambdaContext) {
            val declaration = generateSequence(ctx.parent) { it.parent }
                .takeWhile { it !is mcfppParser.InlineLambdaContext && it !is mcfppParser.CurlBlockContext }
                .filterIsInstance<mcfppParser.FieldDeclarationContext>().firstOrNull()
            val call = ctx.parent as? mcfppParser.LambdaCallExpressionContext
            val function = call?.functionCall()
            val target = function?.let(::functionCallOccurrence)?.let(resolveCallSymbol)
                ?: call?.varWithSuffix()?.Identifier()?.text?.let { resolveGlobalSymbol(it, tokenPosition(ctx.start)) }
            val count = function?.arguments()?.normalArgs().argumentExpressions()?.size ?: 0
            val callbackType = target?.parameters?.filterNot { it.isReadOnly }?.getOrNull(count)?.typeName
            val resultType = declaration?.type()?.typeBody()?.functionType()?.functionReturnType()?.text
                ?: if (ContextBuiltins.entryBefore(document.analysisText, ctx.start.startIndex) != null) "void" else callbackType?.let(::functionResult)
            withFunctionReturnType(resultType ?: "?") {
                super.visitInlineLambda(ctx)
            }
        }

        override fun visitReturnStatement(ctx: mcfppParser.ReturnStatementContext) {
            val expectedType = functionReturnTypes.lastOrNull()?.takeUnless { it == "?" }
            val expression = ctx.expression()
            if (expression != null && ContextBuiltins.isType(inferExpressionType(expression)))
                diagnostics += Diagnostic(contextRange(expression), "Scoped context values cannot escape through a return", DiagnosticSeverity.Error, "mcfpp")
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
            checkCondition(ctx.conditionExpression())
            super.visitIfStatement(ctx)
        }

        override fun visitElseIfStatement(ctx: mcfppParser.ElseIfStatementContext) {
            checkCondition(ctx.conditionExpression())
            super.visitElseIfStatement(ctx)
        }

        override fun visitWhileStatement(ctx: mcfppParser.WhileStatementContext) {
            checkCondition(ctx.conditionExpression())
            super.visitWhileStatement(ctx)
        }

        override fun visitDoWhileStatement(ctx: mcfppParser.DoWhileStatementContext) {
            checkCondition(ctx.expression())
            super.visitDoWhileStatement(ctx)
        }

        private fun checkCondition(expression: mcfppParser.ExpressionContext?) {
            if (expression == null) return
            checkConditionType(inferExpressionType(expression), contextRange(expression))
        }

        private fun checkCondition(condition: mcfppParser.ConditionExpressionContext?) {
            if (condition == null) return
            val actualType = condition.primary()?.let(::inferPrimaryType)
                ?: inferCommonBinaryOperatorType(condition.commonBinaryOperatorExpression())
            checkConditionType(actualType, contextRange(condition))
        }

        private fun checkConditionType(actualType: String?, range: Range) {
            if (!isAssignable("bool", actualType)) {
                diagnostics += Diagnostic(
                    range,
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
            val containing = ctx.parent as? mcfppParser.LambdaCallExpressionContext
            val trailing = containing?.takeIf { it.functionCall() === ctx }?.inlineLambda()
            val normal = ctx.arguments()?.normalArgs()
            val expressions = normal.argumentExpressions()
            val names = normal.argumentNames()
            val contextNames = target.contextParameters.mapNotNull { it.name }.toSet()
            val ordinary = expressions.indices.filter { names.getOrNull(it) !in contextNames }
            val position = tokenPosition(ctx.start)
            for (dependency in target.contextParameters) {
                val explicit = names.indexOf(dependency.name)
                if (explicit >= 0) {
                    val actual = inferExpressionType(expressions[explicit])
                    if (!isAssignable(dependency.typeName, actual)) diagnostics += Diagnostic(contextRange(expressions[explicit]),
                        "Context `${dependency.name}` requires `${dependency.typeName}`, found `$actual`", DiagnosticSeverity.Error, "mcfpp")
                    continue
                }
                val layers = document.symbols.filter { it.isContextProvider && document.isScopeVisible(it.enclosingFunctionScopeId, position) }
                    .groupBy { it.enclosingFunctionScopeId }.map { (scope, values) ->
                        val start = document.scopeRegions.firstOrNull { it.id == scope }?.range?.start ?: Position(0, 0)
                        start to values.map { it.name to it.typeName }
                    } + providedContexts.toList()
                val matches = layers.sortedWith(compareByDescending<Pair<Position, List<Pair<String, String?>>>> { it.first.line }
                    .thenByDescending { it.first.character }).firstNotNullOfOrNull { (_, values) ->
                    values.filter { (_, type) -> type != null && isAssignable(dependency.typeName, type) }.distinct().takeIf { it.isNotEmpty() }
                }.orEmpty()
                if (matches.size != 1) diagnostics += Diagnostic(contextRange(ctx),
                    if (matches.isEmpty()) "Missing context `${dependency.name}: ${dependency.typeName}`"
                    else "Ambiguous context `${dependency.name}: ${dependency.typeName}`; use a named context argument",
                    DiagnosticSeverity.Error, "mcfpp")
            }
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
                arguments = ordinary.map { expressions[it] },
                typeSubstitutions = typeSubstitutions,
                trailing = trailing,
                names = ordinary.map { names.getOrNull(it) }
            )
        }

        private fun validateArgumentGroup(
            callRange: Range,
            functionName: String,
            groupLabel: String,
            parameters: List<MCFPPFunctionParameter>,
            arguments: List<mcfppParser.ExpressionContext>,
            typeSubstitutions: Map<String, String>,
            trailing: mcfppParser.InlineLambdaContext? = null,
            names: List<String?> = emptyList()
        ) {
            val requiredCount = parameters.count { !it.hasDefault }
            val suppliedCount = arguments.size + if (trailing == null) 0 else 1
            if (suppliedCount !in requiredCount..parameters.size) {
                val expectedCount = if (requiredCount == parameters.size) {
                    parameters.size.toString()
                } else {
                    "$requiredCount..${parameters.size}"
                }
                diagnostics += Diagnostic(
                    callRange,
                    "$groupLabel count mismatch for `$functionName`: expected $expectedCount, found $suppliedCount",
                    DiagnosticSeverity.Error,
                    "mcfpp"
                )
            }
            arguments.forEachIndexed { index, expression ->
                val name = names.getOrNull(index)
                val parameter = if (name == null) parameters.getOrNull(index) else parameters.firstOrNull { it.name == name }
                if (parameter == null) {
                    if (name != null) diagnostics += Diagnostic(contextRange(expression), "Unknown argument `$name` for `$functionName`", DiagnosticSeverity.Error, "mcfpp")
                    return@forEachIndexed
                }
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
        contextualLiteralType(ctx.start.startIndex, ctx.stop.stopIndex + 1)?.let { return it }
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
        val elvis = ctx.elvisExpression(0) ?: return null
        val left = inferAdditiveType(elvis.additiveExpression())
        val fallback = elvis.elvisOperand().firstOrNull()?.additiveExpression()?.let(::inferAdditiveType)
        return if (elvis.ELVIS().isNotEmpty()) left?.removeSuffix("?") ?: fallback else left
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
        // Bare type names are Type values; selecting a static or enum member uses
        // the declaration's own member scope.
        if (currentType == "Type" && ctx.selector().isNotEmpty()) {
            val name = ctx.jvmAccessExpression()?.propertyOperator()?.primary()?.`var`()?.varWithSuffix()?.Identifier()?.text
            if (name != null) {
                val symbol = resolveLocalSymbol(name, tokenPosition(ctx.start)) ?: resolveGlobalSymbol(name, tokenPosition(ctx.start))
                currentType = symbol?.typeName ?: currentType
            }
        }
        ctx.selector().forEach { selector ->
            if (selector.EXCL().isNotEmpty()) {
                currentType = currentType?.removeSuffix("?")
                return@forEach
            }
            val optional = selector.SAFE_DOT() != null
            currentType = selector.memberKeyword()?.let { keyword -> resolveMemberSymbol(currentType ?: return@let null, keyword.text, tokenPosition(keyword.start))?.typeName }
                ?: inferMemberVarType(currentType?.removeSuffix("?"), selector.`var`())
            if (optional && currentType != null) currentType = currentType!!.removeSuffix("?") + "?"
        }
        return currentType
    }

    private fun displayAssignmentType(target: mcfppParser.VarWithSelectorContext): String? {
        var owner = inferJvmAccessType(target.jvmAccessExpression())
        val selectors = target.selector()
        for ((index, selector) in selectors.withIndex()) {
            val name = selector.memberKeyword()?.text ?: selector.`var`()?.varWithSuffix()?.Identifier()?.text ?: return null
            if (index == selectors.lastIndex) return top.mcfpp.language.EntityBuiltins.member(owner, name)?.writeType()
            owner = resolveMemberSymbol(owner ?: return null, name, tokenPosition(selector.start))?.typeName
        }
        return null
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
            val currentType = document.innermostTypeScope(tokenPosition(ctx.start))?.name ?: return null
            return if (entityMemberFunctionAt(tokenPosition(ctx.start), currentType)) {
                MCFPPEntityTemplates.referenceType(currentType)
            } else {
                currentType
            }
        }
        if (ctx.SUPER() != null) {
            val currentType = document.innermostTypeScope(tokenPosition(ctx.start))?.name ?: return null
            val parent = visibleGlobals.firstOrNull { it.name == currentType && it.topLevel }?.superTypes?.firstOrNull()
            return if (parent != null && entityMemberFunctionAt(tokenPosition(ctx.start), currentType)) {
                MCFPPEntityTemplates.referenceType(parent)
            } else {
                parent
            }
        }
        if (ctx.type() != null) {
            return "Type"
        }
        return null
    }

    private fun entityMemberFunctionAt(position: Position, type: String): Boolean =
        document.symbols.any { symbol ->
            symbol.isEntityMember && symbol.kind == SymbolKind.Method &&
                symbol.containerName?.substringBefore('<')?.substringAfterLast(':')?.trim() ==
                type.substringBefore('<').substringAfterLast(':').trim() &&
                containsPosition(symbol.fullRange, position)
        }

    private fun containsPosition(range: Range, position: Position): Boolean =
        !(position.line < range.start.line || position.line == range.start.line &&
            position.character < range.start.character) &&
            !(position.line > range.end.line || position.line == range.end.line &&
                position.character > range.end.character)

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
        var typeName = if (symbol?.kind in setOf(SymbolKind.Class, SymbolKind.Enum, SymbolKind.TypeParameter)) {
            "Type"
        } else if (symbol?.kind in setOf(SymbolKind.Function, SymbolKind.Method)) {
            MCFPPFunctionValue(symbol!!.parameters, symbol.typeName ?: "void", symbol.contextParameters.map { it.typeName }).typeName()
        } else symbol?.functionValue?.typeName() ?: symbol?.typeName
            ?: symbol?.let { inferredSymbolTypes[symbolKey(it)] }
            ?: symbol?.let { declaration ->
                val key = symbolKey(declaration)
                if (!inferringSymbols.add(key)) null else try {
                    declaration.initializer?.let { expressionType(it, MCFPPTextChanges.positionToOffset(document.analysisText, declaration.range.start)) }
                } finally { inferringSymbols.remove(key) }
            }
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
            normalArguments = ctx.arguments()?.normalArgs().argumentExpressions()?.map { it.text }.orEmpty(),
            readOnlyArgumentRanges = ctx.arguments()?.readOnlyArgs()?.expressionList()?.expression()?.map(::contextRange).orEmpty(),
            normalArgumentRanges = ctx.arguments()?.normalArgs().argumentExpressions()?.map(::contextRange).orEmpty()
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
            return if (it.coordinateDimension().size >= 3) "pos3" else "pos2"
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
            ctx.nbtCompound() != null -> ctx.nbtCompound().nbtKeyValuePair().joinToString(", ", "{ ", " }") { field ->
                val name = field.key.text.trim('"', '\'')
                val type = field.expression()?.let(::inferExpressionType)
                    ?: resolveLocalSymbol(name, tokenPosition(field.start))?.typeName
                    ?: "any"
                "$name: $type"
            }
            else -> "nbt"
        }
    }

    internal fun expressionType(expression: String, offset: Int): String? {
        val lexer = mcfppLexer(CharStreams.fromString(expression))
        lexer.removeErrorListeners()
        val tokens = CommonTokenStream(lexer)
        tokens.fill()
        val prefix = document.analysisText.take(offset)
        val lineOffset = prefix.count { it == '\n' }
        val columnOffset = prefix.substringAfterLast('\n').length
        for (token in tokens.tokens.filterIsInstance<org.antlr.v4.runtime.CommonToken>()) {
            token.text = token.text
            if (token.line == 1) token.charPositionInLine += columnOffset
            token.line += lineOffset
            token.startIndex += offset
            token.stopIndex += offset
        }
        val parser = mcfppParser(tokens)
        parser.removeErrorListeners()
        return inferExpressionType(parser.expression())
    }

    internal fun isAssignable(expectedType: String?, actualType: String?): Boolean {
        val key = expectedType to actualType
        if (!assignmentChecks.add(key)) return true
        return try { isAssignableTypes(expectedType, actualType) } finally { assignmentChecks.remove(key) }
    }

    private fun isAssignableTypes(expectedType: String?, actualType: String?): Boolean {
        if (expectedType.isNullOrBlank() || actualType.isNullOrBlank()) {
            return true
        }
        if ("->" in expectedType && "->" in actualType && expectedType.filterNot(Char::isWhitespace) == actualType.filterNot(Char::isWhitespace)) return true
        val expectedTemplate = MCFPPEntityTemplates.templateOf(expectedType)
        val actualTemplate = MCFPPEntityTemplates.templateOf(actualType)
        if (expectedTemplate != null) {
            if (actualTemplate != null) {
                return expectedTemplate == actualTemplate || isSubtype(actualTemplate, expectedTemplate)
            }
            if (normalizeDeclaredType(actualType) == "entity") return true
        }
        if (actualTemplate != null && normalizeDeclaredType(expectedType) == "entity") return true
        val resolvedExpectedType = expandTypeAlias(expectedType)
        val resolvedActualType = expandTypeAlias(actualType)
        val expectedUnion = top.mcfpp.language.StructuralTypes.components(resolvedExpectedType, '|')
        val actualUnion = top.mcfpp.language.StructuralTypes.components(resolvedActualType, '|')
        if (actualUnion.isNotEmpty()) return actualUnion.all { isAssignable(resolvedExpectedType, it) }
        if (expectedUnion.isNotEmpty()) return expectedUnion.any { isAssignable(it, resolvedActualType) }
        val expectedIntersection = top.mcfpp.language.StructuralTypes.components(resolvedExpectedType, '&')
        if (expectedIntersection.isNotEmpty()) return expectedIntersection.all { isAssignable(it, resolvedActualType) }
        val schema = MCFPPTemplateLiterals(visibleGlobals, resolveImportedType) { _, _ -> true }
        val expectedFields = schema.fields(resolvedExpectedType)
        val actualFields = schema.fields(resolvedActualType)
        if (expectedFields != null && actualFields != null && resolvedExpectedType != resolvedActualType) {
            return expectedFields.all { field ->
                val other = actualFields.firstOrNull { it.name == field.name }
                if (other == null) field.typeName?.endsWith('?') == true
                else if (other.typeName?.endsWith('?') == true && field.typeName?.endsWith('?') != true) false
                else isAssignable(field.typeName?.removeSuffix("?"), other.typeName?.removeSuffix("?"))
            }
        }
        val expected = normalizeDeclaredType(resolvedExpectedType)
        val actual = normalizeDeclaredType(resolvedActualType)
        if (expected == "nbt" && actualFields != null) return true
        if (actual == "null") return expectedType.endsWith('?')
        if (resolvedExpectedType.filterNot(Char::isWhitespace) == resolvedActualType.filterNot(Char::isWhitespace) || expected == "any") {
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
            return expectedGeneric.second.zip(actualGeneric.second).all { (left, right) -> isAssignable(left, right) && isAssignable(right, left) }
        }
        if (expected == actual.substringAfterLast(':')) {
            return true
        }
        return isSubtype(actual, expected)
    }

    private fun functionResult(type: String): String? {
        val text = type.trim().let { if (it.startsWith("context(")) it.substringAfter(')').trim() else it }
        if (!text.startsWith('(')) return null
        var depth = 0
        for ((index, char) in text.withIndex()) {
            if (char == '(') depth++
            if (char == ')' && --depth == 0) {
                val tail = text.substring(index + 1).trim()
                return if (tail.startsWith("->")) tail.substring(2).trim() else null
            }
        }
        return null
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
        if (ContextBuiltins.isType(actualType) && ContextBuiltins.isType(expectedType)) return ContextBuiltins.isSubtype(actualType, expectedType)
        if (expectedType == "entity" && actualType == "selector") return true
        val entityKind = MCFPPEntityTemplates.kindOf(visibleGlobals, actualType)
        if (entityKind != null && expectedType in top.mcfpp.language.EntityBuiltins.owners(entityKind)) return true
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
        val resolved = resolveImportedType(base).substringAfterLast(':').trim()
        return if (resolved == "type") "Type" else resolved
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
