# 第二批渲染回迁：稳定工具栏与窗格布局（2026-11-07）

用户要求：继续第二批稳定布局回迁，允许测试，审查验证后推进主分支合并。
状态：生产改动与几何回归已完成独立静态审查；生产 Kotlin 编译通过，2779 项 JVM 测试通过；真实窗口回归仍在适配，尚未本地合并。
本轮边界：只回迁稳定工具栏／窗格布局并保留主线 View／湿墨层替换，授权编译、测试及本地合并；不推送或发布。模拟器所需测试 APK 生成／安装尚待本轮明确确认。

## 来源与实现

从已含第一批诊断／Surface 捕获的本地 master `cf2a4d9d` 建立 `codex/stable-pane-layout-backport`，复用独立工作树，保留原 `codex/render-lifecycle`。生产差异取自 `ed996c8c` 的三个布局文件，不整文件覆盖其他主线代码或复制旧任务断点。

- `PaneChromeLayout` 固定内容组合位置，并保留停靠工具栏槽；换边、停靠／浮动或隐藏不再迁移内容宿主。工具栏本身仍在停靠／浮动调用点间切换，浮动工具栏保持原菜单覆盖次序。
- `ToolbarFrame` 的外围布局调用上述实现，隐藏时保留内容槽及零遮挡信息。
- `MainActivity` 的窗格按 owner 与文档渲染类型标识；`PaneToolbarAround` 不再因工具栏显隐切换内容宿主结构。

真正退场／重挂仍走主线 `surfacesForAttach()`。主菜单通过停止组合 SplitHost 隐藏编辑区，返回属于真实重挂；分页保留普通 CanvasView 并替换湿墨 pad，画布保留文档／viewport／scene，但替换绘制 View 与 pad。稳定布局不承诺 Surface 或 EGL context 永不重建。

画布仍只单独打开；不恢复画布分屏。既有画布分屏拒绝、旧会话兼容、前缓冲偏好、暂存笔迹归还、旧回调隔离及首次未挂接守卫保留。本批不迁入 RenderSession、SceneStamp、DrawReceipt、保存／录音交接或 RF01 修正。

## 已完成验证

- JDK 21.0.10、Gradle 8.13、AGP 8.12.3、SDK 36；使用独立输出 `/private/tmp/xnotes-stable-pane-layout` 和项目缓存 `/private/tmp/xnotes-stable-pane-layout-cache`。
- `:app:compileDebugKotlin` 通过，日志 `tmp/stable-pane-compile.log`。
- `:app:testDebugUnitTest` 全量 247 个套件、2779 项通过，失败／错误／跳过均为 0，日志 `tmp/stable-pane-unit.log`。包括 PaneChromeLayout 3、RenderDiagnostics 11、SurfaceCapture 10、InfiniteSurfaceAttach 4 项；不是沿用历史运行结果。
- 三生产文件及 3 项几何测试独立静态复核无必修项。GL、两种 Editor 与第一批诊断／捕获实现相对基线未改变；两个 AndroidView factory 的主线替换调用保留。

## 待完成的窗口验证

迁移历史真实窗口夹具，按当前产品范围覆盖单画布四边／浮动／停靠／显隐／输入，分页笔记与 PDF 的分屏、横纵方向、比例、交换及首页恢复。真正退出宿主再返回应按主线规则替换 View／pad，不以“任何操作都不重挂”为验收目标。第一批 Surface 捕获及既有仪器注册继续保留。

软件与目标设备证据分开：模拟器即使通过，也不能代表 MovinkPad Android 15 或用户天玑 8350／Android 16 的 GPU 显示、笔延迟与原黑块效果。后续窗口编译、执行、修正及合并结果在本记录补齐。
