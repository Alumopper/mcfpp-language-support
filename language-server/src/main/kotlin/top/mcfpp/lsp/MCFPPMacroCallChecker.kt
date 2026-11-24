package top.mcfpp.lsp

import org.antlr.v4.runtime.ParserRuleContext
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import top.mcfpp.antlr.mcfppParser as P
import top.mcfpp.antlr.mcfppParserBaseVisitor
import top.mcfpp.library.model.MacroParameterMode.*

internal object MCFPPMacroCallChecker {
    fun check(
        call: P.FunctionCallContext, target: MCFPPSymbol, document: MCFPPDocumentAnalysis,
        local: (String, Position) -> MCFPPSymbol?, global: (String, Position) -> MCFPPSymbol?,
        assignable: (String?, String?) -> Boolean, range: (ParserRuleContext) -> Range,
    ): List<Diagnostic> {
        val parameters = target.parameters.filterNot { it.isReadOnly }
        if (parameters.none { it.macroMode != null }) return emptyList()
        val values = MCFPPValueFacts(document) { name, position -> local(name, position) ?: global(name, position) }
        return buildList {
            fun report(node: ParserRuleContext, message: String) {
                add(Diagnostic(range(node), message, DiagnosticSeverity.Error, "mcfpp"))
            }
            fun checkMode(node: ParserRuleContext, parameter: MCFPPFunctionParameter) {
                val label = parameter.name?.let { "`$it`" } ?: "argument"
                val callback = directInlineLambda(node)
                when (parameter.macroMode) {
                    REF -> report(node, "Macro parameter $label requires a compiler-issued REF location. Public calls pass ScoreRef or NBTRef<T> as VALUE.")
                    CONST -> if (values.stage(node, range(node).start) == MCFPPValueFacts.Stage.RUNTIME)
                        report(node, "Macro parameter $label requires a compile-time constant")
                    INLINE -> if (callback == null) {
                        report(node, "Parameter $label requires a direct inline lambda")
                    } else {
                        checkCallback(callback, parameter, local, assignable, range, ::report, ::checkMode)
                    }
                    VALUE -> if (target.isMacro && callback != null)
                        report(node, "Macro parameter $label requires a value. Direct callbacks require INLINE mode.")
                    null -> Unit
                }
            }
            val names = call.arguments()?.normalArgs().argumentNodeNames()
            call.callArgumentNodes().forEachIndexed { index, node ->
                val name = names.getOrNull(index)
                val parameter = if (name == null) parameters.getOrNull(index) else parameters.firstOrNull { it.name == name }
                if (parameter != null) checkMode(node, parameter)
            }
        }
    }

    private fun checkCallback(
        callback: P.InlineLambdaContext, parameter: MCFPPFunctionParameter,
        local: (String, Position) -> MCFPPSymbol?, assignable: (String?, String?) -> Boolean,
        range: (ParserRuleContext) -> Range, report: (ParserRuleContext, String) -> Unit,
        checkMode: (ParserRuleContext, MCFPPFunctionParameter) -> Unit,
    ) {
        val inputs = MCFPPValueMembers.callbackSignature(parameter.typeName)?.first ?: return
        val formals = callback.lambdaParameterList()?.lambdaParameter()
        if (formals == null) {
            if (inputs.size > 1) report(callback, "Inline callback requires ${inputs.size} explicit parameters")
        } else if (formals.size != inputs.size) {
            report(callback, "Inline callback expects ${inputs.size} parameters, found ${formals.size}")
            return
        }
        formals.orEmpty().zip(inputs).forEach { (formal, expected) ->
            formal.type()?.let { type ->
                if (!assignable(expected, type.text) || !assignable(type.text, expected))
                    report(type, "Inline callback parameter `${formal.Identifier().text}` requires type `$expected`")
            }
        }
        if (parameter.macroCallbackParameters.isEmpty()) return
        val bindings = if (formals == null) {
            parameter.macroCallbackParameters.singleOrNull()?.let { mapOf("it" to (null to it)) }.orEmpty()
        } else formals.zip(parameter.macroCallbackParameters).associate { (formal, metadata) ->
            formal.Identifier().text to (range(formal).start to metadata)
        }
        fun binding(name: String, position: Position): MCFPPFunctionParameter? {
            val (declaration, metadata) = bindings[name] ?: return null
            val actual = local(name, position)
            return metadata.takeIf { actual?.range?.start == declaration || declaration == null && actual?.isSynthetic == true }
        }
        object : mcfppParserBaseVisitor<Unit>() {
            override fun visitStatementExpression(ctx: P.StatementExpressionContext) {
                val target = ctx.varWithSelector()
                val identifier = target?.jvmAccessExpression()?.propertyOperator()?.primary()?.`var`()
                    ?.varWithSuffix()?.Identifier()?.text
                if (target != null && identifier != null && binding(identifier, range(target).start)?.macroMode == CONST) {
                    report(target, "Cannot assign to const callback parameter `$identifier`")
                }
                super.visitStatementExpression(ctx)
            }

            override fun visitFunctionCall(ctx: P.FunctionCallContext) {
                val formal = binding(ctx.namespaceID().text, range(ctx).start)
                if (formal?.macroMode == INLINE) {
                    ctx.callArgumentNodes().zip(formal.macroCallbackParameters).forEach { (argument, metadata) -> checkMode(argument, metadata) }
                }
                super.visitFunctionCall(ctx)
            }
        }.visit(callback.lambdaBody())
    }
}
