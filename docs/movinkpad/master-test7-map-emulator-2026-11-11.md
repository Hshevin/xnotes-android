# test7 思维导图模拟器实际流程测试（2026-11-11）

用户要求：测试提供的 `XNotes-master-e4739bd6-test7-arm64.apk`，重点检查思维导图的实际可用性。
状态：已验证（API 32 ARM64 模拟器实际 UI 操作、截图、存盘内容核对）；发现阻断问题，未修复；Android 15 与目标真机待验。
本轮边界：安装用户指定 APK 到本机模拟器，创建专用测试数据；只提交测试记录、截图及 README 提醒，不修改实现、不打包、不安装真机、不推送、不发布。

## 对象与方法

- 原始 APK SHA-256：`ab2eb58cf5d563d8ffa709de77121b879ca6d2a405929faf94191c7f1c459121`。
- 包名 `com.xnotes.movinkpad.branchtest.master`，versionCode 7，versionName `0.9.8-test-master-7`；源码标识 `e4739bd6`。本轮未重编译或替换 APK。
- 本机唯一现成 AVD `Medium_Phone`，Android 12 / API 32，ARM64 Google Play 镜像。初始为该包首次存储设置页，选择应用存储。
- 主要流程使用 1800×1125 / 160 dpi 横向覆盖配置；另以实际 2160×1800 / 240 dpi 和原始 1080×2400 / 420 dpi 复查空白。请求 2880 宽时模拟器实际限为 2160，不能称为目标设备分辨率验收。测试结束恢复原始尺寸、密度与日间模式。
- 经 ADB 注入点击、文本、模拟 stylus 轨迹；UIAutomator 保存界面树，screencap 保存截图。读取测试 `.xmap` 核对节点、关系及笔迹，不修改应用存盘文件来伪造测试状态。
- 测试文档 `QA-map-test7.xmap`、`untitled_1.xnote`，副本在模拟器 Download。原始截图、XML、日志、各阶段 `.xmap` 和辅助脚本保留于已忽略的 `tmp/test7-map-qa/`，下列关键截图纳入仓库。

## 结论

本包在该模拟器上的思维导图无法完成正常可视编辑闭环。保存层的部分能力可用，但主画布从新建起持续不可见、不可操作；不能据此宣布思维导图通过验收。未发现本轮操作产生的应用崩溃，崩溃缓冲为空，但“没有崩溃”不等于可用。

### P1：主导图画布空白，新增文本没有进入可见编辑

复现：首次设置应用存储 → 首页“New mind map” → 输入名称 → Create。

实际：显示文件名、Saved、工具栏和 Topic，画布完全空白。点击 Fit、Edit node 不恢复；Text child 会新增并保存默认 Topic，但没有可见的原位文本输入。节点列表能列出它们，节点属性可修改文字。不是“没点中按钮”：读取存盘后确有新增节点。

退出返回首页再打开、真实 force-stop 后重新启动、切换尺寸/密度、日夜主题、进入左右或上下混合分屏，均仍为空白。界面树中的原生 `Mind map canvas` 为 `enabled=false`。新建截图：[map-created.png](evidence/test7-map-2026-11-11/map-created.png)。

影响：看不到结构、无法直接选择/拖动节点、无法完成画布连线、多选、原位笔写；Sources 入口也保持灰色，来源流程本轮被阻断。不可将这些受阻项写成测试通过。

定位线索（不是已证实根因）：`MindMapScreen.kt` 的 `cameraReady` 同时控制 AndroidView 的 alpha 和 enabled，并控制来源入口；就绪 Effect、View 引用及布局/IME 生命周期需要优先追踪。当前证据支持检查宿主就绪链路，尚不能排除平台渲染因素，也不宣称真机一定同样发生。

### P1：放大手写的操作栏不可见

复现：点击 Handwriting child；或 Nodes → Handwriting node → Enlarge edit。

实际：能看到纸张和绘出的笔迹，但放大面板自己的 Back、Undo、Redo、橡皮等工具不显示，所在区域是灰色；界面树仍有这些控件，按其坐标点击 Back 能返回。新建和重进均复现。截图：[ink-reopen-settled.png](evidence/test7-map-2026-11-11/ink-reopen-settled.png)。

影响：用户难以发现退出、撤销和切工具的入口。需检查 `MapInkEditor` / `InkPadView` 的绘制边界及 Compose/AndroidView 叠放，根因尚未确认。

### P1：混合分屏保存失败

复现：单图“Open a note beside this map” → 另一侧首页 New → New Note → Create（默认空白笔记）→ 切换为上下排列 → 顶栏 Save current split。

实际：连续两次提示 `Could not save this split. Both documents are still available.`，未进入保存安排的后续流程。两侧文档均保留，笔记和导图文件实际存在。没有录音、未确认文本或持续笔接触。截图：[split-save-repeat.png](evidence/test7-map-2026-11-11/split-save-repeat.png)。

本轮仅确认该路径失败，未确认具体是来源、保存回执、输入稳定还是发布条件导致。未完成安排显式重开及混合冷恢复验收，不能以单图冷启动替代。

### P2：节点属性的确认按钮被软键盘遮住

复现：Node properties → 点击文字框 → 输入文字。

实际：属性对话框采用很高的文本区，键盘遮住底部 Cancel / Done；界面树仍报告按钮坐标，但触摸该位置实际落在键盘上。先按系统返回收起键盘后才能确认。截图：[root-renamed-done.png](evidence/test7-map-2026-11-11/root-renamed-done.png)。该截图名称来自采集步骤，画面实际仍是未关闭的编辑对话框。

### P2：多排工具栏挤占上下分屏的画图区

1800×1125、160 dpi 上下等分时，导图侧从 y=609 开始，画布只剩 y=931—1086，约 155 px 高；大片空间用于常驻文本操作行。没有选手绘节点时，笔/橡皮/笔宽及手绘属性行仍占据空间。截图：[split-horizontal.png](evidence/test7-map-2026-11-11/split-horizontal.png)。这是实际布局问题，不以画布空白根因修复作为它自然解决的证明。

## 已走通的局部流程

- 首页新建、关闭返回、再打开 `.xmap`；节点列表可读，节点属性修改可确认并保存。
- 新增文本子节点确实写盘；手绘子节点放大模式接受一条模拟 stylus 轨迹，笔迹可见且写盘。
- 单图 force-stop / 启动后恢复该图，文件仍含 3 个文本节点（其中一个改名 Root-QA）、1 个手绘节点及 1 条笔迹；随后进入放大模式仍可见笔迹。主导图依然空白，故只判定内容保留和入口恢复通过。
- Relations → Connect selected node → 选择 Root-QA → Done：存盘关系数为 1；关闭对话框后 Undo 变为 0，Redo 恢复 1，节点数始终 4，笔迹数始终 1。验证的是列表方式的关系命令及持久化，不是画布连线。
- Save a copy → 系统 Downloads → Save：提示成功，独立副本 ID 与原件不同，4 个节点与笔迹负载保留；未做副本重新导入界面的完整验收。
- 导图旁首页、新建笔记进入 Map+NOTE、切换分屏方向、关闭笔记回该侧首页、再关闭首页回单图均可执行。

## 本轮未通过或未覆盖的边界

- 主画布直接交互、原位文本、拖动/缩放/多选、画布连线、来源面板及来源跳转被空白问题阻断。
- PDF/Canvas 来源、多来源更新/失效恢复、反向引用、全库网络连续流程、大图和大 PDF 性能未执行。
- 未运行 Android 仪器测试套件、JVM 全量或 lint。本轮是交付 APK 的实际 UI 测试，不能外推之前平台失败已修复。
- 未测试 Android 15、MovinkPad Pro 14、Pro Pen 3、掌触、物理笔按钮和目标 GPU。模拟 stylus 轨迹不能替代真机笔输入验收。

后续应先恢复“新建 → 看见中心节点 → 新增并编辑 → 拖动/连线 → 保存重开”的可见闭环，再修复混合保存与放大/键盘界面，然后继续来源和网络的完整流程验收。已有缺失功能仍按持续需求计划保留，不把本轮暴露的运行时回归归类为未来增强。
