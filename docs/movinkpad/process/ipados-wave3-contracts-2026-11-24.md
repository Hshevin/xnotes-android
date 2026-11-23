# iPadOS 第三波契约（2026-11-24）

用户要求：第三波做到 M1–M2（打开查看、Pencil 书写、保存）。编辑器采用折中方案：先在 iPad 做精简编辑器尽快上真机，同时把笔画等纯判断逐块抽到共享模块、两端共用，每块都要求 Android 现有测试原样通过；保存协议用精简版。见 [iPadOS 移植方案](ipados-migration-plan-2026-11-23.md)。
状态：契约与 RED 测试已写，待实现
本轮边界：只做本地提交；不推送、不打包、不安装到真机、不发布

## 已确认决定

- 编辑器：折中（C）。iPad 精简编辑器先支持笔、荧光笔、橡皮、撤销重做、分页滚动或画布平移缩放、保存；选择、文本编辑、图层面板、导图、AI、录音、分屏不在本波。
- 共享抽取：本波只抽笔画捕获（`PagedStrokeCapture`）。之后按需再抽橡皮、选择等。
- 保存：精简协议（W3-C），格式、文档 UUID、页编号规则与 Android 相同；SAF 交接、外部来源、登记表本波不做。

## 分工

本波四个任务。W3-A、W3-B、W3-C 并行；W3-D 等这三个合入后开始。

- **W3-A 查看器**：打开、只读显示、文字绘制。
- **W3-B 共享笔画捕获**：在共享模块实现笔画捕获，并让 Android 改用它。
- **W3-C 精简保存**：`LiteStore` / `LiteSession`。
- **W3-D 书写整合**：Pencil 书写、橡皮、撤销、保存接入 iPad 编辑器；契约在前三项合入后编写。

## W3-A 查看器（`:iosKit`、`shared/src/iosMain`）

- 首页点按笔记或画布即打开（经 W3-C 的 `LiteStore.open`；W3-C 未合入前可先用一个同名接口的临时只读实现，合并时换成正式实现）。拒绝打开时按原因显示可读提示（损坏、旧版导图不再支持、未来版本），文件保持原样。
- 分页笔记：页面纵向排列，可滚动、双指缩放；页面内容经 `CgSurface` + `core/pal` 绘制为页缓存位图。PDF 页底用 `:nativelibs` 的 PDFium 渲染。模板背景、笔迹、图片、流式文字都要显示。
- 无限画布：`CAMetalLayer` 上用 W2-4 的 Metal 管线绘制 `core/infinite` 网格，可平移、缩放；导图节点经 `MapProjection` 显示。
- 文字：`CgTextMeasurer`（`object`，实现 `TextMeasurer`）与 `CgRenderer` 的 `drawText` / `drawTextTurned` / `drawTextRun`，用 CoreText。字号换算与 Android 相同：像素 = 磅 × 150 / 72。公式先显示源码占位，在实现记录里注明。
- 夜间模式：沿用 AGENTS.md 的黑白规则，笔迹夜间反转规则沿用 `NightInk`。
- 测试：`shared/src/iosTest/kotlin/com/xnotes/platform/ios/CgTextTest.kt`；另在 iPad 模拟器上依次打开五个互通金样（复制进「文稿」），各截一张图并逐一说明看到的内容。日间、夜间各一组。

## W3-B 共享笔画捕获（`shared/src/commonMain/kotlin/com/xnotes/edit/`）

- `interface PageSpace { val zoom; fun pageAt(content): Int?; fun toPage(page, content): Pt; fun fromPage(page, local): Pt; fun hasPage(page): Boolean; fun writable(page): Boolean }`。
- `class PagedStrokeCapture(space)`：`begin(content, pressure, tool, config, timeMs, speedScale): Boolean`；`move(content, pressure, timeMs, force = false, rulerVisible = false): Handover?`；`finish(): List<Segment>`；`cancel(): List<Segment>`；只读 `active`、`live`、`page`；`companion` 中 `MIN_SAMPLE_DIST = 1.0` 与 `smoothScaleFor(zoom)`。`Handover(retired, fromPage, toPage)`；`Segment(stroke, page)`。
- 行为必须与 `app/.../canvas/InteractionController.kt` 在 `73f664f0` 的 `beginDraw` / `addStrokePoint` / `showTip` / `handOverStroke` 完全一致：不缩放笔按落笔时的缩放归一、荧光笔直线模式与贴轴、按屏幕间距抽稀、稳定器与笔尖、跨页交接与桥接点。不属于捕获的部分留给宿主：颜色解析、尺子吸附（宿主先吸附再传入）、按住成形、魔棒淡出、清除选区、前缓冲、入页与撤销命令。
- Android 的 `InteractionController` 改为委托 `PagedStrokeCapture`，行为不变。现有 Android 单元测试与仪器测试编译全部原样通过；涉及笔迹的仪器测试本机无法在 MovinkPad 上运行，须在报告中说明「待真机回归」。
- 测试：`shared/src/commonTest/kotlin/com/xnotes/edit/PagedStrokeCaptureTest.kt`（JVM 与 iOS）。

## W3-C 精简保存（`shared/src/commonMain/kotlin/com/xnotes/storage/lite/`）

- `LiteStore(fs, documents, privateDir, imageCodec, textMeasurer)`：`open(path): LiteOpen`（`Opened(session)` / `Refused(reason: LiteRefusal)`，原因含 `NOT_FOUND`、`UNSUPPORTED_KIND`、`FUTURE_VERSION`、`CORRUPT`）；`pendingRecoveries(): List<LiteRecovery(original, documentId, record)>`；`restore(recovery): LiteOpen`；`discard(recovery)`。
- `LiteSession`：`path`、`kind: LiteKind { NOTE, CANVAS }`、`documentId`、`note: Document?`、`canvas: InfiniteDocument?`、`dirty`、`markEdited()`、`save(beforePublish: ((Path) -> Unit)? = null): LiteSave`、`writeRecovery(): Boolean`、`close()`、`abandonForTest()`（模拟进程被杀：不保存，也不清理恢复记录）。
- `LiteSave`：`Saved`；`Failed(cause, protectedCopy)`；`Conflict(protectedCopy)`。
- 保存流程：未改动时直接返回 `Saved`，不重写文件。否则：核对原文件自打开以来未被替换（文件身份与内容摘要）→ 编码到同卷隐藏暂存 →（测试钩子 `beforePublish`）→ 重新读取暂存文件并核实文档 UUID、页编号与模型一致 → 原子替换 → 清除恢复记录。任一步失败都不碰原文件，在私有目录写一份受保护副本并返回 `Failed`；发现原文件已被别处替换时返回 `Conflict` 并同样保留副本。暂存文件在任何情况下都要清理。
- 恢复记录只在进入后台时由宿主调用 `writeRecovery()`，并且只在有未保存修改时写入。记录与正文都放在私有目录，不进「文稿」。恢复打开时不写原文件，会话标记为未保存。
- 旧版 `.xmap` 与混合 `.xsplit` v3 按 AGENTS.md 拒绝打开，文件保持不变。
- 测试：`shared/src/commonTest/kotlin/com/xnotes/storage/lite/LiteStoreTest.kt`（JVM 与 iOS；复用 W2-1 的互通金样）。

## 共同规则

- 见 [第二波契约](ipados-wave2-contracts-2026-11-24.md)「共同规则」与 AGENTS.md「iPadOS」一节：JDK 21；iOS 测试先打开模拟器，再带 `-PiosTestDevice`；改动编解码须通过互通金样测试；不得削弱、跳过、删除测试；并行任务尚未实现的 RED 测试只能在本地临时隔离，不得提交隔离；不安装真机、不推送、不打包交付。
- 每个任务在本文末尾追加「W3-x 实现记录」（中文，仓库相对路径）。

## W3-A 实现记录

- 首页可点开分页笔记、PDF 批注笔记与无限画布，点「返回」回到原目录。`iosKit/src/iosMain/kotlin/com/xnotes/iosapp/ViewerStore.kt` 是接入 W3-C 前的只读适配：`open(path)` 复制到本次会话的私有缓存，在副本上检查版本、引用信息和完整解码；关闭或拒绝时清理本次缓存，不保存原件。缺失、不支持（含旧版独立导图）、未来版本、损坏分别显示本地化提示。合并时用正式 `LiteStore.open` 替换临时适配，保留渲染资产直到视图释放。
- `PagedView.kt` 用 `UIScrollView` 纵向排列页面，支持滚动和双指缩放，只保留可见页位图。`PageImages.kt` 用 `CgSurface`、共享绘制器和 PDFium 合成纸面、模板（含包内图片）、PDF、批注、流式正文与可见图层。`CanvasView.kt` 用 `CAMetalLayer` 绘制网格和 `MapProjection` 节点，支持平移与缩放；图片及需特殊合成的对象先绘成透明纹理，按图层顺序与普通网格穿插提交。日夜跟随系统外观，按纸色应用 `NightInk`，不反相图片与 PDF。
- `shared/src/iosMain/kotlin/com/xnotes/platform/ios/CgText.kt` 用 CoreText 处理换行、字形替补、字宽、行度量和导图字形轮廓，字号按 `pt × 150 / 72`。`CgRenderer` 支持矩形文字、旋转及基线文字。公式暂显示 **LaTeX 源码**，不做数学排版。分页位图最长边 2560 像素、单个画布回退纹理最长边 2048 像素；深度放大不等于重新按屏幕分辨率栅格化。
- 验证：JDK 21；`:shared:iosSimulatorArm64Test` 2384 项、`:shared:testDebugUnitTest` 2580 项、`:app:testDebugUnitTest` 1781 项，均零失败、零跳过；`:app:assembleDebug` 和 Xcode 的 XNotes 模拟器 Debug 构建通过。iOS 包含 `CgTextTest` 4 项和 `ViewerRasterTest` 4 项（PDF 像素通道及旋转、文字旋转、中日韩字形轮廓、Metal 图层设备绑定与纹理覆盖顺序）。并行工作树尚无 W3-B、W3-C 实现，仅通过未提交的 `tmp/w3.gradle` 临时隔离它们的两份 RED 测试；本轮未改测试契约。这些是自动化与离屏像素结果，不代表首页手势或五份文档的上屏验收。
- **待截图验收**：目标 iPad Pro 13 英寸（M5）模拟器经 `device_open` 重新打开后，绑定的 `agent-device install` 仍返回 `DEVICE_IN_USE`。未接管其他会话，未复制金样、未在界面打开或截图；本轮没有可交付的截图。合并时按下述步骤补验。未安装到真机，GPU 上屏表现、手势流畅度仍待实机确认。

### 模拟器截图复验步骤

1. 在待验工作树从仓库根构建；在 T3 `device_list` 后用 `device_open(hostId="local", deviceId="3A53F8F7-6847-446E-81A4-174A76DBFB48")` 取得本线程绑定的 CLI，把该可执行文件路径设为 shell 变量 `AD`。设备仍被占用时停止，不接管。

```sh
UDID=3A53F8F7-6847-446E-81A4-174A76DBFB48
xcodebuild -project iosApp/XNotes.xcodeproj -scheme XNotes -destination "platform=iOS Simulator,id=$UDID" -derivedDataPath tmp/derived build
"$AD" install com.xnotes.ipad "$PWD/tmp/derived/Build/Products/Debug-iphonesimulator/XNotes.app"
DATA=$(xcrun simctl get_app_container "$UDID" com.xnotes.ipad data)
CASE=$(mktemp -d "$DATA/Documents/W3-A-XXXXXX")
FIXTURES=shared/src/commonTest/fixtures/interop
cp "$FIXTURES"/*.xnote "$FIXTURES"/*.xcanvas "$CASE/"
(cd "$CASE" && shasum -a 256 *.xnote *.xcanvas) > tmp/w3-a-before.sha256
"$AD" open com.xnotes.ipad --relaunch
"$AD" settings appearance light
```

2. 首页进入刚建的 `W3-A-…` 文件夹。每次 `"$AD" snapshot -i` 读取当前控件，`"$AD" press @eN --settle` 点对应文件或「返回」（用当次快照的实际引用替换 `@eN`）。每份开好后通过 T3 `device_screenshot` 看实际屏幕，用 `"$AD" screenshot "$PWD/tmp/w3-a-<文件主名>-day.png"` 留图。执行 `"$AD" settings appearance dark` 重复一遍，文件后缀改 `-night.png`。结束后返回首页并还原原外观；`(cd "$CASE" && shasum -a 256 -c "$OLDPWD/tmp/w3-a-before.sha256")` 校验副本未变。
3. 下表是**来自金样的检查点，不是截图观察结果**。每行日夜各一图；额外滚动、平移或放大核对细节。

| 文件主名 | 待核验内容 |
| --- | --- |
| `note-ink-text-pages` | 两个纵排页面、粉色笔迹、首面「ページ <一> & two」；滚动可达第二面，夜间黑纸文字可读。 |
| `note-pdf-annotated` | 空白 PDF 白底及左上角半透明灰色高亮；夜间底稿仍白。 |
| `note-layers` | 左上方可见层的粉色笔迹；放大检查隐藏层的第二笔未出现。 |
| `canvas-ink-image` | 网格上有笔迹、位图及连接线；平移缩放后相对位置不变，夜间不把位图作为黑墨反转。 |
| `canvas-mind-map` | 圆角节点、标题「枝」、节点内粉色手写；夜间边框与标题可辨，白色节点填充转为近黑。 |
