# MCFPP IDEA 0.5.0 验收记录

日期：2026-11-01。Windows 11；开发 SDK 基线 IDEA 2025.3.3；实际界面验收 IDEA 2026.2.2（IU-262.10315.125），使用独立配置、缓存和插件目录。验收依据当前工作区源码及未提交修改。

安装包：[mcfpp-intellij-0.5.0.zip](build/distributions/mcfpp-intellij-0.5.0.zip)。SHA-256：

```text
9C437D318A375533A99FC1F6662BB6791E454148CBFF2B226E84225A659BCEAD
```

内置 CLI 0.1.0、Prettier 3.9.10、Linter 0.1.0。CLI、LSP 和格式化工具的指纹见安装包内 `toolchain/toolchain.json`；LSP 构建使用的编译器 SHA-256 与内置 CLI 一致。

## 已完成的实际界面验收

- 从 MCFPP / MCFPP MNI 原生向导创建独立工程，JDK 21 自动填充，入口源码自动打开。创建时两类模板的全部文件与同名 CLI `init` / `init --mni` 结果逐文件比较一致。
- 普通工程同步、默认 Build、产物生成成功；已有 CLI 工程也能直接打开并初始化。
- MNI 工程导入 Java 源码、SDK 和 JUnit 依赖；Java provider 能正常编辑，宏调用能够编译。重复打开没有重新创建 SDK 的异常。
- 宏补全显示参数和返回类型，选择后插入调用括号；Ctrl+B 跳到 Java 方法。修改 Java 宏 identifier 后，无需构建，新名称即进入补全和诊断。原生与 LSP 的同一宏补全已去重。
- 保存自动整理、手动整文件格式化、Ctrl+Z 撤销已操作验证。工具窗口显示工具版本和任务，打开底部 Run 窗口后仍可查看依赖，其他操作可通过滚动访问。
- 通过工具窗口添加 Java 依赖，自动同步；移除后依赖声明及列表同步恢复。
- MNI Test 成功；Run 输出显示 `1 passed, 0 failed, 0 skipped`、缓存状态及 HTML 报告链接。
- 构建未知函数调用，Run 输出中的错误文件和源码行可点击；实际从 Java 编辑页跳回 MCFPP 错误行。恢复源码后使用 Run 的重新执行入口再次构建成功。
- Open Build Output 实际打开工程输出目录，能够查看数据包和 `bin.mclib`。

向导创建的工程保存在 `build/acceptance-projects/wizard-simple-final` 和 `build/acceptance-projects/wizard-mni`；比较用 CLI 工程在 `build/acceptance-cli`。可交付的干净示例为 [普通工程](../examples/idea-cli-simple) 和 [MNI 工程](../examples/idea-cli-mni)。

界面截图包括 [宏补全](build/delivery-macro-completion.png)、[测试结果](build/final-test-result.png)、[错误跳转](build/delivery-error-navigated.png)、[重新构建成功](build/delivery-rerun-success.png) 和 [产物入口](build/delivery-output-directory.png)。

## 自动检查及证据适用范围

早前已执行完整插件、LSP 和 Linter 套件，以及 CLI 普通/MNI 工程的创建、模型导出、构建和 Java 测试。最后的局部修复只重跑受影响检查，保留其余未受影响的已有证据：

| 检查 | 结果与本地日志 |
| --- | --- |
| CLI 项目模型 JSON、locked/offline | 通过，`../../MCFPP/build/ide-model-test.log` |
| 插件完整集成检查（已有证据） | 通过，`build/final-integration.log` |
| SDK、向导、工程、宏及源码符号回归 | 通过，`build/delivery-regression.log` |
| 宏补全去重与不同重载保留 | 通过，`build/macro-completion-regression.log` |
| 带时间戳的编译错误定位与导航 | 通过，`build/error-navigation-regression.log` |
| LSP 项目命名空间、宏通知及服务回归 | 通过，`../language-server/build/mni-namespace-regression.log` |
| CLI 解析库进入/移出编辑器索引 | 通过，`../language-server/build/resolved-library-regression.log` |
| 最终 ZIP 的 Plugin Verifier | 2025.3.3、2026.2.2 均 Compatible，`build/verifier-delivery.log` |

Verifier 仍报告废弃、实验性及内部 API 使用提示，没有兼容性错误。后续 SDK 升级需要继续检查这些接口。

## 尚未逐项手工覆盖

复杂语法的全部编辑交互以回归测试为主要证据，未将每种组合都在 GUI 中重演。远程 MCFPP 包仓库的添加/移除全流程、工具缺失后的配置修复、网络故障及取消任务后重试，也未全部手工模拟；已执行的依赖界面验收针对 Java 依赖，MCFPP 解析库的同步由针对性索引回归验证。

验收时本机 IDEA 原生启动器带有额外 VM 配置，沙盒使用安装目录自带 JBR 和官方启动参数直接启动。未修改用户日常 IDEA 的配置。
