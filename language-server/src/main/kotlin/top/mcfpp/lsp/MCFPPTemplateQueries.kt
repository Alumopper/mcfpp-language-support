package top.mcfpp.lsp

import org.eclipse.lsp4j.Position
import top.mcfpp.language.TemplateLiterals

internal fun MCFPPWorkspaceView.templateSchema(analysis: MCFPPDocumentAnalysis): MCFPPTemplateLiterals = MCFPPTemplateLiterals(
    workspaceSymbols(),
    { type ->
        if (':' in type) resolveNamespaceQualifier(analysis, type.substringBeforeLast(':')) + ":" + type.substringAfterLast(':')
        else {
            val namespace = analysis.namespaceName ?: namespaceForUri(analysis.uri)
            val local = workspaceSymbols().firstOrNull { it.topLevel && it.name == type && it.kind in TYPE_SYMBOL_KINDS &&
                (it.uri == analysis.uri || it.namespaceName == namespace && !it.isStdlib) }
            local?.let { symbol -> symbol.namespaceName?.takeIf { it.isNotEmpty() }?.let { "$it:${symbol.name}" } ?: symbol.name }
                ?: analysis.imports.firstOrNull { !it.importedName.equals("*") && (it.alias ?: it.importedName) == type }
                    ?.let { "${it.namespace}:${it.importedName}" }
                ?: type
        }
    },
    { field, offset -> isMemberAccessible(analysis, field, offsetToPosition(analysis.text, offset)) }
)

internal fun MCFPPWorkspaceView.templateContext(analysis: MCFPPDocumentAnalysis): Pair<TemplateLiterals, MCFPPTemplateLiterals> {
    val schema = templateSchema(analysis)
    val checker by lazy { typeChecker(analysis) }
    val resolver = object : TemplateLiterals.Resolver {
        override fun fields(type: String, offset: Int) = schema.schema(type, offset)
        override fun constructionError(type: String, offset: Int) = schema.constructionError(type, offset)
        override fun returnTypes(expressionStart: Int): List<String> {
            val position = offsetToPosition(analysis.text, expressionStart)
            return analysis.symbols.filter { it.kind in CALLABLE_SYMBOL_KINDS && containsPosition(it.fullRange, position) }
                .minByOrNull { MCFPPTextChanges.positionToOffset(analysis.text, it.fullRange.end) - MCFPPTextChanges.positionToOffset(analysis.text, it.fullRange.start) }
                ?.typeName?.let(::listOf).orEmpty()
        }
        override fun argumentTypes(expressionStart: Int): List<String> {
            val position = offsetToPosition(analysis.text, expressionStart)
            for (call in MCFPPCallContextFinder.findCandidates(analysis.analysisText, position)) {
                val targets = calls.resolveCallableSymbols(analysis, call, position)
                if (targets.isEmpty()) continue
                val named = Regex("^\\s*([\\p{L}_][\\p{L}\\p{N}_]*)\\s*=\\s*$")
                    .matchEntire(call.arguments.lastOrNull().orEmpty())?.groupValues?.get(1)
                return targets.mapNotNull { target ->
                    val parameters = (target.parameters + target.contextParameters).filter {
                        it.isReadOnly == (call.argumentGroup == MCFPPArgumentGroup.READ_ONLY)
                    }
                    val parameter = if (named != null) parameters.firstOrNull { it.name == named }
                        else parameters.getOrNull(call.activeParameter)
                    parameter?.typeName?.let { substituteTypeParameters(it, typeParameterSubstitutions(target, call.readOnlyArguments)) }
                }
            }
            return emptyList()
        }
        override fun assignmentType(expression: String, offset: Int): String? {
            val position = offsetToPosition(analysis.text, offset)
            val separator = lastTopLevelMemberSeparator(expression)
            if (separator > 0) {
                val owner = calls.resolveReceiverTypeAt(analysis, expression.substring(0, separator), position)
                top.mcfpp.language.EntityBuiltins.member(owner, expression.substring(separator + 1).trim())?.writeType()?.let { return it }
            }
            return calls.resolveReceiverTypeAt(analysis, expression, position)
        }
        override fun valueType(expression: String, offset: Int): String? = checker.expressionType(expression, offset)
            ?: calls.inferArgumentType(analysis, expression, offsetToPosition(analysis.text, offset))
        override fun isAssignable(expected: String, actual: String): Boolean =
            if (actual == "null") false else checker.isAssignable(expected, actual)
    }
    return TemplateLiterals(analysis.analysisText, resolver) to schema
}

internal fun MCFPPWorkspaceView.typeChecker(document: MCFPPDocumentAnalysis, contextualLiteralType: (Int, Int) -> String? = { _, _ -> null }): MCFPPTypeChecker =
    MCFPPTypeChecker(
        document = document,
        visibleGlobals = visibleGlobalSymbolsForDocument(document),
        resolveLocalSymbol = { name, position -> resolveLocalSymbol(document, name, position) },
        resolveGlobalSymbol = { name, position -> resolveGlobalSymbol(document, name, position) },
        resolveCallSymbol = { occurrence -> calls.resolveCallTarget(document, occurrence) },
        resolveMemberSymbol = { ownerType, name, position ->
            val receiver = document.receiverBefore(position)
            (if (receiver != null) receiverMembers(document, receiver, ownerType, position)
                else accessibleMemberSymbolsForType(document, ownerType, position)).firstOrNull { it.name == name }
        },
        resolveImportedType = { typeName -> resolveImportedTypeName(document, typeName) ?: typeName },
        contextualLiteralType = contextualLiteralType
    )

internal fun MCFPPWorkspaceView.templateFieldAt(analysis: MCFPPDocumentAnalysis, position: Position): MCFPPSymbol? {
    val (literals, schema) = templateContext(analysis)
    val offset = MCFPPTextChanges.positionToOffset(analysis.text, position)
    val literal = literals.at(offset) ?: return null
    val entry = literals.entries(literal).firstOrNull { it.colon() >= 0 && offset >= it.keyStart() && offset <= it.keyEnd() } ?: return null
    return literals.bestTypes(literal).firstNotNullOfOrNull { type -> schema.fields(type)?.firstOrNull { it.name == entry.name() } }
}
