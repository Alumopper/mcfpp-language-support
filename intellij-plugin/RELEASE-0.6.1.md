# 0.6.1 — 与当前编译器语法和 MNI 契约同步

本版同步了 MCFPP 最新的命令块、实体模板、上下文入口和 Kotlin MNI 声明契约。

## 语言支持

- 同步编译器 ANTLR 语法：命令块 `/{ ... }`、`entity` 模板、`entity var`/`entity func`、选择器尾随回调、成员关键字、构造函数委托和 NBT 键。
- 实体模板把模板成员与 `EntityRef<T>` 成员分开补全；`summon()` 和 `entityByUUID<T>` 推断引用类型，`this` 在实体成员函数中按 `EntityRef<T>` 解析。
- 增加 `forEach`/`forEachAt` 实体上下文入口。
- 命令块在 LSP 中参与解析、`${...}` 引用和格式化；IDEA 原生词法、命令补全、着色、折叠和花括号匹配识别命令块行。

## MNI

- 宏由显式 `func ... = Provider.method` 声明发现，不再读取 `injectedBy`。
- `@MNIMacro(normalParams, returnType)` 取代 `params`；参数模式从 `Value`/`Ref`/`Const`/`InlineFn` JVM 包装推导。
- 宏绑定检查、补全、行标记和快照使用新注解形状；Kotlin 顶层函数、`object` 和 companion 的 `@JvmStatic` 入口走同一 provider 索引。

## 验证

- 语言服务器：93 项测试通过，包含命令块、实体模板、上下文和标准库重载用例。
- IDEA 定向测试（Windows，IDEA 2026.2.2）：McfppLexerTest、McfppBraceMatcherTest、McfppEntityEditorTest、MniFunctionSignatureTest、McfppMacroIntegrationTest 共 19 项通过。
- IDEA 完整测试：212 项中 209 项通过。3 项失败均来自本机测试环境：两项是 Windows 短路径临时目录未加入 VFS 允许根，另一项是 `CodeFoldingManager.buildInitialFoldings` 在测试线程返回 null；对应的折叠描述符已单独验证为 1。
- 安装包已重新生成：`build/distributions/mcfpp-intellij-0.6.1.zip`，SHA-256 `a064469c6cb2de6cdfe6b8a8725567093d9553f2f4f9b997ed7b75246f2d2406`。
- Plugin Verifier 未在本轮重新执行。
