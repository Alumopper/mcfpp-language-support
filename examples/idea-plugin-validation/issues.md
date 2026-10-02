# 验收问题修复（IDEA 插件 0.4.2）

安装 `../../intellij-plugin/build/distributions/mcfpp-intellij-0.4.2.zip` 并重启 IDEA，等待索引完成后复验。原始问题记录保留在下方。

| 问题 | 修复 | 复验方式 |
| --- | --- | --- |
| 1. 未解析引用没有报错 | 项目源码不再重复挂为外部库；原生检查不能接管时保留 LSP 诊断，其他类型的成员不会误解析为当前变量。 | `01_completion.mcfpp` 中 `convert(w)` 的 `w` 应出现 Undefined symbol 红线。 |
| 2. 导入类型别名匹配错误 | 赋值、参数和返回值检查使用别名对应的原始类型。 | `02_aliases.mcfpp` 中 `AlphaCounter = AlphaCounter()` 不再出现 AlphaCounter/Counter 类型不匹配；赋入其他类型仍报错。 |
| 3. 未使用导入没有灰显 | 新增默认开启的 Unused MCFPP import 检查，并提供单条删除修复。 | 在 `05_optimize_imports.mcfpp` 查看没有引用的导入；应灰显并有警告，Alt+Enter 可删除，注释和条件指令保留。 |
| 4. 未定义函数和类型没有诊断/修复 | LSP 也诊断缺失的声明类型，并附带原生创建/导入修复；修正光标位于标识符末尾时的动作识别。 | 在 `06_quick_fixes.mcfpp` 的 missingHelper、MissingType 内部或末尾按 Alt+Enter，应可创建函数/类型，之后红线消失。 |
| 5. 非活动宏分支没有灰显和折叠 | 共享预处理结果记录非活动范围，供灰显、默认折叠使用；保存配置后刷新打开的编辑器。 | 打开 `08_version_branches.mcfpp`：26.3 下 legacy 分支灰显并折叠；保存 version=1.21.8 后 modern 分支灰显并折叠。展开后仍灰显。 |

检查设置：Settings | Editor | Inspections | MCFPP，开启 Unresolved MCFPP reference、Unused MCFPP import。自动回归覆盖这些行为，本机窗口内的最终效果按上述步骤复验。

## 自动验证

- IDEA 插件：133 项测试通过，包含实际编辑器折叠状态、版本切换和手动展开状态保持的回归。
- 语言服务器：41 项相关测试通过，包含别名赋值、参数/返回值检查和未解析变量/函数/类型诊断。
- Plugin Verifier：IDEA Ultimate 2025.3.3、相同 build 的 IDEA 2026.2 完整 SDK（262.10315.125）均为 Compatible。
- 安装包内的语言服务器已同步至本次修复版本；验收项目已有的源码修改保留。

## 原始问题记录

1. 未解析的引用不会报错
```mcfpp
func functionCompletion() {
    var result = convert(w);    # w作为未定义变量不会报错
}
```

2. 类型别名匹配检查问题
```mcfpp
func navigateAliases() {
    # Type mismatch for `counter`: expected `AlphaCounter`, found `Counter`
    var counter as AlphaCounter = AlphaCounter();
    var result = counter.increment(1);
    var converted = alpha:convert(result);
}

```

3. 未使用的导入不会被虚化警告（类似java里面那种）

4. 06_quick_fixes 里面的 missingHelper 和 MissingType 不报错也不能快速修复

5. 宏批处理中不符合条件的代码不会被灰色处理，也不会自动折叠
