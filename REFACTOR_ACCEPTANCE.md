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

## M3 · 编译会话隔离与保存点

编译器的项目、符号表、设置、当前文件、函数和模板归各自的 `CompilerState` 管理。并发测试在两个线程编译同一命名空间中的不同项目，并逐项验证生成指令。嵌套会话阻止恢复其他会话的保存点。Playground 跨线程发起的请求可以重复恢复同一份标准库基线。

JUnit 扩展为每个测试绑定编译状态。测试使用 `forkEvery = 0`，本机观测到 8 个并行测试进程，每个进程的最大堆为 1 GiB。签名准备会将同一批声明合并到一个域快照，避免重复保留标准库声明图。

| 保存点实现 | 原行数 | 当前行数 |
| --- | ---: | ---: |
| `mni/expand/*Journal*.kt` | 3,017（7 个文件） | 0 |
| 包含编译器拥有的保存点、集合恢复、身份验证和事务准备 | 3,017 | 2,712 |

`CompilerState.checkpoint()` 按对象身份一次捕获声明和命名空间，统一处理已脱离注册表的文件、宏和处理器。函数、对象和声明共用已捕获的关系及存储快照。哈希键的依赖在恢复符号表前还原，宏诊断在提交后只发布一次。

计划列出 335 个测试类，当前 Gradle 根项目报告实际发现 322 个类。测试源码中带有 `@Test` 的文件也是 322 个。三轮均保留用户先前延期的嵌套 Lambda `REF` 转发用例上的原有 `@Ignore`。

| 离线构建与全量测试 | 结果 |
| --- | --- |
| 编译器第 1 轮 | 23 分 53 秒，2,649 通过、0 失败、1 跳过 |
| 编译器第 2 轮 | 22 分 41 秒，2,649 通过、0 失败、1 跳过 |
| 编译器第 3 轮 | 21 分 46 秒，2,649 通过、0 失败、1 跳过 |
| `verifyDistribution` | 烟雾项目编译报告 0 个错误、0 条警告 |
| 库模型 | 9/9，输入相同，复用此前报告 |
| LSP `test shadowJar intellijServerJar syncExtensionServer` | 115/115，独立版与 IDEA 版均通过标准输入输出握手和退出 |
| IDEA `test buildPlugin` | 262/262，安装包已生成 |

LSP 验收发现首个语义高亮请求可能先于延迟的项目扫描，导致宏被标记为普通 MNI 函数。修复后，符号通知立即发布索引并使语义缓存失效。回归测试也验证了项目扫描前已有最新符号，晚到的旧通知无法还原已清除的符号。

| 产物 | 字节 | MiB |
| --- | ---: | ---: |
| MCFPP CLI JAR | 60,243,700 | 57.45 |
| 语法模块 JAR | 382,478 | 0.36 |
| 库模型模块 JAR | 747,713 | 0.71 |
| 编辑器兼容 JAR | 1,710,540 | 1.63 |
| 标准库索引 JAR | 19,531,587 | 18.63 |
| 标准库源码 JAR | 98,021 | 0.09 |
| 编译器源码 JAR | 1,658,435 | 1.58 |
| 独立 LSP JAR | 30,344,811 | 28.94 |
| IDEA 用 LSP JAR | 10,811,965 | 10.31 |
| IDEA 0.6.3 ZIP | 79,346,252 | 75.67 |

IDEA 安装包中的 CLI 与 LSP 均匹配清单的 SHA-256。独立 LSP 和 VS Code 同步产物一致，IDEA 用 LSP 含 0 个 `bin.mclib`。IDEA 安装包 `lib/` 下的基础类最高为 65，解析器为 65，编译器、库模型和 LSP 入口类为 69。

提交前清理文件末尾空行后，三个类的调试行号映射发生变化。反汇编确认方法签名和指令相同，其余 CLI 条目一致。重新生成源码归档及相关工具包，并完成最终的发行包烟雾编译和 IDE 工具验证。

编译器三轮日志与统计位于 `build/m3-final-compiler-round-1` 至 `build/m3-final-compiler-round-3` 的 `.log` 和 `.json` 文件。LSP 和 IDEA 最后一轮日志分别位于两工程的 `build/m3-final-lsp-archive-refresh.log` 与 `build/m3-final-intellij-archive-refresh.log`。最终 SHA-256 和字节码检查见编译器的 `build/m3-package-evidence-final.log`。

编译器会话隔离提交为 `4d2dab17`，保存点提交为 `d5817f0e`。
