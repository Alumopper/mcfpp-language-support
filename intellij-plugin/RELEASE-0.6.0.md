# 0.6.0 — 原版资源工程集成

统一构建 MCFPP、dataPath 原版数据和 assetsPath 客户端资源。编译器增加显式 `[entrypoints]`、独立资源包元数据、标签合并、资源冲突及本地引用检查，并在检查成功后替换两份输出。

IDEA 将 data/assets 导入为资源目录，提供双包输出入口。命令和 JSON 使用共享的资源类型与引用规则，支持补全、导航、资源文件重命名、回调重命名时更新 TOML，以及本地缺失和冲突提示。增加模型、物品定义、blockstate、字体和声音的基础 schema。

## 验证

- 编译器：VanillaProjectIntegrationTest、DatapackExperienceTest、IdeProjectModelTest 共 6 项通过；独立发行 verifyDistribution 通过。
- 插件：PackResourceIntegrationTest、MinecraftDatapackJsonIntegrationTest、MinecraftFunctionIntegrationTest、McfppCompilerSourcesIntegrationTest 共 17 项通过。原生 PSI 检查使用先前 CLI 快照；更新最终 CLI/LSP 后，另行重跑源码导航检查通过。
- 仓库外示例独立 CLI 构建通过；资源包严格校验通过，0 警告。
- IDEA 2026.2.2 实际打开示例，确认双包按钮、入口及物品模型的目标定位，通过插件 Build 生成两份包。
- 文档站点构建通过。

Plugin Verifier 对 2025.3.3 判定 Compatible；verifyPlugin 任务因原有 3 处内部 API 使用返回失败，与 0.5.2 数量相同。未放宽验证规则。联网读取 JetBrains API 变更列表超时。未进行真实 Minecraft 游戏验收。

资源字段识别范围和使用方式见 DEVELOPMENT.md 及编译器 docs/vanilla-resources.md。未执行完整测试套件。
