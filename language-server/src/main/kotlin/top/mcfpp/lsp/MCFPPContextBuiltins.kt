package top.mcfpp.lsp

import top.mcfpp.language.ContextBuiltins
import top.mcfpp.language.EntityBuiltins

internal object MCFPPContextBuiltins {
    private val declarations by lazy {
        val uri = javaClass.classLoader.getResource("editor/entity-builtins.mcfpp")!!.toExternalForm()
        MCFPPParserSemanticExtractor.extract(uri, EntityBuiltins.source()).symbols.map {
            it.copy(isExternal = true, isStdlib = true, isSynthetic = true, namespaceName = "mcfpp.lang")
        }
    }
    fun symbols(): List<MCFPPSymbol> = declarations.filter {
        it.name in ContextBuiltins.TYPES || it.containerName?.let(ContextBuiltins.TYPES::contains) == true ||
            it.topLevel && it.name in ContextBuiltins.ENTRIES
    }
    fun entityParameters(name: String): List<MCFPPFunctionParameter> = declarations.firstOrNull {
        it.containerName == "EntityReference" && it.name == name
    }?.parameters.orEmpty()
}
