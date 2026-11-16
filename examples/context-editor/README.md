# 上下文编辑示例

用 IDEA 打开本目录，或用 LSP 客户端打开 `src/main/mcfpp/main.mcfpp`。

在 `space.` 后补全只显示 `pos` 和 `dimension`；在 `passenger.` 后可补全完整空间、`executor` 与关系入口。`teleportBorrowed` 的参数提示保留具名上下文依赖；声明处的 `space` 可导航、查找引用及重命名，具名实参的标签也会更新。

把 `teleportBorrowed()` 放到受控回调之外会提示缺少上下文；用 `context(first, second) { ... }` 同时提供两个可匹配来源会提示歧义，可通过 `space = position` 指定。

`withEntityCtx`、`withPosCtx` 等补全会插入带显式 `ctx` 参数的同步回调。`context(...)` 函数头、显式提供块和 `context(PosContext) () -> void` 函数值类型均可正常格式化。
