# 0.6.2 — 类型组合、工厂与原生函数高亮

- IDEA 和 LSP 同步当前编译器的结构对象类型、交叉/联合类型、`new` 工厂及 `?.`、`?:`、`!!`。
- 工厂提供参数提示、声明导航和返回字面量字段补全。`Point(...)` 与 `Point.new(...)` 推断相同结果类型，工厂内的局部变量保持正确作用域。
- data 继承组合字段，联合成员取所有分支共有字段。显式对象字面量接受额外字段，必须提供所有非可选字段；字段初值用于隐式工厂。支持字段简写。
- Macro 默认紫色加粗，普通 MNI 默认青绿色斜体。声明、调用、导入别名和 provider 方法绑定分别着色，普通函数及同名局部函数值保留自身样式。在 Editor → Color Scheme → MCFPP → Identifiers 中独立配置 Macro function / MNI function。
- LSP 通过宏符号快照和编译库元数据输出 `macro` / `mniFunction` 语义标记；VS Code 的语义图例和自定义类型同步更新。
- 内置格式化器保留可选操作符，整理 Elvis 两侧空格。CLI 与 LSP 通过 SHA-256 校验使用同一编译器。

可运行示例：[类型组合工程](../examples/type-composition)。使用当前 CLI 构建成功，生成数据包且没有编译错误或警告。

本轮使用本机 IntelliJ IDEA 2026.2.2 SDK 构建与运行定向测试，检查 `buildPlugin` / `verifyPluginStructure`，并直接安装到本机插件目录。未启动 `runIde`。

已执行的检查包括 LSP 的工厂、结构类型、可选操作符、示例诊断和语义标记用例；IDEA 的工厂、成员、模板字面量、词法、宏链接及原生函数高亮用例；格式化服务 6 项测试、Linter 词法/Prettier 31 项测试及 VS Code TypeScript 编译。后续新增修复只重跑对应定向用例。

最终安装后在本机 IDEA 打开示例，编辑器显示绿色检查标记，无错误诊断。默认高亮的不同前景色、Macro 加粗和 MNI 斜体通过 IDEA 颜色方案测试；颜色设置页的交互预览尚未点验。已校验安装的 CLI/LSP 指纹与构建产物一致。

安装包：`build/distributions/mcfpp-intellij-0.6.2.zip`。SHA-256：`db87a8f4063f9c26a32d6253bb03296783f9a9862780c9738b08a687e2d26285`。
