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
- **W3-D 书写整合**：Pencil 书写、橡皮、撤销、保存接入 iPad 编辑器；契约见下文「W3-D」。

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

## W3-D 书写整合（`:iosKit`、`shared`）

前提：W3-A、W3-B、W3-C 已合入，查看器已通过五份金样的日夜间截图验收（见文末）。

- **打开与保存接入**：首页改用 W3-C 的 `LiteStore`，删除 W3-A 的临时只读打开器 `ReadOnlyLiteStore`。拒绝原因的提示沿用。查看器去掉「只读」标记，改为可编辑。
- **Pencil 进笔迹**：`UITouch.type == .pencil` 才书写；手指只平移、滚动、缩放（设置里有「手指绘画」开关，默认关，开启时手指也书写）。笔接触屏幕期间忽略手指（防误触）。触摸经 `PencilSample` 转换后进入 `PencilFeed`（契约见下），放行的采样再交给 `PagedStrokeCapture`。分页笔记的 `PageSpace` 用查看器的页面布局；无限画布用一个覆盖整个内容空间的单页 `PageSpace`，不分页、不交接。湿墨显示 `PagedStrokeCapture.live` 加 `PencilFeed.wetTail()`；正式笔画只来自 `finish()` 的分段，入页并作为一个撤销步骤。
- **`PencilFeed`**（`shared/src/commonMain/kotlin/com/xnotes/pen/pencil/`）：`PencilFeed(pressureSupported, settleMs = 40)`；`begin(sample): Released`（首点立即放行，以便落笔时选页）；`move(coalesced, predicted): List<Released>`；`update(index, force, maxForce): List<Released>`；`end(last): List<Released>`；`cancel()`；`wetTail(): List<Released>`（仍在排队的采样加上本轮预测）。`Released(x, y, pressure, timeMs)`，`timeMs` 为 `timestamp × 1000` 取整。力度仍是估计值的采样最多等 `settleMs`（相对最新采样的时间），等到更新就用新压力；队首未放行时后面的采样也不放行，保持顺序；超时后用估计值放行，迟到的更新忽略；抬笔放行全部。无压感时不等待，压力恒为 1.0。压力换算与 `PencilStrokeAssembler` 相同。测试：`shared/src/commonTest/kotlin/com/xnotes/pen/pencil/PencilFeedTest.kt`。
- **工具**：笔、荧光笔、橡皮（按 `ToolConfig` 的擦除方式，复用 `core` 现有的整笔擦除与局部擦除函数；画布用 `EraseSession`）、撤销、重做。工具栏只用黑白与 `Tokens`，触控区不小于 48 dp；用户可见文字放入 `Localizable.strings`（英文与简体中文）。双击、捏压经 W2-3 的 `PencilGestures`（跟随系统偏好），笔画中收到的手势在抬笔后生效，取消则丢弃。笔画中途不切换工具。
- **撤销**：每次抬笔（含跨页的多段）一步，用 `core` 的 `History` 与现有命令（`AddItem`、`CompositeCommand`、擦除命令）。
- **保存时机**：返回首页时保存；场景进入后台时先保存，再在仍有未保存修改时调用 `writeRecovery()`；停笔 3 秒后自动保存。正在书写时不保存，保存时等抬笔。保存失败或冲突时用一条黑白提示说明结果和受保护副本所在，不丢弃修改。启动时若有待恢复记录，首页顶部提示「恢复」或「放弃」。
- **验收**：
  - 单元测试：`PencilFeedTest` 及全部既有测试（共享 JVM、iOS 模拟器、原生库、Android 应用）。
  - 模拟器（经 Device 面板）：打开「手指绘画」后，在分页笔记和画布上各画两笔（分页笔记中有一笔跨页）；撤销、重做、橡皮各操作一次，返回首页后再打开，确认内容保留；截图记录。模拟器不产生 Pencil 事件，Pencil 路径只能由单元测试与真机覆盖。
  - 真机（待用户在 iPad Air + Apple Pencil Pro 上验收）：压感、延迟、悬停光标、双击与捏压、防误触、后台被杀后的恢复。

## 共同规则

- 见 [第二波契约](ipados-wave2-contracts-2026-11-24.md)「共同规则」与 AGENTS.md「iPadOS」一节：JDK 21；iOS 测试先打开模拟器，再带 `-PiosTestDevice`；改动编解码须通过互通金样测试；不得削弱、跳过、删除测试；并行任务尚未实现的 RED 测试只能在本地临时隔离，不得提交隔离；不安装真机、不推送、不打包交付。
- 每个任务在本文末尾追加「W3-x 实现记录」（中文，仓库相对路径）。

## W3-B 实现记录

- `shared/src/commonMain/kotlin/com/xnotes/edit/PagedStrokeCapture.kt` 从 `73f664f0` 的分页控制器抽出首点、落笔缩放、直线荧光笔贴轴、屏幕间距抽稀、稳定器与笔尖，以及跨页分段。`PageSpace` 提供当前页布局与坐标变换；跨页时把上一段末点按原压力、原相对时间转入新页坐标，页缝中不换页。
- Android 的 `InteractionController` 先用原始落点确定可写页，再将尺子吸附后的点传入捕获器，即使首点被投到页外也不重选页。颜色、清选区、按住成形、魔棒淡出、前缓冲、入页与简化、速度笔跨段宽度拼接、撤销仍由宿主负责。正常抬笔把整笔记为一步撤销；取消不提交当前尾段，已入页的前段仍可撤销。
- JDK 21 下，原样运行 `PagedStrokeCaptureTest`：Android JVM 和 iPad Pro 13 英寸（M5）模拟器各 15 项通过。`:shared:testDebugUnitTest` 共 2,595 项、`:shared:iosSimulatorArm64Test` 共 2,391 项通过。共享测试编译时仅用未提交的初始化脚本隔离并行任务的 `LiteStoreTest.kt` 与 `CgTextTest.kt`，未改动测试源文件；此结果不代表完整第三波已验证。
- `:app:testDebugUnitTest` 全部 1,784 项通过，含新增的三项 Android 宿主事件回归：跨页抬笔一步撤销／重做、取消仅保留已入页段、尺子将首点吸出页外。`:app:compileDebugAndroidTestKotlin` 与 `:app:assembleDebug` 通过。MovinkPad Pro 14／Android 15／Pro Pen 3 笔迹仪器测试及前缓冲显示**待真机回归**；未安装、推送、打包交付或发布。

## W3-C 实现记录

- `shared/src/commonMain/kotlin/com/xnotes/storage/lite/` 提供分页笔记与无限画布的打开、保存、后台恢复和明确丢弃。打开时只解码私有快照，不写原件；严格检查包类型、身份、页编号及 PDF 原件，保留旧笔迹采样。文稿目录以外的路径、符号链接、独立导图、分屏文件、损坏及未来版本的包不作为可写文档打开。未改动的保存不创建暂存文件，也不替换 inode。
- 编辑后冻结模型；在文稿根目录的同卷隐藏文件使用共享编解码器写出，记录写出字节的 SHA-256，再回读暂存核对摘要、UUID、页序、成员类型与 PDF 原件摘要。发布前再次核对原件的设备号、inode 和完整摘要；iPad 在 `NSFileCoordinator` 授予写访问期间完成末验与原子替换，不跟随已改名的目标。返回成功只确认该次快照，生成快照后发生的编辑保持未保存。Android 应用没有改用此协议。
- `writeRecovery()` 只由宿主在后台对脏会话调用。正文与记录以同一私有目录原子发布，记录携带相对文稿路径、打开时的实体与摘要、正文摘要；恢复仍用原凭据，不因路径现在可读而取得覆盖权。成功保存清理该会话的记录；失败与冲突保留私有副本；旧恢复、未认领、损坏及未来版本资料不被发现流程删除。`close()` 释放本次解码的工作资源，不隐式保存或丢弃未认领资料。
- 失败边界：私有正文写成但校验或记录登记失败时，保留未授予覆盖权的原始候选，不替换上一份可恢复记录。若原件和私有副本**都不能写入**，无法返回一个确实存在的本次副本路径：`save()` 抛出 `LiteProtectionException`，保持 dirty，保留先前的恢复资料；宿主应提示并允许重试，不得关闭会话或报告已保护。
- 双端验证：原 `LiteStoreTest` 10 项未改；新增 `LiteStoreEdgeTest` 13 项，包括画布图片／导图／笔迹、PDF 与批注原字节、原件同字节换 inode、同身份暂存换正文、发布中继续编辑、原件目录改名、冷恢复冲突、双重写入失败及损坏恢复保留。五类 W2-1 金样和 ZIP 布局测试原样通过，未重新生成。JDK 21：`:shared:testDebugUnitTest` 2,603 项，`:shared:iosSimulatorArm64Test`（iPad Pro 13 英寸 M5／iPadOS 27.2 模拟器）2,399 项，`:app:testDebugUnitTest` 1,781 项，均零失败、零跳过；`:app:assembleDebug` 通过。
- 并行阶段仅在未提交的 `tmp/` Gradle 初始化脚本中隔离 W3-A 的 `CgTextTest.kt`、W3-B 的 `PagedStrokeCaptureTest.kt`；未修改两者，也不代表全波次联测。W3-D 仍需在输入稳定后串行使用同一会话并接入后台回调；「文件」App 并发操作、真实存储压力、后台终止及性能待 iPad 真机验收。未安装应用、推送或交付安装包。

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

## W3-A 截图验收（主代理，2026-11-24）

- 环境：iPad Pro 13 英寸（M5）/ iPadOS 27.2 模拟器，合并 W3-A、W3-B、W3-C 后的 `ipados` 构建；五份互通金样复制到「文稿/金样」，经 Device 面板依次打开，日间、夜间各一张，共十张，存于已忽略的 `tmp/w3-a-acceptance/`。
- `note-ink-text-pages`：首页左上角显示金样里那条约 3 × 12 px 的粉色笔画，流式文字「ページ <一> & two」正常排版；夜间纸面反为黑色、文字反白，笔迹颜色不变。
- `note-layers`：默认层笔画显示；上层「訂正」在金样中设为隐藏（`visible = false`），其 x = 200 的笔画按预期不显示。
- `note-pdf-annotated`：金样 PDF 是空白页，页面左上角显示灰色高亮标注，与金样一致。
- `canvas-ink-image`：网格背景、笔画、1 × 1 图片与直线连接线显示；夜间网格与连接线反色，笔迹颜色不变。
- `canvas-mind-map`：根节点「枝」带圆角外框，节点内手写笔画显示在标题下方。
- 结论：五类文件均能打开并按内容显示，日夜间符合黑白规则。金样内容极少，只能证明解码与绘制通路；版面细节（页边、页间距）与 Android 的逐像素对照以及真机 GPU 显示仍待在 iPad 真机上核对。
