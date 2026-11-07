# 渲染回迁合并后审查与冒烟测试待修（2026-11-07）

用户要求：审查第二批稳定窗格布局（`076ba353`）与第四批图片／录音入队快照（`2f960218`）两次合并，并在模拟器上冒烟测试 master test2 APK；审查与测试发现计入待修清单，待后续渲染回迁／合并完成后一起修复。
状态：已审查、已记录，未修复。第一批诊断回迁的 D-1～D-7 另见 `render-diagnostics-review-2026-11-07.md`。
本轮边界：只新增本记录及入口链接；不改源码，不打包、安装、推送或发布。

审查由 Claude Code 的 code-review 流程完成，标「已核实」的条目已对照源码确认；行号为对应合并时的位置。两次合并均未发现会在正常使用中崩溃或丢失数据的问题。

## P：第二批稳定窗格布局（`076ba353`）

合并依据为编译与 2779 项 JVM；10 项窗口仪器测试未运行（master test2 模拟器中 `PaneChromeHostTest` 1 项输入注入 UID 不匹配，原因未确认）。

| # | 位置 | 问题 | 建议 |
| --- | --- | --- | --- |
| P-1 | `ui/PaneChromeLayout.kt:73` | 测量策略用 `check(...)`，遇到不限宽／高约束时正式版直接崩溃（旧 Row/Column 可容忍）。现有调用均为固定尺寸，目前不触发。已核实 | 改为兜底测量，不抛异常。优先修 |
| P-2 | `MainActivity.kt:1743` | 窗格 key 含 `editor.canvasOpen`，同一窗格切换文档类型时整个 EditorPane（标题栏、焦点、拖动状态）重建；SplitHost 读取该值也会多重组。已核实 | 只让内容分支随类型切换，key 去掉 `canvasOpen` |
| P-3 | `ui/PaneChromeLayout.kt:64` | 内容槽 `propagateMinConstraints = true` + `Constraints.fixed` 把每个根节点撑满整个窗格；旧布局给子元素最小 0。现有调用只有单个撑满的根节点，目前无影响。已核实 | 改为不传播最小约束，或在注释中明确限制 |
| P-4 | `ui/PaneChromeLayout.kt:83` | 停靠工具栏在 Layout 中位于内容之后，TOP/LEFT 时无障碍与键盘焦点顺序、超出边界的装饰绘制次序与旧布局相反 | 按停靠方向调整子元素顺序或显式设置遍历顺序 |
| P-5 | `ui/PaneChromeLayout.kt:66` | 停靠／隐藏时 `floatingBar` 为每次重组新建的捕获 lambda，导致编辑区内容多次重组、额外 publish | 非浮动时传常量 `{}` 或 `remember` |
| P-6 | `ui/ToolbarFrame.kt:78`、`MainActivity.kt:1847`、`:1624` | 三参数 `ToolbarAround` 重载已无调用者；`PaneToolbarAround` 仅原样转发；SplitHost 注释未写明首页切换与主菜单往返仍会真实重挂 | 删除旧重载与转发层，补全注释 |

## S：第四批图片／录音入队快照（`2f960218`）

合并依据为先失败后修复的队列回归与 2799 项 JVM；3 项 EGL 仅编译。

| # | 位置 | 问题 | 建议 |
| --- | --- | --- | --- |
| S-1 | `core/infinite/InfiniteDocument.kt:282` | 画布录音时每次抬笔：add 使 `order` 失效，随后 `syncChanged` 调 `orderMap()` 在主线程重建整张画布的 IdentityHashMap（估计 1 万对象约 1 ms、5 万约 5 ms，未实测）。不录音不触发。已核实 | 只检查变化项（空间索引／`indexOfRef`），或对刚加入项跳过成员过滤 |
| S-2 | `ui/InfiniteEditor.kt:357` | `history.onPush` 每次编辑都分配 before 列表并过滤，即使未在录音 | 先判断本画布是否在录音，或让 `stamp()` 返回实际变更项 |
| S-3 | `ui/InfiniteEditor.kt:2071` | `onSyncChanged` 已自行 publish，解除关联与录音中的每笔仍再 publish 一次，重复请求重绘 | 明确由谁负责重绘，删除重复调用 |
| S-4 | `core/infinite/CanvasCommands.kt:164` | `OnCanvasSync` 以 `Command by inner` 委托全部接口，而 `OnCanvas` 只覆写 redo/undo，两者报告 `touched()`／`added()` 不一致 | 统一两个包装器的委托规则 |
| S-5 | `ui/InfiniteEditor.kt:416` | 矢量图片解码线程仍直接读 `item.image.file`（可变字段），与本批消除跨线程读取模型的目标不符；代次检查通常丢弃错误结果 | 与 rect／orientation 等一起先捕获 `val file = item.image.file`。一行修复，优先修 |
| S-6 | `gl/CanvasScene.kt:1097` | `Edit.SyncChanged` 更新 `record.sync` 但不递增 `revision`，与其他修改记录的路径不同；目前无缓存依赖，显示不出错 | 一并递增 `revision` |

## U：master test2 模拟器冒烟测试的体验问题

环境：master test2 APK（`eed318ac`，SHA-256 `3ad2cc3a…acf1`），Android 12 / API 32 arm64 模拟器，显示临时覆盖为 2160×1350 / 240dpi（约 1440×900 dp，与 MovinkPad Pro 14 逻辑尺寸一致）。主要流程（书写、保存、画布、画布拒绝进入分屏、两笔记分屏、关闭一侧、退出分屏后显示与书写）均正常，无崩溃、无 ANR、无黑块。以下不影响使用。

| # | 现象 | 原因／判断 | 建议 |
| --- | --- | --- | --- |
| U-1 | 书写过程中系统弹出「已从剪贴板粘贴」 | 编辑器检查剪贴板是否有可粘贴图片（`controller.clipboardHasImage` → `clipboardImageUri()`），触发 Android 12+ 读取提示；可能为上游原有行为 | 只在用户打开粘贴菜单时读取，或改用 `ClipDescription` 判断类型 |
| U-2 | 分屏时共用工具栏右端「重做」被截半 | 工具栏可横向滚动，截断处为滚动边缘，非布局错误；但没有可滚动提示 | 加渐隐边缘或溢出指示 |
| U-3 | 单屏打开过的笔记进入分屏后页面右侧贴边、不居中 | 沿用该文档记住的缩放；对调左右窗格验证，现象随文档而非窗格位置 | 窗格宽度变化时重新适配或居中 |
| U-4 | 新建画布后停在列表，新建笔记直接打开 | Backstage 创建对话框有意「点了才打开」，新建笔记走完整创建页；行为不一致 | 统一两者行为 |

## 优先级

P-1、S-5 改动最小、收益明确，宜优先修。其余可与 D-1～D-7 一起在回迁收尾时处理。U 组为体验改进，可按需安排。
