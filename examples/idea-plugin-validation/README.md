# IDEA 插件人工验收项目

直接在本机 IDEA 中打开本目录 `examples/idea-plugin-validation`，选择 **Open as Project** 并信任项目，然后等待 Gradle 同步和索引结束。无需用 `runIde` 启动沙盒。

## 打开前准备

1. 在 **Settings | Plugins | 齿轮 | Install Plugin from Disk** 安装 `../../intellij-plugin/build/distributions/mcfpp-intellij-0.4.2.zip`，按 IDEA 提示重启。
2. 启用 Java 插件，设置 **Project SDK** 与 **Gradle JVM** 为 Java 21。自动化测试基准为 **IntelliJ IDEA Ultimate 2025.3.3**，兼容性检查也覆盖本机 IDEA 2026.2 的相同 build **262.10315.125**（使用完整 SDK）；LSP 功能需要 IDEA 的 LSP 模块。
3. 本项目的 Gradle Wrapper 为 9.2.1。Java/MNI 依赖直接使用 `../../language-server/build/libs/mcfpp-language-server.jar`，不下载 MCFPP SNAPSHOT 依赖；当前工作区已构建该文件。换到其他机器时，先构建语言服务器，或用 `-PmcfppApiJar=/绝对路径/mcfpp.jar` 指定真实 MCFPP JAR。
4. 命令补全和命令诊断需要 Java 25。当前机器可以从终端启动 IDEA，让它继承下面的环境变量；已经运行的 IDEA 需先退出：

   ```bash
   export MCFPP_DPS_JAVA=/home/Alumopper/.jdks/openjdk-25.0.2/bin/java
   /你的IDEA安装目录/bin/idea.sh /home/Alumopper/Projects/mcfpp-language-support/examples/idea-plugin-validation
   ```

   也可在 **Help | Edit Custom VM Options** 添加 `-Dmcfpp.dps.java.path=/home/Alumopper/.jdks/openjdk-25.0.2/bin/java`，然后重启 IDEA。路径不同的机器请替换为自己的 Java 25。

本项目故意包含未解析引用和重复声明，用来验证红线和快速修复。**Gradle 构建只编译 Java/MNI 示例，不编译这些故意错误的 MCFPP 文件**。`mcfpp.json` 没有 `jars` / `include` 依赖，使新增的原生检查能够直接参与验收。

## 验收顺序

先确认 **Settings | Editor | Inspections | MCFPP** 中 **Unresolved MCFPP reference**、**Duplicate MCFPP declaration** 和 **Unused MCFPP import** 已开启。快捷键以默认 Windows/Linux Keymap 为例；使用其他 Keymap 时可通过对应菜单执行。

| 文件（均在 `src/main/mcfpp` 下） | 操作 | 预期结果 |
| --- | --- | --- |
| `scenarios/01_completion.mcfpp` | 光标放在 `Coun` 后，Ctrl+Space，选择 `validation.beta:Counter` | 候选区分命名空间；插入 `Counter` 和对应的精确导入。多个测试文件也声明了 Counter，因此会看到多个候选。 |
| 同上 | 光标放在 `conv` 后，Ctrl+Space，选择 convert | 保留 int/string 两个重载签名；插入括号和 `validation.alpha:convert` 导入，光标进入括号。 |
| `scenarios/02_aliases.mcfpp` | 补全 `Alpha`；对 `AlphaCounter`、`increment`、`alpha:convert` 执行 Ctrl+B | 复用已有别名，不添加冗余导入；别名构造赋值不应出现 AlphaCounter/Counter 类型不匹配；导航到 alpha 文件。成员补全可把 `increment` 改成 `inc` 后触发。 |
| `scenarios/03_name_conflicts.mcfpp` | 补全 Coun，明确选择 beta 的 Counter | 插入 `validation.beta:Counter`，不添加歧义导入。 |
| `scenarios/03_crossfile_conflict.mcfpp` | 同上 | 即使当前命名空间的同名类型在另一个文件中，也使用完整限定名。 |
| `scenarios/04_import_choices.mcfpp` | Counter 上按 Alt+Enter，选择 `Import 'validation.beta:Counter'` | 可以按命名空间选择导入；修复后 Ctrl+B 指向 beta 文件。 |
| `scenarios/05_optimize_imports.mcfpp` | **Code | Optimize Imports**，重复一次，再撤销 | 删除第一个块中重复的 convert 导入并排序；保留未使用导入、不同别名、CountDown 的注释绑定和条件分支；再次执行无变化，撤销可恢复。 |
| 同上 | 查看没有引用的导入，在灰显导入上 Alt+Enter → Remove unused import | 未使用导入灰显并提示警告；只删除选中的导入，保留注释和版本指令；撤销可恢复。 |
| `scenarios/06_quick_fixes.mcfpp` | missingHelper 与 MissingType 上按 Alt+Enter | 分别创建 `func missingHelper(arg1 as int, arg2 as string)` 和 `data MissingType`；光标进入新声明。可把 MCFPP 缩进改成 2 后测试格式化。 |
| `scenarios/07_scopes_and_duplicates.mcfpp` | 查看重复提示；在 nestedScope 的最后一个 value 上 Ctrl+B | 第二个 Duplicate 和参数/局部变量重名被检查；不同类型成员及不同内层块中的同名变量不误报；最后一个 value 指向参数。 |
| `scenarios/08_version_branches.mcfpp` | 查看红线和折叠，再把 mcfpp.json 的 version 改成 1.21.8 并保存 | 默认只有 missingModern 报错，legacy 分支灰显并默认折叠；版本切换后只有 missingLegacy 报错，modern 分支灰显并折叠。展开非活动分支仍可看到灰显。测试后将版本恢复 26.3。 |
| `scenarios/09_qualified_calls.mcfpp` | 两个缺失调用上按 Alt+Enter | LSP 保留错误提示；不提供错误的“在当前文件创建顶层函数”操作。 |
| `scenarios/10_mni.mcfpp` | 声明名和 Java 目标上 Ctrl+B；查看 gutter 图标 | 跳到 `ValidationMni.java`，convert 按 MCFPP 签名选择正确 Java 重载；@From 能跳到 Java 类。修改目标名、static 或注解参数可验证绑定检查，之后撤销。 |
| `scenarios/11_generic_fallback.mcfpp` | 查看泛型 T | 原生检查不把泛型声明上下文中的 T 错当作未解析变量，复杂语义继续交给 LSP。 |
| `datapack/.../commands.mcfunction` | 对命令、选择器、资源 ID 触发补全 | Java 25 服务启动后提供补全；`scoreboard players set` 是故意的命令语法错误。 |
| `datapack/pack.mcmeta`、recipe、tag JSON | 编辑键名并触发补全 | 有 JSON 模块时使用对应的路径敏感 JSON Schema。 |

确认诊断共存时，可以在 `06_quick_fixes.mcfpp` 的 missingHelper 上查看问题提示；开启原生检查时，不应同时出现两条相同的 Undefined symbol 提示。关闭 **Unresolved MCFPP reference** 后，LSP 应继续显示该错误，其他 LSP 诊断也应保留。

每一项可独立测试；需要恢复原文时使用 IDEA 的 Undo 或 Local History。所有构建产物、IDEA 元数据和临时修改都放在此项目中。

## 本机预检查

```bash
cd /home/Alumopper/Projects/mcfpp-language-support/examples/idea-plugin-validation
bash gradlew classes --offline
```

当前工作区已有 Gradle 9.2.1 缓存；其他机器第一次使用 Wrapper 时需联网下载 Gradle。
