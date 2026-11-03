# MCFPP IDEA 0.5.2

日期：2026-11-02。

独立 CLI 的 Kotlin 反射实现不再被 Shadow 最小化移除。发行 JAR 可直接编译 `nbtStorageRef<int>` 及同步读写回调，无需手工增加 classpath。编译器增加 `verifyDistribution` 检查，使用实际发行 JAR 编译该场景。

插件随包附带编译器 Java/Kotlin 实现源码，并为 MNI 工程中的编译器依赖附加 Sources 根。普通工程可从 External Libraries 浏览源码库。此入口不依赖本机存在编译器仓库。编译器同时生成配套的 `*-sources.jar`，完整 CLI 目录和 Maven publication 均包含它。

本机已安装插件切换至 0.5.2，原 0.5.1 目录保留。启动 IDEA 后重新同步已有工程；如配置了外部 CLI，需要自行选择匹配的发行物。

验证范围：

- `shadowJar sourcesJar installDist` 成功。
- `verifyDistribution` 使用独立 JAR 编译泛型 NBT 示例成功。
- 独立 CLI 新建并构建 Java MNI 示例成功。
- 源码库、MNI 引用与源码导航定向检查分批累计 10 项通过，其中 9 项复用前一批结果；未重跑全套。
- IDEA 2026.2.2 独立沙盒中，在编译器仓库之外打开 MNI 工程，实际从 `Example.java` 导航至 `MCIntData.java`，再导航至 `NativeTextOperations.kt`。两种实现源码均来自插件内的 `compiler-sources`。
- Plugin Verifier 对 IDEA 2025.3.3 判定 Compatible。Gradle `verifyPlugin` 仍因 3 处内部 API 使用返回失败；相同 3 项在 0.5.1 的既有报告中已存在。没有修改该检查的失败规则。工具另报告无法联网读取 JetBrains 的已记录 API 变更列表。
- 未执行真实 Minecraft 游戏验收。

安装 ZIP SHA-256：`FCA9825F8585784F4087F25839F4A5A97B3AD978B3847D5E10FE877695D24CDE`。

CLI SHA-256：`D23E9379422938FB1C0051F3DECC8EC7BE5531EA386F3DFBE2D22DEEF13FE551`。

源码 JAR SHA-256：`5B9A2044B7B1F2F0724C9E0CE315D5C2559D130F676F55D07D68A6EB27620B3E`。

本轮日志保存在 Codex 工作目录的 `work/distribution-build.log`、`work/distribution-smoke.log`、`work/source-nav-mni-build.log`、`work/plugin-sources-build.log`、`work/plugin-navigation-final.log` 和 `work/plugin-verifier.log`。`plugin-sources-build.log` 保留首批新增测试的失败，最终该测试的通过结果在 `plugin-navigation-final.log`，不将早期失败描述为整批成功。
