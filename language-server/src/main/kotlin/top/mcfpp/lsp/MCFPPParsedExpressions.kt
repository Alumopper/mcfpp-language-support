package top.mcfpp.lsp

import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.misc.Interval
import org.antlr.v4.runtime.tree.ErrorNode
import org.antlr.v4.runtime.tree.ParseTree
import org.eclipse.lsp4j.Position
import top.mcfpp.antlr.mcfppParser

internal fun ParserRuleContext.sourceText(): String =
    start.inputStream.getText(Interval(start.startIndex, stop.stopIndex))

internal fun MCFPPCallContext.parsedArguments(
    analysis: MCFPPDocumentAnalysis
): List<mcfppParser.ExpressionContext?> =
    argumentPositions.zip(arguments) { position, source -> analysis.expressionAt(position, source) }

internal fun MCFPPDocumentAnalysis.expressionAt(
    position: Position,
    source: String
): mcfppParser.ExpressionContext? =
    expressionAt(MCFPPTextChanges.positionToOffset(analysisText, position), source)

internal fun MCFPPDocumentAnalysis.expressionAt(
    offset: Int,
    source: String
): mcfppParser.ExpressionContext? = argumentAt(offset, source, false) as? mcfppParser.ExpressionContext

internal fun MCFPPDocumentAnalysis.argumentAt(position: Position, source: String): ParserRuleContext? =
    argumentAt(MCFPPTextChanges.positionToOffset(analysisText, position), source, true)

private fun MCFPPDocumentAnalysis.argumentAt(offset: Int, source: String, includeLambda: Boolean): ParserRuleContext? {
    val tree = compilationUnit ?: return null
    val leadingWhitespace = source.indexOfFirst { !it.isWhitespace() }
    if (leadingWhitespace < 0) return null
    val start = offset + leadingWhitespace
    if (start !in analysisText.indices) return null
    return tree.findExpression(analysisText.codePointCount(0, start), source.trim(), includeLambda)
}

private fun ParserRuleContext.findExpression(
    startIndex: Int,
    source: String,
    includeLambda: Boolean,
): ParserRuleContext? {
    val stopToken = stop ?: return null
    if (startIndex !in start.startIndex..stopToken.stopIndex) return null
    if ((this is mcfppParser.ExpressionContext || includeLambda && this is mcfppParser.InlineLambdaContext) && start.startIndex == startIndex &&
        (sourceText() == source || text == source) && !hasParseError()) {
        return this
    }
    for (index in 0 until childCount) {
        val child = getChild(index) as? ParserRuleContext ?: continue
        child.findExpression(startIndex, source, includeLambda)?.let { return it }
    }
    return null
}

private fun ParseTree.hasParseError(): Boolean =
    this is ErrorNode || this is ParserRuleContext && exception != null ||
        (0 until childCount).any { getChild(it).hasParseError() }
