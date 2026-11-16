package com.simplelanguage.lsp

internal fun mcfppParser.NormalArgsContext?.argumentExpressions(): List<mcfppParser.ExpressionContext> =
    this?.expressionList()?.expression() ?: this?.callArgumentList()?.callArgument()?.mapNotNull { it.expression() }.orEmpty()

internal fun mcfppParser.NormalArgsContext?.argumentNames(): List<String?> =
    this?.expressionList()?.expression()?.map { null }
        ?: this?.callArgumentList()?.callArgument()?.filter { it.expression() != null }
            ?.map { if (it.ASSIGNMENT() != null) it.Identifier()?.text else null }.orEmpty()
