# iPadOS 首页 H1 契约（2026-11-24）

上级计划：[iPadOS 首页对齐 Android](ipados-home-plan-2026-11-24.md)
状态：S1 交 gpt-6.2-astra 实现；界面由主代理在 iosKit 实现

## S1 浏览区纯逻辑搬进 shared（gpt-6.2-astra）

目标：Android 浏览区的排序、分组、日期与大小文字、视图选项模型只保留一份实现，放在 `shared` 公共代码，Android 与 iPad 共用。

范围（现有 Android 代码，行为一律不变）：

- `app/src/main/java/com/xnotes/ui/ExplorerSort.kt`：`explorerComparator`。
- `app/src/main/java/com/xnotes/ui/ExplorerGroups.kt`：`EntryKind`、`Hue`、`ExplorerWords`、`EntryGroup`、`DateBucket`、`dateBucket`、`groupEntries`、`hueName`、`hueOf`、`clockText`、`formatWhen`、`formatFull`、`openedLabel`、`formatSize`、`countsLabel`、`itemsLabel`。
- `app/src/main/java/com/xnotes/settings/ExplorerView.kt` 的枚举与 `ExplorerView` 数据类，以及 `Settings.kt` 里的 `ExplorerSortKey`。

要求：

1. 新包 `com.xnotes.explorer`（`shared/src/commonMain`）。日期用已加入的 `kotlinx-datetime` 0.7.1（`TimeZone`、`LocalDate`；`Month`、`DayOfWeek` 在 JVM 上即 `java.time` 类型）。不得引入其他依赖，不得用 `java.*`、`String.format`。
2. 条目抽象成公共接口 `ExplorerEntry`（`name`、`isDir`、`size`、`modified`、`created`、`color: Rgba?`）；Android 的 `BrowseEntry` 实现它。分组与排序函数对 `ExplorerEntry` 泛型，返回的组里保留调用方的具体类型。
3. `ExplorerView` 的 JSON 读写：公共代码里不碰 `org.json`；Android 的 `toJson`/`fromJson` 留在 app 端（扩展函数或 `Settings.kt` 内），字段名与默认值不变，已存的设置读出来完全一样。
4. app 端只留薄适配：现有 `app/src/test/java/com/xnotes/ui/ExplorerGroupsTest.kt`、`ExplorerSortTest.kt` 及其他 app 测试**一个字都不改**且全部通过（它们传入 `java.time.ZoneId`、读取 `DateBucket.start` 等，需要的话由 app 端同名函数转换后调用共享实现）。app 中其余调用点改为使用共享实现，不保留第二份逻辑。
5. 在 `shared/src/commonTest/kotlin/com/xnotes/explorer/` 新增公共测试，覆盖：比较器（四种键、升降序、文件夹在前、名称兜底）；`dateBucket` 各档（今天、昨天、本周早些、上周、本月早些、今年其他月、往年，含未来时间按今天算、周起始日）；`groupEntries` 四种分组及日期组排序方向；`formatWhen` 三种样式与 12/24 小时；`formatSize` 各档（999 B、1000 B→1 KB、1.5 MB、GB）；`countsLabel` 省略零项；`hueOf` 黑白灰与各色相边界。测试在 JVM 与 iOS 模拟器都跑。
6. iPad 列表 `shared/src/iosMain/kotlin/com/xnotes/storage/ios/IosDocumentList.kt`：`IosDocumentEntry` 增加 `size`（字节，文件夹为 0）、`createdAtMillis`（取文件创建时间，取不到为 null）、`childCount`（文件夹内可显示条目数，即同一过滤规则下的条目数；文件为 null），并实现 `ExplorerEntry`（`created` 取不到时用 `modified`，颜色暂为 null）。保留现有过滤规则（隐藏「.」开头、符号链接、未知类型）。补 iOS 测试。

验收（实现方在本文末尾追加「S1 实现记录」，中文，列测试数，不写本机绝对路径）：

- `:shared:testDebugUnitTest`、`:shared:iosSimulatorArm64Test`（需 `-PiosTestDevice=<UDID>`，模拟器已启动）、`:app:testDebugUnitTest`、`:app:assembleDebug`、`:iosKit:iosSimulatorArm64Test` 全部通过，零失败零跳过。
- `git diff` 中 `app/src/test` 无改动。
- 构建需 `JAVA_HOME=/opt/homebrew/opt/openjdk@21`。只做本地提交，不推送。

## H1 界面（主代理）

对照 Android 平板截图（已忽略目录 `tmp/w4-merged/android-*.png`）：

- 左侧浮动侧栏（xnotes 标题、收起按钮；首页及其下文件夹树、分屏工作区、AI、恢复资料、最近；分隔线下回收站、偏好设置、关于），可收成图标窄栏。iPad 未有的功能（分屏、AI、最近、回收站、偏好设置、关于）先置灰，点了提示尚未推出；随后续段落逐项启用。
- 浏览区顶部：首页图标 + 位置名、搜索（H2）、布局切换（H1 只有网格、列表）、视图选项与更多（H2/H3）。下方芯片行：排序（网格时）、按日期分组；右侧计数（「1 个文件夹 · 2 个文件」，列表加总大小）。
- 网格：文件夹卡片（图标、名称、条目数、⋮）、日期分组标题（「今天 2」）、文件磁贴（缩略图、类型角标、名称、时间、⋮）。列表：名称、类型、页数、大小、修改时间列，表头可点排序。
- 右下角圆形新建按钮，菜单：新建笔记、新建画布、导入 PDF、新建文件夹；空文件夹显示插画与三个大按钮。
- 新建笔记／画布／文件夹先弹命名框（Android 的新建笔记页含封面、纸张、模板，列入 H3）。
- 恢复资料页：列出待恢复记录，可恢复或放弃（取代首页顶部的待恢复行）。

## S2 iPad 导入 PDF（gpt-6.2-astra）

目标：iPad 首页「导入 PDF」把用户从「文件」选中的 PDF 变成当前文件夹里的一本 `.xnote` 批注笔记，与 Android `createPdfNoteFile` 产物一致（Android 可直接打开、批注、互通）。

要求：

1. `LiteStore` 增加 `fun importPdf(directory: Path, baseName: String, pdf: Path): LiteOpen`（`shared` 公共代码；PDF 页尺寸的读取走 iOS 现有 PDFium 绑定 `com.xnotes.nativelibs`，必要时以 expect/actual 或参数注入，公共代码不依赖 iOS 类型）。行为：
   - 页面规则与 Android `app/src/main/java/com/xnotes/platform/Pdf.kt` 的 `PdfImporter.import`/`pages` 完全一致：每个 PDF 页一页，尺寸 = 点数 / 72 × dpi（`PageSize.DEFAULT_DPI`），`pdfPage` 为页序；读不出或小于 1 点的页沿用前一页尺寸（第一页则 A4 竖向）；零页 PDF 返回 `LiteOpen.Refused`（新增 `LiteRefusal.PDF_UNREADABLE`），加密且无法打开的同样拒绝。
   - 文档开启引用锚点（同 `createLiteDocument`），PDF 原样嵌入（`doc.pdfFile`），由现有 `LiteCodec` 写出；命名、去重（`名称 (2)`）、目录与 Documents 范围检查、暂存再链接的原子发布流程全部复用 `createLiteDocument` 的做法（可重构为共用，不复制粘贴）。`decoded.pdfHash` 校验相应调整为非空且与写入的 PDF 一致。
   - 源 PDF 只读，不移动不修改；导入失败不留下任何文件（含暂存目录）。
2. Android 新建笔记的默认样式（`stampNewNoteDefaults`：新笔记页面样式、模板、正文样式）属于 Android 偏好，iPad 暂无，按 `Document.blank()` 的默认处理，记录在实现记录里。
3. 测试（`shared/src/iosTest` 或 commonTest，按依赖放）：用仓库里已有的测试 PDF（`shared/src/commonTestFixtures` 或 `nativelibs` 测试资源中找；没有就用 PDFium 生成或加入一个很小的 2 页不同尺寸 PDF 作为测试资源）验证：页数、各页尺寸与 `pdfPage`、重名去重、打开后 `LiteSession.note` 的 `pdfFile` 存在且页数一致、关闭后重新 `open` 读回一致、用 `DocumentCodec` 读回与 Android 读取路径一致、零页/损坏 PDF 被拒绝且目录无残留。
4. 不改 `app/`，不改 `app/src/test`。

验收：`:shared:testDebugUnitTest`、`:shared:iosSimulatorArm64Test`（`-PiosTestDevice=<UDID>`）、`:iosKit:iosSimulatorArm64Test`、`:app:assembleDebug` 通过，零失败零跳过；在本文末尾追加「S2 实现记录」（中文，测试数，不写本机绝对路径）。

## S2 实现记录

- 首页可调用 `LiteStore.importPdf(directory: Path, baseName: String, pdf: Path): LiteOpen`。导入成功返回已打开的批注笔记。导入与新建共用名称及 Documents 范围检查、重名编号、暂存回读校验和独占硬链接发布流程。
- 导入只读所选文件，先取得带 SHA-256 的私有快照。iOS PDFium 按文件读取快照，编解码器把同一份 PDF 原字节嵌入笔记。发布前核对嵌入原件的摘要、文档与页身份。零页、损坏、需要密码的 PDF 返回 `LiteRefusal.PDF_UNREADABLE`。失败后清理会话资产和发布暂存目录。
- 每个 PDF 页均取得永久页编号，按默认 150 dpi 将点数换算为页面尺寸。读不到尺寸或任一边小于 1 点时沿用前一页尺寸，首页回退为 A4 竖向。此处按 S2 的页序要求保留 `pdfPage`。Android 现有实现将这类回退页的 `pdfPage` 留空。页面样式、模板和正文样式沿用 `Document.blank()` 默认值，iPad 暂无 Android 的新建笔记样式偏好。
- 新增 6 项 iOS 回归测试，覆盖两页不同尺寸与分数点数、独立身份与重名、回退尺寸、移除所选文件后保存和重开、用共享 `DocumentCodec.readComplete` 读回、零页／损坏／密码保护拒绝，以及嵌入前快照被替换时拒绝发布并清理。
- JDK 21、iPad Pro 13 英寸（M5）iOS 27.2 模拟器验证通过。`:shared:testDebugUnitTest` 2711 项，`:shared:iosSimulatorArm64Test` 2519 项，`:iosKit:iosSimulatorArm64Test` 34 项，另验 `:nativelibs:iosSimulatorArm64Test` 6 项。均为零失败、零错误、零跳过。`:app:assembleDebug` ARM64 构建通过。首页文件选择器接入属于 H1 界面工作，本次未做真机操作。
