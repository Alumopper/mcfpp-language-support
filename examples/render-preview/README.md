# DSB 渲染预览

在 IDEA 打开此目录，设置 JDK 25。点击 `main` 旁的运行图标，选择 **Run (Render)**。DSB 独立窗口初始暂停；点击 **Play** 后，石头展示实体从高处下降，最终回到高度零。可暂停、单 tick、移动镜头、重置和导出 PNG。

在 `display_test.mcfpp` 的函数旁选择 **Test (Render)**：`displayExists` 通过，`failedPreview` 失败后仍打开预览，`disabledPreview` 跳过预览。正式测试执行 `@AfterEach`；独立预览重放 `@BeforeEach` 和测试函数，保留展示实体。预览中的交互不修改正式测试结果。

在 **Settings → Tools → MCFPP** 配置本地 Minecraft 客户端 JAR 或含 `assets/` 的目录，可使用原版模型和纹理；留空使用 DSB 的替代模型。附加资源包每行一个目录或 ZIP，相对路径按运行配置的工程目录解析。工程生成的资源包先加载，再按填写顺序覆盖。

打开 MCFPP 工程时，若尚未配置游戏资源或资源来源已失效，IDEA 右下角会显示警告。点击 **Choose Minecraft JAR…** 可直接进入 MCFPP 设置并定位到资源选择项；选择游戏安装目录下 `versions/<版本>/<版本>.jar` 的客户端文件。保存时会检查客户端字体和纹理资源。

运行配置中的 **Open DSB render window** 保存渲染开关，Build 使用 **Render function**，Test 使用 `--tests namespace:function` 选择单例。IDEA 的 **Stop** 会关闭对应预览。每次运行的数据包副本和 PNG 位于 `build/render/session-*`。
