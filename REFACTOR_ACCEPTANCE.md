# MCFPP 后续重构验收记录

## M1 · 拆分 LSP 文档服务

验收日期为 2026-11-22。LSP 请求通过 `MCFPPTextDocumentService` 分发，文档生命周期统一管理异步调度和版本检查。处理器通过 `MCFPPWorkspaceView` 查询符号。完整初始化器和实参复用文档 AST，编辑中的局部片段仍支持文本推断。

| 文件 | 行数 |
| --- | ---: |
| `MCFPPTextDocumentService.kt` | 2,772 → 240 |
| `MCFPPDocumentLifecycle.kt` | 329 |
| `MCFPPCompletionHandler.kt` | 236 |
| `MCFPPHoverAndSignatureHandler.kt` | 203 |
| `MCFPPNavigationHandler.kt` | 166 |
| `MCFPPHierarchyAndRefactorHandler.kt` | 386 |
| `MCFPPDiagnosticsAndTokensHandler.kt` | 275 |
| `MCFPPWorkspaceView.kt` | 273 |
| `MCFPPTypeChecker.kt` | 1,291 → 1,287 |
| `MCFPPParserSemanticExtractor.kt` | 1,117 → 1,100 |

| 离线构建与全量测试 | 结果 |
| --- | --- |
| LSP `test shadowJar intellijServerJar syncExtensionServer` | 115/115，通过。零新增编译警告 |
| IDEA `test buildPlugin` | 262/262，通过。清单与内置 JAR 的 SHA-256 一致 |

| 产物 | 字节 | MiB |
| --- | ---: | ---: |
| 独立 LSP JAR | 30,313,027 | 28.91 |
| IDEA 用 LSP JAR | 10,812,136 | 10.31 |
| IDEA 0.6.3 ZIP | 94,318,389 | 89.95 |
| MCFPP CLI JAR（验收使用的基线） | 60,209,703 | 57.42 |

VS Code 同步产物与独立 LSP JAR 的 SHA-256 一致。LSP 入口类的字节码版本为 69，IDEA 安装包 `lib/` 下的基础类最高为 65。IDEA 用 LSP JAR 内的 `bin.mclib` 条目数为 0。Gradle 日志位于两个子工程各自的 `build/m1-acceptance.log`。

验收工作区原有的源码和构建基线分别保存为 MCFPP `4e91b194` 和语言工具 `728c295`，M1 变更独立提交于本地 `codex/remaining-refactor`。

## M2 · 编译器 Gradle 多模块与 Composite Build

`mcfpp-syntax` 从 `MCFPP/src/main/antlr` 的 4 个文法文件生成解析器，Java 预处理器为 201 行。全工作区的 ANTLR 生成任务为 `:mcfpp-syntax:generateGrammarSource`。`mcfpp-library-model` 包含 27 个 Kotlin 文件，共 4,445 行。库的导入计划由编译器会话持有。

LSP 解析语法与库模型依赖。IDEA 宿主依赖 Java 21 的语法模块，CLI 和 LSP 作为子进程 JAR 打包。CLI、编译器源码、标准库源码和编辑器元数据通过 Gradle 变体传递。CLI 与独立 LSP 各包含 47 个标准库索引，IDEA 用 LSP 含 0 个。IDEA 安装包中的 CLI、LSP 与清单的 SHA-256 一致，VS Code 同步 JAR 与独立 LSP 一致。

| 离线构建与全量测试 | 结果 |
| --- | --- |
| MCFPP `shadowJar editorRuntimeJar stdlibIndexJar sourcesJar verifyDistribution test` | 2,649 项，2,648 通过、0 失败、1 跳过。发行包烟雾构建报告 0 个错误、0 条警告 |
| 库模型 `:mcfpp-library-model:test` | 9/9，通过，复用输入相同的此前结果 |
| LSP `test shadowJar intellijServerJar syncExtensionServer` | 115/115，通过，两个 JAR 均完成标准输入输出握手和关闭 |
| IDEA `test buildPlugin` | 262/262，通过，联合构建包含 CLI、语法模块、库模型与 IDEA 用 LSP |

| 产物 | 字节 | MiB |
| --- | ---: | ---: |
| MCFPP CLI JAR | 60,215,523 | 57.43 |
| 语法模块 JAR | 382,478 | 0.36 |
| 库模型模块 JAR | 747,713 | 0.71 |
| 编辑器兼容 JAR | 1,710,510 | 1.63 |
| 标准库索引 JAR | 19,531,587 | 18.63 |
| 标准库源码 JAR | 98,021 | 0.09 |
| 编译器源码 JAR | 1,655,922 | 1.58 |
| 独立 LSP JAR | 30,342,756 | 28.94 |
| IDEA 用 LSP JAR | 10,809,910 | 10.31 |
| IDEA 0.6.3 ZIP | 79,320,125 | 75.65 |

编译器和 LSP 入口类以及库模型字节码版本为 69，解析器为 65，IDEA 安装包 `lib/` 下的基础类最高为 65。IDEA ZIP 较 M1 缩小 14.30 MiB，瘦版 LSP 维持在 11 MiB 以下。

编译器首轮全量测试报告 72 个失败。修复了外部声明的存储写入被删、编译期引用读错存储根等问题，恢复了 DSB 断言消息。47 个标准库索引在干净检出中依据实际入库资源重新生成。真实 DSB 集成测试使用 26.2 并通过。用户先前延期的嵌套 Lambda `REF` 转发用例仍由原有 `@Ignore` 跳过。

构建日志分别位于 MCFPP 的 `build/m2-compiler-acceptance.log`、LSP 的 `build/m2-lsp-acceptance.log` 和 IDEA 的 `build/m2-intellij-final.log`。

编译器提交为 `27cd14c9`，两个源码仓库使用本地 `codex/remaining-refactor` 分支。
