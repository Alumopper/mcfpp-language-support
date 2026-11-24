package top.mcfpp.lsp

import top.mcfpp.antlr.mcfppParser
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.ParseTree

internal fun mcfppParser.NormalArgsContext?.argumentExpressions(): List<mcfppParser.ExpressionContext> =
    this?.expressionList()?.expression() ?: this?.callArgumentList()?.callArgument()?.mapNotNull { it.expression() }.orEmpty()

internal fun mcfppParser.NormalArgsContext?.argumentNames(): List<String?> =
    this?.expressionList()?.expression()?.map { null }
        ?: this?.callArgumentList()?.callArgument()?.filter { it.expression() != null }
            ?.map { if (it.ASSIGNMENT() != null) it.Identifier()?.text else null }.orEmpty()

internal fun mcfppParser.NormalArgsContext?.argumentNodes(): List<ParserRuleContext> =
    this?.expressionList()?.expression() ?: this?.callArgumentList()?.callArgument()
        ?.mapNotNull { it.inlineLambda() ?: it.expression() }.orEmpty()

internal fun mcfppParser.NormalArgsContext?.argumentNodeNames(): List<String?> =
    this?.callArgumentList()?.callArgument()?.map { if (it.ASSIGNMENT() != null) it.Identifier()?.text else null }.orEmpty()

internal fun mcfppParser.FunctionCallContext?.trailingLambda(): mcfppParser.InlineLambdaContext? {
    if (this == null) return null
    (parent as? mcfppParser.LambdaCallExpressionContext)?.let { if (it.functionCall() === this) return it.inlineLambda() }
    return (parent?.parent as? mcfppParser.SelectorContext)?.trailingLambda()
}

internal fun mcfppParser.SelectorContext.trailingLambda(): mcfppParser.InlineLambdaContext? {
    inlineLambda()?.let { return it }
    val chain = parent as? mcfppParser.LambdaCallExpressionContext ?: return null
    val callback = chain.inlineLambda() ?: return null
    return callback.takeIf { chain.selector().lastOrNull { s -> s.stop.tokenIndex < callback.start.tokenIndex } === this }
}

internal fun mcfppParser.FunctionCallContext?.callArgumentNodes(): List<ParserRuleContext> =
    this?.arguments()?.normalArgs().argumentNodes() + listOfNotNull(trailingLambda())

internal fun directInlineLambda(start: ParseTree?): mcfppParser.InlineLambdaContext? {
    var node = start
    while (node != null) {
        if (node is mcfppParser.InlineLambdaContext) return node
        node = if (node is mcfppParser.BucketExpressionContext) node.expression()
            else if (node.childCount == 1) node.getChild(0) else null
    }
    return null
}
