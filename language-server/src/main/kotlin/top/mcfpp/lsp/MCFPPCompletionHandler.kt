package top.mcfpp.lsp

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import top.mcfpp.language.VersionPreprocessor

internal class MCFPPCompletionHandler(private val workspace: MCFPPWorkspaceView) {
    private enum class CompletionContext {
        DEFAULT,
        VALUE,
        TYPE
    }

    fun completion(params: CompletionParams, analysis: MCFPPDocumentAnalysis): Either<List<CompletionItem>, CompletionList> =
        buildCompletions(params, analysis)

    private fun buildCompletions(
        params: CompletionParams,
        analysis: MCFPPDocumentAnalysis
    ): Either<List<CompletionItem>, CompletionList> {
        val directiveStart = VersionPreprocessor.directivePrefixStart(
            analysis.text, MCFPPTextChanges.positionToOffset(analysis.text, params.position)
        )
        if (directiveStart >= 0) {
            val start = offsetToPosition(analysis.text, directiveStart)
            val items = listOf(
                "#if" to "#if MC >= \${1:26.3}",
                "#elif" to "#elif MC >= \${1:26.1}",
                "#else" to "#else",
                "#endif" to "#endif"
            ).map { (label, snippet) ->
                CompletionItem(label).apply {
                    kind = CompletionItemKind.Keyword
                    detail = "Minecraft version conditional compilation"
                    insertTextFormat = InsertTextFormat.Snippet
                    textEdit = Either.forLeft(TextEdit(
                        Range(start, params.position), snippet
                    ))
                }
            }
            return Either.forLeft(items)
        }
        templateCompletions(analysis, params.position)?.let {
            return Either.forLeft(it)
        }
        val receiver = analysis.receiverBefore(params.position)
        val namespaceQualifier = workspace.namespaceQualifierBefore(analysis, params.position)
        val context = determineCompletionContext(analysis, params.position)
        val items = if (receiver != null) {
            resolveMemberCompletions(analysis, receiver, params.position)
        } else if (namespaceQualifier != null) {
            val namespace = resolveNamespaceQualifier(analysis, namespaceQualifier)
            workspace.workspaceSymbols()
                .filter { it.topLevel && it.namespaceName == namespace }
                .map(::symbolCompletion)
        } else if (context == CompletionContext.TYPE) {
            buildList {
                addAll(MCFPPDocumentIndex.builtinTypes.map { typeCompletion(it) })
                addAll(
                    globalCompletionItems(analysis) { it.kind in TYPE_DEFINITION_SYMBOL_KINDS }
                )
                addAll(
                    visibleSymbolsForCompletion(analysis, params.position)
                        .filter { it.kind == SymbolKind.TypeParameter }
                        .map(::symbolCompletion)
                )
            }
        } else {
            buildList {
                addAll(MCFPPDocumentIndex.keywords
                    .filter { context != CompletionContext.VALUE || it in top.mcfpp.language.CompletionScope.VALUE_KEYWORDS }
                    .map { keywordCompletion(it) })
                addAll(MCFPPDocumentIndex.builtinValues.map { valueCompletion(it) })
                addAll(workspace.contextualEnumMembers(analysis, params.position).map(::symbolCompletion))
                addAll(MCFPPDocumentIndex.referenceFactories.map { name -> CompletionItem(name).apply {
                    kind = CompletionItemKind.Function
                    detail = "Create a reference from an explicit Minecraft address"
                } })
                addAll(globalCompletionItems(analysis) {
                    it.kind !in TYPE_DEFINITION_SYMBOL_KINDS || it.name !in top.mcfpp.language.EntityBuiltins.TYPES + top.mcfpp.language.ContextBuiltins.TYPES
                })
                analysis.innermostTypeScope(params.position)?.name?.let { currentType ->
                    addAll(
                        workspace.accessibleMemberSymbolsForType(analysis, currentType, params.position)
                            .filter { it.kind != SymbolKind.Constructor }
                            .map(::symbolCompletion)
                    )
                }
                addAll(visibleSymbolsForCompletion(analysis, params.position).map { symbolCompletion(it) })
            }
        }
        return Either.forLeft(items.distinctBy { it.label })
    }

    private fun resolveMemberCompletions(
        analysis: MCFPPDocumentAnalysis,
        receiver: String,
        position: Position
    ): List<CompletionItem> {
        val receiverType = workspace.calls.resolveReceiverTypeAt(analysis, receiver, position) ?: return emptyList()
        val constant = workspace.calls.isConstantReceiver(analysis, receiver, position)
        return workspace.receiverMembers(analysis, receiver, receiverType, position)
            .filter { it.kind != SymbolKind.Constructor && (constant || !it.requiresConstantReceiver) }
            .map { symbolCompletion(it) }
    }

    private fun visibleSymbolsForCompletion(analysis: MCFPPDocumentAnalysis, position: Position): List<MCFPPSymbol> {
        return analysis.symbols
            .filter { !it.topLevel }
            .filter { it.kind in setOf(SymbolKind.Variable, SymbolKind.Field, SymbolKind.Method, SymbolKind.Function, SymbolKind.Constructor, SymbolKind.EnumMember, SymbolKind.TypeParameter) }
            .filter { analysis.isScopeVisible(it.enclosingFunctionScopeId ?: it.enclosingTypeScopeId, position) }
            .filter { isBeforeOrEqual(it.range.start, position) }
            .sortedBy { it.name }
    }

    private fun templateCompletions(analysis: MCFPPDocumentAnalysis, position: Position): List<CompletionItem>? {
        val (literals, schema) = workspace.templateContext(analysis)
        val offset = MCFPPTextChanges.positionToOffset(analysis.text, position)
        val literal = literals.at(offset) ?: return null
        val site = literals.keySite(literal, offset) ?: return null
        val types = literals.bestTypes(literal).takeIf { it.isNotEmpty() } ?: return null
        return literals.completions(literal, site).map { field ->
            val symbol = types.firstNotNullOfOrNull { schema.fields(it)?.firstOrNull { it.name == field.name() } }
            CompletionItem(field.name()).apply {
                kind = CompletionItemKind.Field
                detail = "${field.name()}: ${field.type()}" + if (field.hasDefault()) " (default)" else ""
                symbol?.documentation?.let { documentation = Either.forRight(MarkupContent(MarkupKind.MARKDOWN, it)) }
                insertTextFormat = if (site.hasColon()) InsertTextFormat.PlainText else InsertTextFormat.Snippet
                textEdit = Either.forLeft(TextEdit(Range(offsetToPosition(analysis.text, site.start()), offsetToPosition(analysis.text, site.end())),
                    field.name() + if (site.hasColon()) "" else ": \${1}"))
                sortText = "0${field.name()}"
            }
        }
    }

    private fun determineCompletionContext(analysis: MCFPPDocumentAnalysis, position: Position): CompletionContext {
        val line = analysis.line(position.line)
        val prefix = line.substring(0, position.character.coerceIn(0, line.length))
        val trimmed = prefix.trimEnd()
        val beforeIdentifier = prefix.replace(IDENTIFIER_SUFFIX, "")
        if (top.mcfpp.language.CompletionScope.isTypeArgumentOrCast(beforeIdentifier)) {
            return CompletionContext.TYPE
        }
        if (CAST_TYPE_CONTEXT.containsMatchIn(trimmed)) {
            return CompletionContext.TYPE
        }
        if (DECLARED_TYPE_CONTEXT.containsMatchIn(trimmed)) {
            return CompletionContext.TYPE
        }
        return if (top.mcfpp.language.CompletionScope.isValuePosition(beforeIdentifier)) CompletionContext.VALUE
            else CompletionContext.DEFAULT
    }

    private fun keywordCompletion(keyword: String): CompletionItem = CompletionItem(keyword).apply {
        kind = CompletionItemKind.Keyword
        detail = "keyword"
    }

    private fun typeCompletion(typeName: String): CompletionItem = CompletionItem(typeName).apply {
        kind = CompletionItemKind.Class
        detail = "builtin type"
    }

    private fun valueCompletion(value: String): CompletionItem = CompletionItem(value).apply {
        kind = CompletionItemKind.Value
        detail = "builtin value"
    }

    private fun globalCompletionItems(
        analysis: MCFPPDocumentAnalysis,
        include: (MCFPPSymbol) -> Boolean = { true }
    ): List<CompletionItem> {
        val currentNamespace = analysis.namespaceName ?: workspace.namespaceForUri(analysis.uri)
        val wildcardNamespaces = analysis.imports
            .filter { it.importedName == "*" && it.alias == null }
            .mapTo(mutableSetOf()) { it.namespace }
        return workspace.visibleGlobalSymbolsForDocument(analysis)
            .asSequence()
            .filter { it.topLevel && include(it) }
            .flatMap { symbol ->
                val namespace = symbol.namespaceName ?: workspace.namespaceForUri(symbol.uri)
                val matchingImports = analysis.imports.filter {
                    it.importedName == symbol.name && it.namespace == namespace
                }
                buildList {
                    matchingImports.mapNotNullTo(this) { imported ->
                        imported.alias?.let { alias -> symbolCompletion(symbol, alias) }
                    }
                    if (
                        (symbol.isStdlib && namespace in setOf("mcfpp.lang", "mcfpp.sys", "mcfpp")) ||
                        symbol.uri == analysis.uri ||
                        namespace == currentNamespace ||
                        (namespace != null && namespace in wildcardNamespaces) ||
                        matchingImports.any { it.alias == null }
                    ) {
                        add(symbolCompletion(symbol))
                    }
                }.asSequence()
            }
            .toList()
    }

    private fun symbolCompletion(
        symbol: MCFPPSymbol,
        displayName: String = symbol.name
    ): CompletionItem = CompletionItem(displayName).apply {
        kind = symbol.toCompletionKind()
        detail = if (symbol.kind in setOf(SymbolKind.Function, SymbolKind.Method, SymbolKind.Constructor)) {
            buildSignatureLabel(symbol, displayName)
        } else {
            symbol.typeName ?: symbol.detail ?: symbol.containerName
        }
        symbol.documentation?.takeIf { it.isNotBlank() }?.let {
            documentation = Either.forRight(MarkupContent(MarkupKind.MARKDOWN, it))
        }
    }

    private companion object {
        private val IDENTIFIER_SUFFIX = Regex("[A-Za-z_][A-Za-z0-9_]*$")
        private val CAST_TYPE_CONTEXT = Regex("(?:\\bas\\b|\\)\\s*:)\\s*[A-Za-z0-9_:.<>!?|&\\[\\]]*$")
        private val DECLARED_TYPE_CONTEXT = Regex("(?:\\b(?:var|const|external)\\s+[A-Za-z_][A-Za-z0-9_]*\\s*:|\\bfunc\\s+[A-Za-z_][A-Za-z0-9_]*\\s*(?:<[^>]*>\\s*)?\\([^)]*\\)\\s*:|[<(,]\\s*[A-Za-z_][A-Za-z0-9_]*\\s*:)\\s*[A-Za-z0-9_:.<>!?|&\\[\\]]*$")
    }
}
