package top.mcfpp.lsp

import org.antlr.v4.runtime.Token
import top.mcfpp.antlr.mcfppParser as P

/** Source facts shared by editor indexing for receiver-qualified native macro declarations. */
internal object MCFPPMacroExtraction {
    data class Header(
        val name: Token,
        val receiverType: String,
        val parameters: P.FunctionParamsContext,
        val returnType: String?,
        val typeParameters: List<String>,
    )

    fun extract(context: P.NativeFuncDeclarationContext): Header? {
        val part = context.receiverNativeFunctionDeclarationPart() ?: return null
        val name = part.Identifier()?.symbol ?: part.MAP()?.symbol ?: return null
        val parameters = part.functionParams() ?: return null
        val generics = parameters.readOnlyParams()?.readOnlyParameterList()?.readOnlyParameter()
            .orEmpty().map { it.Identifier().text }
        return Header(name, part.type().text.trim(), parameters, part.functionReturnType()?.text?.trim(), generics)
    }

    /** Match `list<E>` from a declaration with a concrete receiver such as `list<int>`. */
    fun receiverBindings(pattern: String, actual: String, parameters: Set<String>): Map<String, String>? {
        fun match(expected: String, supplied: String, result: MutableMap<String, String>): Boolean {
            val left = expected.trim()
            val right = supplied.trim()
            if (left in parameters) {
                val previous = result.putIfAbsent(left, right)
                return previous == null || previous == right
            }
            val leftBase = left.substringBefore('<').trim().substringAfterLast(':')
            val rightBase = right.substringBefore('<').trim().substringAfterLast(':')
            if (leftBase != rightBase) return false
            val leftArgs = typeArguments(left)
            val rightArgs = typeArguments(right)
            return leftArgs.size == rightArgs.size && leftArgs.zip(rightArgs).all { (a, b) -> match(a, b, result) }
        }
        val result = linkedMapOf<String, String>()
        return result.takeIf { match(pattern, actual, it) }
    }

    private fun typeArguments(value: String): List<String> = if ('<' !in value || !value.endsWith('>')) emptyList()
        else MCFPPTemplateLiterals.splitTypes(value.substringAfter('<').dropLast(1))
}
