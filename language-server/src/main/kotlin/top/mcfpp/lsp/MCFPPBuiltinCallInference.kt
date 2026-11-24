package top.mcfpp.lsp

import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ParserRuleContext
import top.mcfpp.antlr.mcfppLexer
import top.mcfpp.antlr.mcfppParser as P
import top.mcfpp.antlr.mcfppParserBaseVisitor

internal object MCFPPBuiltinCallInference {
    fun result(
        owner: String, name: String?, nodes: List<ParserRuleContext>,
        expressionType: (P.ExpressionContext) -> String?, lambdaType: (P.InlineLambdaContext, List<String>) -> String?,
    ): String? {
        if (name !in MCFPPValueMembers.genericMethodNames) return null
        val candidates = MCFPPValueMembers.members(owner).filter { member ->
            member.name == name && member.typeParameters.isNotEmpty() &&
                member.parameters.count { !it.isReadOnly } == nodes.size
        }
        for (candidate in candidates) {
            var compatible = true
            val member = MCFPPValueMembers.instantiate(candidate, nodes.size) { index, expected ->
                val node = nodes[index]
                val callback = directInlineLambda(node)
                val signature = MCFPPValueMembers.callbackSignature(expected)
                if (callback == null || signature == null) {
                    (node as? P.ExpressionContext)?.let(expressionType)
                } else {
                    lambdaType(callback, signature.first)?.also { actual ->
                        val result = MCFPPValueMembers.callbackSignature(actual)?.second
                        if ('<' in signature.second && result?.substringBefore('<') != signature.second.substringBefore('<')) {
                            compatible = false
                        }
                    }
                }
            }
            if (compatible && member.typeParameters.isEmpty()) return member.typeName
        }
        return null
    }

    fun parseCallback(source: String): P.InlineLambdaContext? {
        val lexer = mcfppLexer(CharStreams.fromString(source))
        lexer.removeErrorListeners()
        val parser = P(CommonTokenStream(lexer))
        parser.removeErrorListeners()
        return parser.inlineLambda().takeIf { parser.numberOfSyntaxErrors == 0 && parser.currentToken.type == P.EOF }
    }

    fun bindings(callback: P.InlineLambdaContext, inputs: List<String>): Map<String, String>? {
        val parameters = callback.lambdaParameterList()?.lambdaParameter()
        if (parameters == null) return when (inputs.size) {
            0 -> emptyMap()
            1 -> mapOf("it" to inputs.single())
            else -> null
        }
        if (parameters.size != inputs.size) return null
        return parameters.zip(inputs).associate { (parameter, input) ->
            parameter.Identifier().text to (parameter.type()?.text ?: input)
        }
    }

    fun callbackType(callback: P.InlineLambdaContext, inputs: List<String>, infer: (P.ExpressionContext) -> String?): String? {
        val body = callback.lambdaBody() ?: return null
        val results = linkedSetOf<String?>()
        body.lambdaTailExpression()?.expression()?.let { results += infer(it) }
        object : mcfppParserBaseVisitor<Unit>() {
            override fun visitInlineLambda(ctx: P.InlineLambdaContext) = Unit
            override fun visitReturnStatement(ctx: P.ReturnStatementContext) {
                results += if (ctx.expression() == null) "void" else infer(ctx.expression())
            }
        }.visit(body)
        val result = if (results.isEmpty()) "void" else results.singleOrNull() ?: return null
        return inputs.joinToString(", ", "(", ") -> $result")
    }
}
