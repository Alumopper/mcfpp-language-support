package top.mcfpp.lsp

import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.tree.ErrorNode
import org.antlr.v4.runtime.tree.ParseTree
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SymbolKind
import top.mcfpp.antlr.mcfppLexer
import top.mcfpp.antlr.mcfppParser as P

internal class MCFPPValueFacts(
    private val document: MCFPPDocumentAnalysis,
    private val resolve: (String, Position) -> MCFPPSymbol?,
) {
    enum class Stage { CONSTANT, RUNTIME, UNKNOWN }

    fun stage(source: String, position: Position): Stage =
        stage(document.expressionAt(position, source) ?: parse(source), position, emptySet())

    fun stage(node: ParseTree?, position: Position): Stage = stage(node, position, emptySet())

    fun symbolStage(symbol: MCFPPSymbol, position: Position): Stage = symbolStage(symbol, position, emptySet())

    private fun symbolStage(symbol: MCFPPSymbol, position: Position, seen: Set<MCFPPSymbol>): Stage {
        if (symbol in seen) return Stage.UNKNOWN
        if (symbol.isRuntimeValue || (symbol.isParameter && !symbol.isReadOnly)) return Stage.RUNTIME
        if (symbol.kind == SymbolKind.EnumMember || symbol.isReadOnly) return Stage.CONSTANT
        if (symbol.kind in setOf(SymbolKind.Field, SymbolKind.Property)) return Stage.RUNTIME
        if (symbol.kind != SymbolKind.Variable) return Stage.UNKNOWN
        if (document.referenceOccurrencesByName[symbol.name].orEmpty().any {
                it.isAssignmentTarget && isBeforeOrEqual(symbol.range.end, it.range.start) &&
                    isBeforeOrEqual(it.range.start, position) && resolve(symbol.name, it.range.start) == symbol
            }) return Stage.RUNTIME
        val initializer = symbol.initializer ?: return Stage.RUNTIME
        val start = symbol.initializerStart ?: symbol.range.start
        return stage(document.expressionAt(start, initializer) ?: parse(initializer), start, seen + symbol)
    }

    private fun stage(node: ParseTree?, position: Position, seen: Set<MCFPPSymbol>): Stage {
        when (node) {
            null, is ErrorNode -> return Stage.UNKNOWN
            is P.FunctionCallContext, is P.LambdaCallExpressionContext, is P.InlineLambdaContext -> return Stage.RUNTIME
            is P.VarWithSuffixContext -> {
                val symbol = resolve(node.Identifier()?.text ?: return Stage.UNKNOWN, position) ?: return Stage.UNKNOWN
                val base = symbolStage(symbol, position, seen)
                if (base != Stage.CONSTANT) return base
            }
            is P.SelectorContext -> if (node.EXCL().isEmpty()) return Stage.RUNTIME
        }
        val children = (0 until node.childCount).map { stage(node.getChild(it), position, seen) }
        return when {
            Stage.RUNTIME in children -> Stage.RUNTIME
            Stage.UNKNOWN in children -> Stage.UNKNOWN
            else -> Stage.CONSTANT
        }
    }

    private fun parse(source: String): P.ExpressionContext? {
        val lexer = mcfppLexer(CharStreams.fromString(source))
        lexer.removeErrorListeners()
        val parser = P(CommonTokenStream(lexer))
        parser.removeErrorListeners()
        return parser.expression().takeIf { parser.numberOfSyntaxErrors == 0 && parser.currentToken.type == P.EOF }
    }
}
