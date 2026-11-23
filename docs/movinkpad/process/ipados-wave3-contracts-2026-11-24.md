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
