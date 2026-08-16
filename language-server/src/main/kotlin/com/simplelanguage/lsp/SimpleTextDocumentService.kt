package com.simplelanguage.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.concurrent.CompletableFuture
import org.antlr.v4.runtime.*
import org.antlr.v4.runtime.tree.*

/**
 * 文本文档服务类，处理LSP文档相关请求
 * 实现了TextDocumentService接口，负责处理文档的打开、变更、关闭、保存等事件
 * 以及代码补全、定义跳转、悬停信息等核心LSP功能
 */
class SimpleTextDocumentService(private val server: SimpleLanguageServer) : TextDocumentService {
    
    /**
     * 符号信息数据类，用于存储变量和函数的定义信息
     * @property name 符号名称，如变量名或函数名
     * @property range 符号在文档中的位置范围，包括起始行、列和结束行、列
     * @property type 符号类型，"variable"表示变量，"function"表示函数，"data"表示数据模板
     * @property dataType 数据类型名称，用于变量和成员
     * @property members 数据模板的成员列表，用于data类型
     */
    data class SymbolInfo(
        val name: String,
        val range: Range,
        val type: String,
        val dataType: String? = null,
        val members: MutableList<SymbolInfo> = mutableListOf()
    )
    
    /**
     * 文档内容映射，存储每个文档URI对应的文本内容
     * Key: 文档URI
     * Value: 文档文本内容
     */
    private val documents = mutableMapOf<String, String>()
    
    /**
     * 符号表映射，存储每个文档URI对应的符号信息列表
     * Key: 文档URI
     * Value: 该文档中定义的所有符号（变量和函数）
     */
    private val symbolTable = mutableMapOf<String, MutableList<SymbolInfo>>()
    
    /**
     * 文档打开事件处理
     * 当客户端打开一个新文档时调用
     * @param params 文档打开参数，包含文档URI、语言ID、版本和内容
     */
    override fun didOpen(params: DidOpenTextDocumentParams) {
        val uri = params.textDocument.uri
        val text = params.textDocument.text
        documents[uri] = text
        validateDocument(uri, text)
    }
    
    /**
     * 文档变更事件处理
     * 当客户端修改文档内容时调用
     * @param params 文档变更参数，包含文档URI、版本和变更内容列表
     */
    override fun didChange(params: DidChangeTextDocumentParams) {
        val uri = params.textDocument.uri
        val text = params.contentChanges[0].text
        documents[uri] = text
        validateDocument(uri, text)
    }
    
    /**
     * 文档关闭事件处理
     * 当客户端关闭一个文档时调用
     * @param params 文档关闭参数，包含文档URI和版本
     */
    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = params.textDocument.uri
        documents.remove(uri)
        // 移除对应的符号表条目
        symbolTable.remove(uri)
    }
    
    /**
     * 文档保存事件处理
     * 当客户端保存一个文档时调用
     * @param params 文档保存参数，包含文档URI和版本
     */
    override fun didSave(params: DidSaveTextDocumentParams) {
        val uri = params.textDocument.uri
        val text = documents[uri] ?: return
        validateDocument(uri, text)
    }
    
    /**
     * 验证文档内容，构建符号表并发送诊断信息
     * 这是核心方法，负责：
     * 1. 解析文档内容，查找变量和函数定义
     * 2. 构建符号表，存储符号的名称、位置和类型
     * 3. 发送诊断信息到客户端，显示语法错误
     * @param uri 文档URI
     * @param text 文档内容
     */
    private fun validateDocument(uri: String, text: String) {
        val diagnostics = mutableListOf<Diagnostic>()
        val symbols = mutableListOf<SymbolInfo>()
        
        try {
            // 创建ANTLR输入流
            val input = CharStreams.fromString(text)
            
            // 创建词法分析器
            val lexer = SimpleLanguageLexer(input)
            val tokens = CommonTokenStream(lexer)
            
            // 创建语法分析器
            val parser = SimpleLanguageParser(tokens)
            
            // 添加错误监听器，捕获语法错误
            parser.addErrorListener(object : BaseErrorListener() {
                override fun syntaxError(
                    recognizer: Recognizer<*, *>,
                    offendingSymbol: Any?,
                    line: Int,
                    charPositionInLine: Int,
                    msg: String,
                    e: RecognitionException?
                ) {
                    // 创建诊断信息
                    val range = Range(
                        Position(line - 1, charPositionInLine),
                        Position(line - 1, charPositionInLine + 1)
                    )
                    diagnostics.add(Diagnostic(range, msg, DiagnosticSeverity.Error, "simple-language"))
                }
            })
            
            // 解析文档
            parser.compilationUnit()
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // 将文档按行分割，便于逐行分析
        val lines = text.lines()
        
        // 定义正则表达式模式，用于匹配变量定义、函数定义和数据模板定义
        // 变量定义模式：匹配 "var x = 1"、"var x as int = 1" 或 "var x as int" 这样的语句
        val varPattern = Regex("^\\s*var\\s+(\\w+)(?:\\s+as\\s+(\\w+))?(?:\\s*=.*)?")
        
        // 函数定义模式：匹配 "func test(a as int) -> int {" 或 "func test(a as int) {" 这样的语句
        val funcPattern = Regex("^\\s*func\\s+(\\w+)\\s*\\(")
        
        // 数据模板定义模式：匹配 "data Test {" 这样的语句
        val dataPattern = Regex("^\\s*data\\s+(\\w+)\\s*\\{")
        
        // 遍历每一行，查找变量、函数和数据模板定义
        var currentDataSymbol: SymbolInfo? = null
        val dataMembers = mutableMapOf<String, MutableList<SymbolInfo>>()
        
        for ((lineIndex, line) in lines.withIndex()) {
            // 查找数据模板定义
            dataPattern.find(line)?.let { match ->
                val dataName = match.groupValues[1]
                // 计算数据模板名在该行中的位置
                val startCol = match.range.start + match.value.indexOf(dataName)
                val range = Range(
                    Position(lineIndex, startCol),
                    Position(lineIndex, startCol + dataName.length)
                )
                // 创建数据模板符号
                currentDataSymbol = SymbolInfo(dataName, range, "data")
                dataMembers[dataName] = mutableListOf()
                // 添加到符号表
                symbols.add(currentDataSymbol!!)
            }
            
            // 查找数据模板结束
            if (line.contains("}") && currentDataSymbol != null) {
                // 更新数据模板符号的成员列表
                val updatedSymbol = currentDataSymbol!!.copy(members = dataMembers[currentDataSymbol!!.name]!!)
                symbols.remove(currentDataSymbol!!)
                symbols.add(updatedSymbol)
                currentDataSymbol = null
            }
            
            // 查找变量定义
            varPattern.find(line)?.let { match ->
                val variableName = match.groupValues[1]
                val dataType = match.groupValues.getOrNull(2)
                // 计算变量名在该行中的位置
                val startCol = match.range.start + match.value.indexOf(variableName)
                val range = Range(
                    Position(lineIndex, startCol),
                    Position(lineIndex, startCol + variableName.length)
                )
                val symbol = SymbolInfo(variableName, range, "variable", dataType)
                
                if (currentDataSymbol != null) {
                    // 是数据模板的成员，添加到数据模板的成员列表
                    dataMembers[currentDataSymbol!!.name]?.add(symbol)
                } else {
                    // 是全局变量，添加到符号表
                    symbols.add(symbol)
                }
            }
            
            // 查找函数定义
            funcPattern.find(line)?.let { match ->
                val functionName = match.groupValues[1]
                // 计算函数名在该行中的位置
                val startCol = match.range.start + match.value.indexOf(functionName)
                val range = Range(
                    Position(lineIndex, startCol),
                    Position(lineIndex, startCol + functionName.length)
                )
                val symbol = SymbolInfo(functionName, range, "function")
                
                if (currentDataSymbol != null) {
                    // 是数据模板的成员函数，添加到数据模板的成员列表
                    dataMembers[currentDataSymbol!!.name]?.add(symbol)
                } else {
                    // 是全局函数，添加到符号表
                    symbols.add(symbol)
                }
            }
        }
        
        // 更新符号表映射
        symbolTable[uri] = symbols
        
        // 发送诊断信息到客户端
        server.getClient().publishDiagnostics(PublishDiagnosticsParams(uri, diagnostics))
    }
    
    /**
     * 代码补全请求处理
     * 当客户端触发补全请求时调用，如用户输入到一半按 Ctrl+Space
     * @param params 补全请求参数，包含文档URI、当前位置和上下文信息
     * @return 补全项列表，包含关键字、变量和函数
     */
    override fun completion(params: CompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>> {
        val uri = params.textDocument.uri
        val text = documents[uri] ?: return CompletableFuture.completedFuture(Either.forRight(CompletionList(false, emptyList())))
        val position = params.position
        val symbols = symbolTable[uri] ?: emptyList()
        
        // 分析当前位置，检查是否是在点号后进行补全
        val lines = text.lines()
        val currentLine = if (position.line < lines.size) lines[position.line] else ""
        val prefix = currentLine.substring(0, position.character)
        
        // 检查是否是成员访问补全（在点号后）
        val memberAccessMatch = Regex("(\\w+)\\.\\w*$").find(prefix)
        
        if (memberAccessMatch != null) {
            // 是成员访问补全，获取变量名
            val variableName = memberAccessMatch.groupValues[1]
            // 查找变量的类型
            val variableSymbol = symbols.find { it.name == variableName && it.type == "variable" }
            
            if (variableSymbol?.dataType != null) {
                // 查找数据模板的成员
                val dataSymbol = symbols.find { it.name == variableSymbol.dataType && it.type == "data" }
                if (dataSymbol != null) {
                    // 返回数据模板的成员补全项
                    val memberItems = dataSymbol.members.map { member ->
                        CompletionItem(member.name).apply {
                            kind = when (member.type) {
                                "variable" -> CompletionItemKind.Field
                                "function" -> CompletionItemKind.Method
                                else -> CompletionItemKind.Property
                            }
                            detail = member.type.replaceFirstChar { it.titlecase() }
                        }
                    }
                    return CompletableFuture.completedFuture(Either.forRight(CompletionList(false, memberItems)))
                }
            }
        }
        
        // 构建关键字补全项列表
        val keywordItems = listOf(
            CompletionItem("var").apply { 
                kind = CompletionItemKind.Keyword
                detail = "Variable declaration"
            },
            CompletionItem("data").apply { 
                kind = CompletionItemKind.Keyword
                detail = "Data template declaration"
            },
            CompletionItem("as").apply { 
                kind = CompletionItemKind.Keyword
                detail = "Type annotation"
            },
            CompletionItem("if").apply { 
                kind = CompletionItemKind.Keyword
                detail = "If statement"
            },
            CompletionItem("else").apply { 
                kind = CompletionItemKind.Keyword
                detail = "Else clause"
            },
            CompletionItem("while").apply { 
                kind = CompletionItemKind.Keyword
                detail = "While loop"
            },
            CompletionItem("do").apply { 
                kind = CompletionItemKind.Keyword
                detail = "Do-while loop"
            },
            CompletionItem("namespace").apply { 
                kind = CompletionItemKind.Keyword
                detail = "Namespace declaration"
            },
            CompletionItem("import").apply { 
                kind = CompletionItemKind.Keyword
                detail = "Import declaration"
            },
            CompletionItem("print").apply { 
                kind = CompletionItemKind.Function
                detail = "Print function"
            },
            CompletionItem("func").apply { 
                kind = CompletionItemKind.Keyword
                detail = "Function declaration"
            },
            CompletionItem("int").apply { 
                kind = CompletionItemKind.Keyword
                detail = "Integer type"
            },
            CompletionItem("string").apply { 
                kind = CompletionItemKind.Keyword
                detail = "String type"
            },
            CompletionItem("bool").apply { 
                kind = CompletionItemKind.Keyword
                detail = "Boolean type"
            },
            CompletionItem("true").apply { 
                kind = CompletionItemKind.Constant
                detail = "Boolean true"
            },
            CompletionItem("false").apply { 
                kind = CompletionItemKind.Constant
                detail = "Boolean false"
            }
        )
        
        // 从符号表中构建变量补全项列表
        val variableItems = symbols.filter { it.type == "variable" }.map { symbol ->
            CompletionItem(symbol.name).apply { 
                kind = CompletionItemKind.Variable
                detail = "Variable"
            }
        }
        
        // 从符号表中构建函数补全项列表
        val functionItems = symbols.filter { it.type == "function" }.map { symbol ->
            CompletionItem(symbol.name).apply { 
                kind = CompletionItemKind.Function
                detail = "Function"
            }
        }
        
        // 从符号表中构建数据模板补全项列表
        val dataItems = symbols.filter { it.type == "data" }.map { symbol ->
            CompletionItem(symbol.name).apply { 
                kind = CompletionItemKind.Class
                detail = "Data template"
            }
        }
        
        // 合并所有补全项
        val allItems = keywordItems + variableItems + functionItems + dataItems
        
        // 返回补全结果，使用 CompletionList 包装
        return CompletableFuture.completedFuture(Either.forRight(CompletionList(false, allItems)))
    }
    
    /**
     * 悬停信息请求处理
     * 当用户将鼠标悬停在代码上时调用
     * @param params 悬停请求参数，包含文档URI和当前位置
     * @return 悬停信息，返回当前行的文本内容
     */
    override fun hover(params: HoverParams): CompletableFuture<Hover> {
        val uri = params.textDocument.uri
        val text = documents[uri] ?: return CompletableFuture.completedFuture(null)
        val position = params.position
        
        // 获取当前行的文本内容
        val lines = text.lines()
        if (position.line < lines.size) {
            val line = lines[position.line]
            val content = MarkupContent(MarkupKind.PLAINTEXT, "Line: $line")
            return CompletableFuture.completedFuture(Hover(content))
        }
        
        return CompletableFuture.completedFuture(null)
    }
    
    /**
     * 定义跳转请求处理
     * 当用户按下F12或右键选择"转到定义"时调用
     * @param params 定义跳转参数，包含文档URI和当前位置
     * @return 定义位置列表，从符号表中查找匹配的符号定义
     */
    override fun definition(params: DefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        val uri = params.textDocument.uri
        val position = params.position
        val text = documents[uri] ?: return CompletableFuture.completedFuture(Either.forLeft(emptyList()))
        
        // 获取当前行内容
        val lines = text.lines()
        if (position.line >= lines.size) {
            return CompletableFuture.completedFuture(Either.forLeft(emptyList()))
        }
        
        val line = lines[position.line]
        
        // 查找当前位置的标识符
        var endPos = position.character
        var startPos = position.character
        
        // 向前查找标识符开始位置，处理字母、数字和下划线
        while (startPos > 0 && (line[startPos - 1].isLetterOrDigit() || line[startPos - 1] == '_')) {
            startPos--
        }
        
        // 向后查找标识符结束位置，处理字母、数字和下划线
        while (endPos < line.length && (line[endPos].isLetterOrDigit() || line[endPos] == '_')) {
            endPos++
        }
        
        // 提取标识符
        val identifier = line.substring(startPos, endPos)
        if (identifier.isEmpty()) {
            return CompletableFuture.completedFuture(Either.forLeft(emptyList()))
        }
        
        // 检查是否是成员访问（例如：person.name）
        val memberAccessMatch = Regex("(\\w+)\\." + Regex.escape(identifier) + "$").find(line.substring(0, endPos))
        
        val symbols = symbolTable[uri] ?: emptyList()
        val locations = mutableListOf<Location>()
        
        if (memberAccessMatch != null) {
            // 是成员访问，获取变量名
            val variableName = memberAccessMatch.groupValues[1]
            // 查找变量的类型
            val variableSymbol = symbols.find { it.name == variableName && it.type == "variable" }
            
            if (variableSymbol?.dataType != null) {
                // 查找数据模板
                val dataSymbol = symbols.find { it.name == variableSymbol.dataType && it.type == "data" }
                if (dataSymbol != null) {
                    // 在数据模板的成员中查找
                    dataSymbol.members.forEach { member ->
                        if (member.name == identifier) {
                            // 计算成员在数据模板中的实际行号
                            // 注意：这里简化处理，实际应该从数据模板定义开始行计算
                            // 由于我们没有存储成员的实际位置，这里使用数据模板的位置作为近似
                            locations.add(Location(uri, member.range))
                        }
                    }
                }
            }
        } else {
            // 不是成员访问，直接在符号表中查找
            symbols.forEach { symbol ->
                if (symbol.name == identifier) {
                    locations.add(Location(uri, symbol.range))
                }
                // 同时检查数据模板的成员中是否有匹配的
                if (symbol.type == "data") {
                    symbol.members.forEach { member ->
                        if (member.name == identifier) {
                            locations.add(Location(uri, member.range))
                        }
                    }
                }
            }
        }
        
        return CompletableFuture.completedFuture(Either.forLeft(locations))
    }
    
    override fun references(params: ReferenceParams): CompletableFuture<List<Location>> {
        return CompletableFuture.completedFuture(emptyList())
    }
    
    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> {
        return CompletableFuture.completedFuture(emptyList())
    }
    
    // 其他未实现的方法返回默认值
    override fun declaration(params: DeclarationParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        return CompletableFuture.completedFuture(Either.forLeft(emptyList()))
    }
    
    override fun typeDefinition(params: TypeDefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        return CompletableFuture.completedFuture(Either.forLeft(emptyList()))
    }
    
    override fun implementation(params: ImplementationParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        return CompletableFuture.completedFuture(Either.forLeft(emptyList()))
    }
    
    override fun documentHighlight(params: DocumentHighlightParams): CompletableFuture<List<DocumentHighlight>> {
        return CompletableFuture.completedFuture(emptyList())
    }
    
    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> {
        return CompletableFuture.completedFuture(emptyList())
    }
    
    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit> {
        return CompletableFuture.completedFuture(WorkspaceEdit())
    }
    
    override fun selectionRange(params: SelectionRangeParams): CompletableFuture<List<SelectionRange>> {
        return CompletableFuture.completedFuture(emptyList())
    }
    
    override fun linkedEditingRange(params: LinkedEditingRangeParams): CompletableFuture<LinkedEditingRanges> {
        return CompletableFuture.completedFuture(null)
    }
}
