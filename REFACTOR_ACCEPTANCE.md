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
