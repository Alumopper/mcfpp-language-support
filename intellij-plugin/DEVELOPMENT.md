# 在 IDEA 中完成 MCFPP 开发

## 安装与工具

在 **Settings → Plugins → 齿轮 → Install Plugin from Disk** 中选择 `build/distributions/mcfpp-intellij-0.6.0.zip`，重启 IDEA。

本次修复、实际检查和安装包指纹见 [0.5.1 开发体验修复](FEEDBACK-0.5.1.md)；此前的完整流程记录见 [0.5.0 验收记录](ACCEPTANCE.md)。

0.5.2 修复独立 CLI 的 Kotlin 反射依赖，并随插件附带编译器 Java/Kotlin 实现源码。External Libraries 中可浏览源码；MNI 工程同步后自动为编译器依赖附加源码，转到声明可查看底层实现，无需本地编译器仓库。独立 CLI 发行目录另含配套的 `*-sources.jar`。

0.6.0 接通原版数据包与资源包工程：`dataPath` 和 `assetsPath` 中的内容随同一次构建输出。`[entrypoints]` 将稳定的原版函数 ID 映射到公开无参数 void MCFPP 函数。命令和 JSON 中的常用资源字段支持按类型补全、跳转、缺失提示和资源文件重命名更新；入口跳转进入 MCFPP 实现，声明重命名更新 TOML 目标并保留公开 ID。资源包附带模型、物品定义、blockstate、字体和声音的基础 schema。

开发基线为 IDEA 2025.3.3；LSP 功能需要带 `com.intellij.modules.lsp` 的 JetBrains IDEA 发行版，不支持开源构建和 Android Studio。JDK 25 用于 CLI 和 Java MNI，Node.js 20 或更新版本用于格式化与 Linter。Minecraft 命令服务使用 Java 25。插件携带 CLI、标准库源码、LSP、Prettier、Linter 和命令服务，运行时不需要编译器源码仓库或全局 npm 包。

打开 **Settings → Tools → MCFPP**。通常自动发现项目 SDK、已登记的 JDK 和 PATH 中的 Node；也可指定 JDK 25、Node、命令服务 Java、外部 CLI JAR、Prettier 包目录和 Linter 包目录。默认使用内置工具。安装包内 `toolchain/toolchain.json` 记录版本及 CLI、LSP、格式化工具的 SHA-256；构建时校验 CLI 与 LSP 使用同一编译器。

## 创建工程

1. **File → New → Project → MCFPP**，输入名称、目录和 JDK 25。创建过程调用内置 `mcfpp init .`。
2. Java 原生函数工程选择 **MCFPP MNI**，创建过程调用 `mcfpp init . --mni`，包含 `@MNIFunction` provider、显式 MNI 函数声明和 JUnit 示例。
3. 等待后台同步，入口 `src/main/mcfpp/main.mcfpp` 自动打开。

直接 **File → Open** 打开含 `mcfpp.toml` 的现有 CLI 工程，也会创建运行配置并同步。工程根目录下的 `.mcfpp/` 是 CLI 缓存。

## 编辑、保存与修复

整文件 **Code → Reformat Code** 与保存共用后台 Node 服务：Linter 安全修复 → Prettier → 重新诊断。项目的 Linter 配置、忽略规则、Prettier 配置、`.prettierignore` 和 `.editorconfig` 会被重新读取。默认四空格、100 列并保留换行方式。局部格式化和输入缩进使用原生格式化器。

保存结果仅在文档版本仍一致时写入；连续输入产生的过期结果会被丢弃。格式化可撤销，语法错误或工具失败时保留原文。安全快速修复可从问题位置的 **Alt+Enter** 应用。保存格式化、安全修复和实时 Linter 可分别在 MCFPP 设置中关闭。

行尾空格、混合缩进和文件末尾换行属于格式化事项，不生成编辑器警告；格式化时统一整理。语法错误和有实际含义的 Linter 问题继续显示。

语言功能包括补全、参数提示、文档、声明导航、引用与重命名、结构视图和版本分支。普通 MNI 函数使用 `func answer(): int = example.mni.Example.answer` 完整声明，Java 侧使用 `@MNIFunction`；支持 Java 目标补全、参数和返回类型检查及导航。高级宏扩展的 Java 签名变动也会同步给 LSP。

## 同步与依赖

右侧 **MCFPP** 工具窗口显示工程状态、内置工具版本、依赖、任务和产物入口。

- **Add…**：MCFPP 依赖使用 `group:name@version-range`；Java 依赖使用 `group:artifact:version`，选择所需 Gradle configuration。
- **Remove**：选择依赖并移除。
- **Synchronize**：重新执行 `ide-model --json`。配置文件及依赖文件保存后会自动同步；失败时保留上次成功的模型，可在此重试。

插件读取 CLI 返回的最终模型，设置源码、资源、输出、JDK，以及 MNI Gradle source set 的编译、运行、测试依赖。生成目录不需要作为独立 Gradle 工程手动导入。

## 构建、检查与测试

首次导入自动生成 **MCFPP Build、Install、Update、Clean、Test、Lint、Format Check**。默认选中 Build，重新打开工程保留已有配置和用户修改。

**MCFPP Dump IR** 在普通构建基础上开启 `--dump-ir`，已有工程也会补充此任务。控制台中的三个 IR 路径可点击打开：`build/ir/01-compiled.ir` 记录实际类型化 CFG 与已降级命令（直接降级的函数会明确注明），`02-optimized.ir` 记录运行时初始化及入口整理后的结果，`03-linked.ir` 记录资源保留、省略和引用来源。命令行可执行 `mcfpp build --dump-ir`，或用 `--dump-ir=目录` 指定相对构建输出根目录的位置。

从顶部运行按钮、Run 菜单或 MCFPP 工具窗口启动任务。输出出现在标准 Run 窗口，可以取消、重新执行，并跳转到错误位置。Build 和依赖变更在同一工程内串行执行。运行配置支持工程目录、额外 CLI 参数、环境变量、offline 和 locked；Update 与 locked 不能同时使用。

Build 成功后点击 **Data Pack** 查看数据包，点击 **Resource Pack** 查看配套资源包。标准库函数和资源按引用链链接进同一数据包；未使用的库对象不执行初始化。动态函数目标按可能匹配的函数族保守保留，并在 IR 链接报告中说明。Test 执行 Java MNI 测试和 `src/test/mcfpp` 下的 MCFPP 测试。MCFPP 使用 `import mcfpp.test:*`、`@Test`、`@BeforeEach`、`@AfterEach`、`@Disabled("原因")` 和 `assertEquals` / `assertTrue` 等断言；插件复用内置 DSB JAR 和命令服务的 Java 25 设置。函数名旁的运行按钮执行单例，MCFPP 结果显示在测试树中并支持源码跳转；运行配置可传 `--tests 'namespace:function*'`。JUnit XML 位于 `build/tests/mcfpp/TEST-mcfpp.xml`（自定义 `targetPath` 时随之变化）。Lint 和 Format Check 只检查文件，不写入源码。

## 可复现工程

- [普通工程](../examples/idea-cli-simple)：CLI 原始模板，运行 Build 后检查 `build/`。
- [Java MNI 工程](../examples/idea-cli-mni)：先同步，再运行 Test 和 Build；检查 Java 原生函数调用及数据包。

## 从源码构建

需要将 `MCFPP`、`MCFPP-Linter` 和本仓库放在同一父目录。先在 MCFPP 中使用 JDK 25 构建 CLI，再打包同版本 LSP 和插件：

```powershell
cd ../MCFPP
./gradlew.bat shadowJar
cd ../MCFPP-Linter
npm ci
cd ../mcfpp-language-support/language-server
./gradlew.bat test jar syncExtensionServer
cd ../intellij-plugin
./gradlew.bat test buildPlugin verifyPlugin -x buildSearchableOptions
```

非默认 CLI 制品可通过两次 Gradle 构建都传入 `-PmcfppCompilerJar=<absolute-jar>` 指定。语法文件必须与编译器一致，LSP 构建会检查差异。

本机 SDK 可用 `-PideaLocalPath=<IDEA目录>` 指定。使用 JDK 25 启动 Gradle（`JAVA_HOME` 指向 JDK 25）；这是默认构建工具链。插件宿主代码仍输出 Java 21 字节码以匹配 IDEA 基线，独立 CLI、LSP、调试适配器与 DSB 均使用所选 JDK 25。GUI 沙盒使用 `runIde --args="<工程绝对路径>"`。
