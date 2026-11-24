# iPadOS 首页 H3 契约（2026-11-24）

上级计划：[iPadOS 首页对齐 Android](ipados-home-plan-2026-11-24.md)
状态：S4、S5 交 gpt-6.2-astra 实现；界面由主代理在 iosKit 实现

## S4 按设定新建笔记与模板库（gpt-6.2-astra）

目标：iPad 的「新建笔记」页与 Android `NewNoteScreen` 一样可选纸张大小与方向、页面样式（纸色、模板）和封面；做出的 `.xnote` 与 Android `Editor.createNoteFile(…, NewNoteSetup)` 的产物一致。

Android 参照：`app/src/main/java/com/xnotes/ui/Editor.kt` 的 `NewNoteSetup`、`createNoteFile`（约 7480–7500 行）、`stampNewNoteDefaults`；`app/src/main/java/com/xnotes/platform/TemplateLibrary.kt`（内置、自带示例、导入三类，`embed`）；`app/src/main/java/com/xnotes/ui/NewNoteScreen.kt`、`TemplatePicker.kt` 了解界面要的数据；自带示例在 `app/src/main/assets/templates`。

要求：

1. `LiteStore` 增加按设定新建：`fun create(directory: Path, baseName: String, setup: LiteNoteSetup): LiteOpen`，`LiteNoteSetup(widthMm, heightMm, style: PageStyle, cover: PageStyle?)`。页数、DPI、封面（第 0 页、`cover = true`、页边距为 0）、引用锚点、`created` 与 Android 一致；命名、去重、原子发布复用现有 `create` 流程（重构共用，不复制）。Android 的新笔记正文样式偏好 iPad 暂无，按 `Document.blank()` 默认处理并记录。
2. 模板库公共化：`shared/src/commonMain` 新增模板库（名称自定），提供：所有可选模板（内置三种、自带示例、顶层文件夹 `.xnote/templates/` 里的导入模板；同 Android 的键规则：内置按 ruling id，其余按内容哈希），按键取模板，`embed(doc, key)` 把非内置模板写进文档（与 Android `embed` 相同：纯 JSON 进 `doc.templates`，带图包进 `doc.templatePackages`）。Android 的 `TemplateLibrary` 改为调用公共实现（Android 只留资源读取与文件位置），`app/src/test` 一个字都不改且通过。
3. 自带示例进 iPad：把 `app/src/main/assets/templates` 里的示例作为 iOS 应用资源（`iosApp` 工程拷贝阶段或 iosKit 资源，任选其一，写明），iPad 能列出、能嵌入。
4. 新建页的缩略预览需要「用某个样式画一页」：给出公共函数（或说明现有哪个函数）可在 iOS 用 `CgSurfaceFactory` 画出带纸色与模板的空白页。
5. 测试：commonTest（JVM 与 iOS）覆盖尺寸换算、封面、各类模板键、embed 前后文档读回一致（`DocumentCodec` 读回）、与 Android `createNoteFile` 同设定产物结构一致；iOS 测试覆盖自带示例可列出与嵌入。

## S5 iPad 导出 PDF（gpt-6.2-astra）

目标：iPad 首页条目菜单「导出 PDF」把一本笔记（含 PDF 批注笔记）或一张画布导出为 PDF 文件，交给系统分享面板。用 CoreGraphics PDF 上下文、复用现有绘制器；读屏用的标签结构不做。

Android 参照：`app/src/main/java/com/xnotes/platform/Pdf.kt` 的 `PdfExporter.export`（页序、纸色、模板、PDF 背景页按原样矢量绘入、批注在上、正文排版文字、书签可不做）。

要求：

1. `iosMain` 新增导出（建议 `com.xnotes.platform.ios.PdfExport`）：`fun export(session/doc, out: Path, onProgress, isCancelled): Boolean`。每页尺寸取页面像素按 DPI 换算成点；有 `pdfPage` 的页把源 PDF 对应页以矢量绘入（PDFium 或 `CGPDFDocument`，任选，写明）；纸色、模板、墨迹、形状、图片、文本框、正文排版用现有 CoreGraphics `Renderer` 画；荧光笔与半透明墨迹保持透明度。画布：按内容边界加边距导出成一页。
2. 写到临时文件，完成后原子改名；取消或失败删掉临时文件。
3. 测试（iosTest）：普通笔记多页尺寸正确、PDF 批注笔记的背景页来自源 PDF（页数、尺寸一致，可用 PDFium 读回）、画布一页、取消不留文件。

## 验收（S4、S5 各自）

`:shared:testDebugUnitTest`、`:shared:iosSimulatorArm64Test`（`-PiosTestDevice=<UDID>`）、`:iosKit:iosSimulatorArm64Test`、`:app:testDebugUnitTest` 通过，零失败零跳过；`:app:assembleDebug` 被自动审批拦下就写明。在本文末尾追加「S4 实现记录」「S5 实现记录」（中文，测试数，公开 API 最终签名，不写本机绝对路径）。只做本地提交。

## H3 界面（主代理）

- 视图选项面板（磁贴大小、缩略图形状、显示类型／页数／时间／大小／颜色、文件夹位置、紧凑行、按文件夹记住），画廊／分栏／时间线布局，捏合换磁贴大小。
- 新建笔记页（纸张、方向、模板、封面、名称）。
- 偏好设置（只放 iPad 已有的：用手指绘画、回收站保留天数、日期样式、显示扩展名、侧栏显示项）、关于。
- 条目菜单：分享（系统分享面板）、导出 PDF。拖动磁贴到文件夹移入。
