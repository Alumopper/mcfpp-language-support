package top.mcfpp.lsp

import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintKind
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.SignatureInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.jsonrpc.messages.Either

internal class MCFPPHoverAndSignatureHandler(private val workspace: MCFPPWorkspaceView) {
    fun hover(params: HoverParams, analysis: MCFPPDocumentAnalysis): Hover? {
        val symbol = workspace.resolveSymbolAt(params, analysis) ?: return null
        val markdown = buildString {
            if (symbol.kind in setOf(SymbolKind.Function, SymbolKind.Method, SymbolKind.Constructor)) {
                append("```mcfpp\n")
                append(buildSignatureLabel(symbol))
                append("\n```")
            } else {
                append(symbol.kind.name.lowercase())
                append(" `")
                append(symbol.name)
                append('`')
                val owner = if (symbol.uri == analysis.uri) analysis else workspace.allAnalyses()[symbol.uri]
                (symbol.typeName ?: owner?.let { declarationType(it, symbol) })?.takeIf { it.isNotBlank() }?.let {
                    append(" : `")
                    append(it)
                    append('`')
                }
            }
            symbol.receiverType?.let {
                append("\n\nReceiver: `")
                append(it)
                append('`')
            }
            symbol.containerName?.takeIf { it.isNotBlank() }?.let {
                append("\n\nContainer: `")
                append(it)
                append('`')
            }
            symbol.documentation?.takeIf { it.isNotBlank() }?.let {
                append("\n\n---\n\n")
                append(it)
            }
            if (symbol.kind == SymbolKind.EnumMember && symbol.detail != null) append("\n\n`${symbol.detail}`")
        }
        val hover = Hover()
        hover.contents = Either.forRight(MarkupContent(MarkupKind.MARKDOWN, markdown))
        hover.range = analysis.wordRange(params.position) ?: symbol.selectionRange
        return hover
    }

    fun signatureHelp(params: SignatureHelpParams, analysis: MCFPPDocumentAnalysis): SignatureHelp? {
        val resolved = MCFPPCallContextFinder.findCandidates(analysis.text, params.position)
            .firstNotNullOfOrNull { context ->
                val symbols = workspace.calls.resolveCallableSymbols(analysis, context, params.position)
                symbols.takeIf { it.isNotEmpty() }?.let { context to it }
            }
            ?: return null
        val (context, symbols) = resolved
        val arguments = context.parsedArguments(analysis)
        val scored = symbols.map { workspace.calls.instantiate(it, context, analysis, params.position, arguments) }.map { symbol ->
            symbol to workspace.calls.signatureMatchScore(symbol, context, analysis, params.position, arguments)
        }
        val activeSignature = scored.indices.maxByOrNull { scored[it].second } ?: 0
        val signatureInformation = scored.map { (symbol, _) ->
            buildSignatureInformation(symbol, context)
        }
        val activeParameter = signatureInformation.getOrNull(activeSignature)?.activeParameter
        return SignatureHelp(signatureInformation, activeSignature, activeParameter)
    }

    fun inlayHint(params: InlayHintParams, analysis: MCFPPDocumentAnalysis): List<InlayHint> {
        val checker by lazy { workspace.typeChecker(analysis) }
        val inferredTypeHints = analysis.symbols
            .asSequence()
            .filter { symbol ->
                !symbol.isExternal &&
                    !symbol.isParameter &&
                    symbol.typeName == null &&
                    symbol.kind in setOf(SymbolKind.Variable, SymbolKind.Field) &&
                    containsPosition(params.range, symbol.selectionRange.end)
            }
            .mapNotNull { symbol -> inferredTypeHint(symbol, checker) }
        val parameterHints = analysis.referenceOccurrencesByName.values
            .asSequence()
            .flatten()
            .filter { occurrence ->
                occurrence.isCall && (occurrence.normalArgumentRanges.asSequence() + occurrence.readOnlyArgumentRanges)
                    .any { containsPosition(params.range, it.start) }
            }
            .flatMap { occurrence -> parameterInlayHints(analysis, occurrence).asSequence() }
            .filter { hint -> containsPosition(params.range, hint.position) }
        val hints = (inferredTypeHints + parameterHints)
            .distinctBy { hint -> listOf(hint.position.line, hint.position.character, hint.label.toString()).joinToString("|") }
            .sortedWith(compareBy({ it.position.line }, { it.position.character }))
            .toList()
        return hints
    }

    private fun declarationType(analysis: MCFPPDocumentAnalysis, symbol: MCFPPSymbol): String? =
        workspace.typeChecker(analysis).declarationType(symbol)?.replace(")->", ") -> ")

    private fun inferredTypeHint(symbol: MCFPPSymbol, checker: MCFPPTypeChecker): InlayHint? {
        if (symbol.initializer == null) return null
        val inferredType = checker.declarationType(symbol)?.replace(")->", ") -> ") ?: return null
        return InlayHint(symbol.selectionRange.end, Either.forLeft(": $inferredType")).apply {
            kind = InlayHintKind.Type
            paddingLeft = false
            paddingRight = true
            setTooltip("Inferred MCFPP type")
        }
    }

    private fun parameterInlayHints(
        analysis: MCFPPDocumentAnalysis,
        occurrence: MCFPPReferenceOccurrence
    ): List<InlayHint> {
        val target = workspace.calls.resolveCallTarget(analysis, occurrence) ?: return emptyList()
        val readOnlyParameters = target.parameters.filter { it.isReadOnly }
        val normalParameters = target.parameters.filterNot { it.isReadOnly }
        return buildList {
            addParameterInlayHints(
                this,
                readOnlyParameters,
                occurrence.readOnlyArguments,
                occurrence.readOnlyArgumentRanges
            )
            addParameterInlayHints(
                this,
                normalParameters,
                occurrence.normalArguments,
                occurrence.normalArgumentRanges
            )
        }
    }

    private fun addParameterInlayHints(
        destination: MutableList<InlayHint>,
        parameters: List<MCFPPFunctionParameter>,
        arguments: List<String>,
        ranges: List<Range>
    ) {
        ranges.forEachIndexed { index, range ->
            val parameter = parameters.getOrNull(index) ?: return@forEachIndexed
            val parameterName = parameter.name?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            if (arguments.getOrNull(index)?.trim() == parameterName) {
                return@forEachIndexed
            }
            destination += InlayHint(range.start, Either.forLeft("$parameterName:")).apply {
                kind = InlayHintKind.Parameter
                paddingLeft = false
                paddingRight = true
                setTooltip(parameter.typeName)
            }
        }
    }

    private fun buildSignatureInformation(symbol: MCFPPSymbol, context: MCFPPCallContext): SignatureInformation {
        val parameters = symbol.parameters + symbol.contextParameters
        val groupParameters = parameters.filter {
            it.isReadOnly == (context.argumentGroup == MCFPPArgumentGroup.READ_ONLY)
        }
        val groupOffset = if (context.argumentGroup == MCFPPArgumentGroup.NORMAL) {
            parameters.count { it.isReadOnly }
        } else {
            0
        }
        val named = context.arguments.getOrNull(context.activeParameter)?.takeIf { '=' in it }?.substringBefore('=')?.trim()
        val activeParameter = parameters.indexOfFirst { named != null && it.name == named }.takeIf { it >= 0 } ?: groupParameters
            .takeIf { it.isNotEmpty() }
            ?.let { groupOffset + context.activeParameter.coerceAtMost(it.lastIndex) }
        return SignatureInformation(buildSignatureLabel(symbol, context.name)).apply {
            symbol.documentation?.takeIf { it.isNotBlank() }?.let {
                documentation = Either.forRight(MarkupContent(MarkupKind.MARKDOWN, it))
            }
            this.parameters = parameters.map { parameter ->
                val description = parameter.name?.let { parameterDocumentation(symbol.documentation, it) }
                if (description == null) ParameterInformation(parameter.label())
                else ParameterInformation(parameter.label(), MarkupContent(MarkupKind.MARKDOWN, description))
            }
            this.activeParameter = activeParameter
        }
    }

    private fun parameterDocumentation(documentation: String?, parameterName: String): String? {
        if (documentation.isNullOrBlank()) return null
        val marker = "@param $parameterName"
        return documentation.lineSequence()
            .map(String::trim)
            .firstOrNull { it == marker || it.startsWith("$marker ") }
            ?.removePrefix(marker)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}
