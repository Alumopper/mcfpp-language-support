# 0.5.1 开发体验修复

- IDEA 实时 Linter 不再把行尾空格、混合缩进和缺失末尾换行显示为警告；这些内容继续由格式化整理。
- 在语句后的 `}` 左侧按 Enter，会按对应 `{` 所在行对齐，支持嵌套块并忽略字符串中的括号。
- CLI 与 IDEA 的新 MNI 工程使用 `@MNIFunction` 和完整声明：

```mcfpp
func answer(): int = example.mni.Example.answer

func main() {
    print(answer())
}
```

模板不再生成内部 `injectedBy` 文件。Java 方法通过 `ValueWrapper<MCInt>` 返回一个简单整数，另附普通 JUnit 测试。已有工程的源码保持原样，可参考更新后的 [MNI 示例](../examples/idea-cli-mni)。

- 数据包统一链接所需标准库函数和资源，沿函数调用、函数标签和资源引用传递；用户源码函数与显式资源保留。动态目标保守保留对应函数族。数学库、临时实体和库单例初始化按实际使用启用，旧的 `Imports` 数据包输出不再生成。
- 工具窗口与原生运行配置增加 **Dump IR**，对应 `mcfpp build --dump-ir`。三个文本文件保存在 `build/ir`，包含类型化基本块、指令、降级命令和最终链接理由；控制台路径可点击。
- 控制台按消息级别显示真实错误，正常 stderr 进度使用普通文字，并消除 ANSI 黑色对 IDEA 深色主题的覆盖。

使用新的安装 ZIP 更新插件并重启 IDEA。CLI、LSP 与格式化服务一起随插件更新，版本指纹由构建校验。

## 实际检查（2026-11-01）

- 编辑器服务 5 项、独立 Linter 55 项检查通过；插件的输入缩进、MNI 绑定、运行任务和控制台回归通过。
- CLI 新建 MNI 工程后，同步、Java 编译、MCFPP 构建及 JUnit 测试通过。编译器定向检查覆盖资源引用链、标签合并、动态目标保留、运行时初始化和 IR 输出；已修复检查中发现的浮点运行时初始化缺失。
- 最终 ZIP 在 IDEA 2026.2.2 独立沙盒中运行：重现用户的 Enter 输入、空白诊断及保存整理；执行 Dump IR 并打开链接报告；故意调用不存在的函数，确认真实错误为红色、正常进度清晰可读，再恢复模板并构建。
- 最小普通工程的数据包为 8 个文件（含 `pack.mcmeta`），没有整套数学库和独立 `Imports` 包。Minecraft 游戏内运行留待后续开发测试。
- Plugin Verifier 对 IDEA 2025.3.3、2026.2.2 均报告 Compatible；仍有 API 弃用、内部和实验接口使用提示。
- 本机 IDEA 2026.2.2 已正常重启并加载 MCFPP Language 0.5.1；原 Simple 工程同步为 Ready，工具窗口显示 Dump IR。旧版插件目录保留以便回退。

安装包：`build/distributions/mcfpp-intellij-0.5.1.zip`。

SHA-256：`D2F3BBB56A2FAEB19EDCF4EC5F2CC2E55155B51B4EBA0BFC967E90352D0C5A74`。

检查日志位于插件 `build/feedback-release.log`、`build/verifier-feedback.log`、`build/whitespace-feedback-regression.log`、`build/linter-cli-compatibility.log`，编译器 `build/final-linker-feedback-regression.log` 与 `build/float-bootstrap-feedback-regression.log`；界面截图位于插件 `build/feedback-*.png`。
