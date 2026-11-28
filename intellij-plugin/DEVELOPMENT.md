# 在 IDEA 中完成 MCFPP 开发

## 安装与工具

在 **Settings → Plugins → 齿轮 → Install Plugin from Disk** 中选择 `build/distributions/mcfpp-intellij-0.6.2.zip`，重启 IDEA。

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

0.6.2 同步结构类型 `{ x: int }`、交叉/联合类型 `(A & B)` / `(A | B)` 和 `new` 工厂。工厂通过 `Point(...)` 或 `Point.new(...)` 调用，参数提示和导航进入同一声明；`return { ... }` 按所属模板补全字段。data 继承只组合字段，显式字面量必须提供所有必需字段，并允许额外字段；字段初值仅供隐式工厂使用。可选字段支持 `?.`、`?:` 和 `!!` 的词法与成员类型推断。可打开[类型组合示例](../examples/type-composition)检查。

Macro 函数默认使用紫色加粗，普通 MNI 函数默认使用青绿色正体，覆盖声明、调用、导入别名和 provider 方法绑定。**Settings → Editor → Color Scheme → MCFPP → Identifiers → Macro function / MNI function** 可分别修改样式。样式依据解析后的声明及 `@MNIMacro` 元数据区分；普通函数和遮蔽同名宏的局部函数值保留自身样式。LSP 输出 `macro` / `mniFunction` 语义标记，IDEA 的宏符号快照和编译库元数据携带分类。

整文件 **Code → Reformat Code** 与保存共用后台 Node 服务：Linter 安全修复 → Prettier → 重新诊断。项目的 Linter 配置、忽略规则、Prettier 配置、`.prettierignore` 和 `.editorconfig` 会被重新读取。默认四空格、100 列并保留换行方式。局部格式化和输入缩进使用原生格式化器。

保存结果仅在文档版本仍一致时写入；连续输入产生的过期结果会被丢弃。格式化可撤销，语法错误或工具失败时保留原文。安全快速修复可从问题位置的 **Alt+Enter** 应用。保存格式化、安全修复和实时 Linter 可分别在 MCFPP 设置中关闭。

行尾空格、混合缩进和文件末尾换行属于格式化事项，不生成编辑器警告；格式化时统一整理。语法错误和有实际含义的 Linter 问题继续显示。

语言功能包括补全、参数提示、文档、声明导航、引用与重命名、结构视图和版本分支。普通 MNI 函数使用 `func answer(): int = example.mni.Example.answer` 完整声明，Java 侧使用 `@MNIFunction`；宏使用同一显式声明形式，provider 侧使用 `@MNIMacro(normalParams = [...], returnType = "...")`。宏参数模式由 JVM 的 `Value`/`Ref`/`Const`/`InlineFn` 包装推导，`injectedBy` 不再暴露宏。支持 Java 和 Kotlin 目标补全、参数和返回类型检查及导航；Kotlin 顶层函数、`object` 与 companion 的 `@JvmStatic` 入口通过同一 provider 索引解析。

`var h: X = { ... }` 与函数参数中的数据模板字面量按目标类型补全字段，选择后插入 `字段名: `；已有字段和不可访问字段会从候选中排除。嵌套模板、继承字段、泛型和 `list<X>` 元素沿用各自的字段类型与默认值，悬停或查看补全文档可读取字段说明，字段名支持跳转到声明。LSP 实时检查缺失、未知、重复、不可访问字段及字段值的类型，IDEA 显示同一组诊断。IDEA 快速文档在 LSP 已提供内容时跳过重复的原生目标，LSP 尚未就绪或没有内容时使用原生文档。可打开[模板字面量示例](../examples/template-literals)检查变量及参数的编辑效果。

实体接口与编译器同步：支持 `UUID`、`ItemDisplay` / `BlockDisplay` / `TextDisplay` 等实体子类型，以及 `entityByUUID<T>` 的结果类型推断。`display.transform.y`、`scale.x/y/z`、四元数分量、公共显示属性及三种展示内容属性提供补全和说明；属性赋值沿用 setter，`tryRead` 读取快照。内建接口可跳转到只读接口描述。实体声明类型不会隐式检查实际种类，使用 `exists()` 和 `checkType<T>()` 查询。

实体模板使用 `entity Name: Parent { ... }` 声明。模板字段/方法与 `entity var`/`entity func` 分开补全：模板值显示普通成员和 `summon()`，`summon()`、`entityByUUID<T>` 的结果显示 `EntityRef<T>` 成员、模板实体成员以及父模板的实体成员；`this` 在实体成员函数中按 `EntityRef<T>` 解析，`forEach`/`forEachAt` 也作为实体上下文入口提供。命令块 `/{ ... }` 的每个非空行按原版命令着色和补全，`${...}` 仍进入 MCFPP 引用与诊断；`/` 开头的行按普通 MCFPP 语句处理，块可折叠并与花括号匹配。

上下文使用 `context(space: PosContext) func probe() { ... }` 声明依赖；`context(space) { probe() }` 显式提供词法候选。IDEA 与 LSP 共用上下文类型和成员描述，按具名继承关系补全，推断同步回调参数，并支持上下文形参及具名实参的导航、引用和重命名。LSP 检查缺失、歧义、类型不匹配、不可用成员及通过返回值逃逸；函数值保留 `context(PosContext) () -> void` 的依赖。入口补全插入显式回调参数，保存格式化识别新语法。旧 `execute(...)` 语句已移除。可用 [上下文编辑示例](../examples/context-editor) 检查这些功能。

`tick { frame -> ... }`、`loop(t) { frame -> ... }` 和 `schedule(t) { ... }` 支持尾随 lambda、参数提示与类型检查。`frame` 从零开始；tick/loop 回调返回 bool，schedule 回调返回 void。新安装包同时携带配套的 CLI、标准库和 LSP；构建时校验 CLI/LSP 指纹一致。

补全按上下文收敛：实体子类型放在类型注解、类型转换及实体 API 的类型参数位置；属性访问只列出接收者成员，赋值和返回表达式隐藏声明关键字。空前缀默认显示当前作用域与已导入符号；输入名称后仍可自动导入其他项目符号，再次调用补全可展开未导入候选。LSP 同样遵守标准库的隐式命名空间和显式导入范围。

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

在 **Run → Edit Configurations → MCFPP Build** 中勾选 **Copy datapack after successful build**，将 **Deployment directory** 设为游戏存档的 `datapacks` 目录（可浏览选择；相对路径按运行配置的工程目录解析）。之后从运行按钮或工具窗口执行 Build，成功后会自动复制到 `<部署目录>/<mcfpp.toml 中的 name>`；未设置 `name` 时使用工程目录名。Dump IR 也支持该选项。复制使用 `root`、`targetPath` 对应的输出，并支持 `--debug-info` 的 `debug/` 输出。

每次部署完整替换同名数据包并清除旧文件，其他数据包保持不变；已有同名目录必须包含 `pack.mcmeta`。编译失败时跳过复制；复制失败会在 Run 控制台显示原因，并将此次运行标为失败。设置随运行配置保存。进入已加载的存档后执行 `/reload` 重新加载数据包。

Build 成功后点击 **Data Pack** 查看数据包，点击 **Resource Pack** 查看配套资源包。标准库函数和资源按引用链链接进同一数据包；未使用的库对象不执行初始化。动态函数目标按可能匹配的函数族保守保留，并在 IR 链接报告中说明。Test 执行 Java MNI 测试和 `src/test/mcfpp` 下的 MCFPP 测试。MCFPP 使用 `import mcfpp.test:*`、`@Test`、`@BeforeEach`、`@AfterEach`、`@Disabled("原因")` 和 `assertEquals` / `assertTrue` 等断言；插件复用内置 DSB JAR 和命令服务的 Java 25 设置。函数名旁的运行按钮执行单例，MCFPP 结果显示在测试树中并支持源码跳转；运行配置可传 `--tests 'namespace:function*'`。JUnit XML 位于 `build/tests/mcfpp/TEST-mcfpp.xml`（自定义 `targetPath` 时随之变化）。Lint 和 Format Check 只检查文件，不写入源码。

函数旁选择 **Run (Render)**，或在 `@Test` 旁选择 **Test (Render)**，可打开内置 DSB 的独立实时窗口。窗口初始暂停，支持播放、单 tick、镜头移动、重置和 PNG 导出。测试先正式执行并显示在测试树，再在独立世界重放初始化和被选用例，保留清理前的展示实体；断言失败也能预览，禁用用例跳过窗口。预览交互不修改正式测试结果。

在 **Settings → Tools → MCFPP** 设置 Minecraft 客户端 JAR / assets 目录，以及每行一个的附加资源包路径；留空使用 DSB 的替代模型。工程生成的资源包先加载，附加资源包按填写顺序覆盖。运行配置的 **Open DSB render window** 开启预览，Build 用 **Render function** 指定无参数顶层函数，Test 用 `--tests namespace:function` 选择单例。临时预览包副本位于 `<targetPath>/render/session-*`，PNG 导出位于 `<targetPath>/render/exports`，IDEA **Stop** 和关闭项目会终止对应进程。参见[渲染预览示例](../examples/render-preview)。

运行窗口将 **Compilation / Tests** 与 **DSB** 分开显示。预览使用 `--render-info` 保留正常优化，将入口信息写入 `preview/mcfpp.render.json`；调试器继续使用 `--debug-info`。临时会话在窗口结束或 Stop 后删除，异常退出遗留的目录在下次启动回收；用户主动导出的 PNG 保存在 `render/exports`，`render/dsb.log` 只保留最近一次日志。默认编译日志级别为 INFO。渲染构建与源码测试自动启用 `--reuse-build`：无 MNI、无外部库的本地工程在输入和输出内容均未变动时复用编译产物，测试仍在新世界执行；可用 `--no-reuse-build` 强制重新编译。

## 可复现工程

- [普通工程](../examples/idea-cli-simple)：CLI 原始模板，运行 Build 后检查 `build/`。
- [Java MNI 工程](../examples/idea-cli-mni)：先同步，再运行 Test 和 Build；检查 Java 原生函数调用及数据包。

## 从源码构建

将 `MCFPP`、`MCFPP-Linter` 和本仓库放在同一父目录。Gradle Composite Build 会按需构建编译器和 LSP，并将对应产物打包到插件中。Windows 工作区的全量验收命令如下。

```powershell
cd F:\Project_MCFPP\MCFPP
.\gradlew.bat shadowJar editorRuntimeJar stdlibIndexJar sourcesJar verifyDistribution test --offline --no-daemon "-Dmaven.repo.local=F:\DevCache\.m2\repository"
cd F:\Project_MCFPP\mcfpp-language-support\language-server
.\gradlew.bat test shadowJar intellijServerJar --offline --no-daemon --max-workers=6 "-Dmaven.repo.local=F:\DevCache\.m2\repository"
cd F:\Project_MCFPP\mcfpp-language-support\intellij-plugin
.\gradlew.bat test buildPlugin --offline --no-daemon "-Dmaven.repo.local=F:\DevCache\.m2\repository"
```

若格式化依赖尚未安装，先在 `MCFPP-Linter` 中执行 `npm ci`。ANTLR 由 `mcfpp-syntax` 从编译器文法生成一次，三个工程共同使用该模块。

本机 SDK 可用 `-PideaLocalPath=<IDEA目录>` 指定。使用 JDK 25 启动 Gradle（`JAVA_HOME` 指向 JDK 25）；这是默认构建工具链。插件宿主代码仍输出 Java 21 字节码以匹配 IDEA 基线，独立 CLI、LSP、调试适配器与 DSB 均使用所选 JDK 25。GUI 沙盒使用 `runIde --args="<工程绝对路径>"`。
