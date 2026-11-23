# 无限画布退出分屏后局部黑屏（2026-11-05）

用户要求：排查天玑 8350、Android 16 设备在应用内退出分屏、剩余无限画布铺满屏幕后，原分屏区域变为全黑的问题。
状态：讨论中；已核对源码并完成一次旧版本模拟器对照，未复现目标设备故障，根因未确认。
本轮边界：仅排查与记录；未修改产品代码，未打包、安装、推送或发布。

## 用户确认的事实

- 是应用内双窗格，不是 Android 系统多应用分屏。
- 问题发生在退出分屏、剩余文档铺满屏幕后；剩余文档为无限画布。
- 勾选「禁用前缓冲绘制」后重新进入、退出分屏，问题仍然出现。
- 用户进一步确认平移、缩放无法恢复；尚未测试旋转屏幕。因此仅缺少一次重绘的解释可信度降低，但未取得目标设备的实际帧绘制证据。
- 用户称使用最新版本；本地当前构建配置为 0.9.8 / versionCode 93，暂按该版本调查，未直接核验设备安装信息。具体设备型号、系统构建号、实际 GL_RENDERER 和驱动版本尚未提供。天玑 8350 为用户提供的芯片信息，不据此冒充已经读取实际 GPU/驱动。

## 源码核对

检查基线 `d4a5fc9c`，开始时工作区干净。

1. `Editor.applyPagePrefsToState()` 同时更新分页与无限画布的 `pad.frontBuffering`。关闭前缓冲将湿墨 SurfaceView 设为 GONE，不会移除无限画布的底层 `InfiniteCanvasView`（GLSurfaceView）。因此本次对照不支持把故障仅归因于湿墨层；仍需区分底层 GL 绘制和系统合成。
2. `MainActivity.PaneToolbarAround()` 在分屏与单文档之间切换不同 Compose 分支。无限画布的 `AndroidView.factory` 调用 `detached(canvas.surfaces)`，从旧父节点移除同一个 FrameLayout，再挂到新宿主；其中 GL 画布和湿墨 View 均复用。
3. 分页文档调用 `Editor.surfacesForAttach()` 替换湿墨 View。此前 Release 87 的用户验收只证明原分页场景的实时笔迹问题解决，不证明无限画布黑屏原因相同，也不授权直接将旧结论扩大到 Android 16。
4. `InfiniteCanvasView.onDetachedFromWindow()` 调用 GLSurfaceView 的拆挂逻辑；`onSizeChanged()` 更新 viewport 并 publish；Compose update 同样 publish。`GlRenderer.onSurfaceCreated()` 会重建 shader 和 scene 的 GPU 状态，`onSurfaceChanged()` 设置 GL viewport。因此不能声称代码完全没有处理重绘、尺寸变化或 EGL 上下文丢失。

主要待验证假设是跨 Compose 宿主复用 GLSurfaceView 后，View/RenderNode、Surface 几何或合成状态没有正确恢复。尚无目标设备图层、缓冲或日志证据，不能指定某个 crop 字段或宣称是芯片驱动缺陷。

## 模拟器对照

- 使用本机已存在的 `emulator-5554`，未安装或替换应用。
- Android 12，ARM64，包名 `com.xnotes.movinkpad`，versionCode 89，versionName `0.9.7-movinkpad-preview1`。
- GL 日志为 Android Emulator OpenGL ES Translator / ANGLE / SwiftShader，实际 MSAA 为 0；不支持 mutable render buffer，湿墨层初始化报告 `no mutable-render-buffer config`。
- 打开现有无限画布 → 打开右侧空分屏首页 → 关闭右侧首页 → 恢复全屏。退出后的截图中网格覆盖整个画布、图片内容正常，没有观察到局部黑块。日志记录新的 GL context 和 `self check ok`。
- 这只是一轮旧版本、不同系统和软件 GPU 的对照，不能作为当前源码或目标设备兼容性验收，也没有覆盖关闭另一份已打开文档的全部路径。
- 原始截图和日志在忽略目录 `tmp/infinite-split-black/`。未运行新的 JVM 或 Android 仪器测试；没有代码修复可据此宣布通过。

## 下一步证据

平移/缩放不恢复的对照已经完成，无需重复。下一步需要故障时截图或设备上的渲染证据；横竖屏切换尚未测试，可作为补充，但不是确认根因的必要前置条件。

已核对 AndroidX Media 的 [SurfaceView / Compose API 35 报告 #2158](https://github.com/androidx/media/issues/2158)：维护者指出相关框架修复已进入 Android 15，原报告未提供真实设备复现，最终因缺少信息关闭。因此不能拿这个问题号认定 Android 16 本设备命中了已知缺陷，也不采用修改 SDK 版本判断的绕过方式。

如果能连接故障设备，采集故障前后 `xnotes.gl`/EGL 错误日志、SurfaceFlinger 图层及尺寸，并比较系统截图与屏幕实际显示；避免只凭模拟器或重建候选的效果声称已定位 GPU 内部缺陷。当前没有加入按芯片禁用功能、强制软件绘制或无证据的 View 重建补丁。
