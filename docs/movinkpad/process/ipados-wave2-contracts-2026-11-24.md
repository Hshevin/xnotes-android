# iPadOS 第二波契约（2026-11-24）

用户要求：按 [iPadOS 移植方案](ipados-migration-plan-2026-11-23.md) 推进 M0–M2，由 gpt-6.2-astra 并行实现，主代理写契约与测试。
状态：契约与 RED 测试已写，待实现
本轮边界：只做本地提交；不推送、不打包、不安装到真机、不发布

## 分工调整

`storage/`、`map/` 依赖 `format/`，所以原 W2-1 与 W2-2 合并为一个任务。iOS 存储位置随应用宿主一起由 W2-3 实现。第二波三路并行：

- **W2-1**：`format/`、`storage/`、`map/` 的纯逻辑进入 `:shared` 的 `commonMain`，并加跨平台互通金样。
- **W2-3**：iPad 应用宿主：`:iosKit` 框架模块、`iosApp/` Xcode 工程、Compose Multiplatform 宿主、iOS 存储位置、Pencil 输入纯逻辑。
- **W2-4**：`core/pal` 的 CoreGraphics 实现与 Metal 网格绘制。

三者都改 Gradle 构建文件，由主代理合并时解决冲突。各任务不改其他任务负责的目录。

## W2-1 共享编解码与存储

- 移动 `app/src/main/java/com/xnotes/format/**`、`storage/**`、`map/**` 中不依赖 Android 的部分到 `shared/src/commonMain`，包名、类名不变。`java.io` 流改为 Okio `Source` / `Sink`，`File` 改为 `okio.Path`，`FileChannel` 改为 `KFileHandle`，`MessageDigest` 改为 `Sha256`，`ZipFile` / `ZipInputStream` 改用 `ZipTail` 加 `RawDeflate`（或 Okio 的 zip 支持）。依赖 Android 的部分（SAF、ContentResolver、`android.system.Os`、`platform/` 中的会话缓存）留在 `app`，或者通过接口注入。
- 迁移只改 IO 类型，不改格式与行为：条目名称、顺序、压缩方式选择、清单字段、原笔迹字节、UUID 与页编号规则全部不变。现有 `format/`、`storage/`、`map/` 测试随源码迁移，不得削弱断言；依赖 Android 文件系统的测试留在仪器测试。
- 互通金样：用迁移前的 Android 编解码器（提交 `a07e803e` 的 `app` 代码，在 JVM 单元测试里运行一个生成器）写出 `shared/src/commonTest/fixtures/interop/` 下的五类文件：`note-ink-text-pages.xnote`、`note-pdf-annotated.xnote`、`note-layers.xnote`、`canvas-ink-image.xcanvas`、`canvas-mind-map.xcanvas`。同时记录每个文件的 `<名>.summary.txt`。生成器与生成步骤写入本文。金样一经提交，不得用迁移后的编解码器重新生成。
- 实现 `com.xnotes.format.interop.BundleInterop`（`commonMain`，用无界面的图像与文字测量替身解码）：
  - `summarize(name, bytes): BundleSummary`：解出文档种类、文档 UUID、按顺序的页面永久编号、条目数、笔迹采样摘要 `inkDigest`（对全部笔画的原始采样字节求 SHA-256），以及每个 ZIP 条目解压后内容的 CRC。`canonical()` 输出按行排序的稳定文本。
  - `reencode(name, bytes)`：解码后不做修改，按正常保存路径写回。
  - `VOLATILE_ENTRIES`：保存时理应变化的条目（如修改时间），最多两个，在本文中逐项说明原因。
- 测试：`shared/src/commonTest/kotlin/com/xnotes/format/interop/BundleInteropTest.kt`，在 JVM 与 iosSimulatorArm64 上都要通过。`expect fun interopFixtureNames()` / `interopFixture(name)` 由实现者在两端测试源集里补 actual。

## W2-3 iPad 应用宿主

- 新建 KMP 模块 `:iosKit`（仅 iosArm64、iosSimulatorArm64），依赖 `:shared` 与 `:nativelibs`，导出静态框架 `XNotesKit`，并在这里引入 Compose Multiplatform。不得在 `:shared` 的 Android 目标或 `:app` 引入 Compose Multiplatform，也不升级 Android 侧依赖。若 Compose Multiplatform 与 Kotlin 2.2.21 不兼容，停下报告。
- `iosApp/XNotes.xcodeproj` 与 `iosApp/XNotes/`：Swift 只有应用入口（`UIApplicationDelegate` / `UISceneDelegate` 把 `XNotesKit` 提供的根 `UIViewController` 装进窗口）。应用名 XNotes，Bundle ID `com.xnotes.ipad`，仅 iPad（设备族 2），最低 iPadOS 18，支持多场景与全部方向，`UIFileSharingEnabled` 与 `LSSupportsOpeningDocumentsInPlace` 为真。不提交签名团队，签名在本机 Xcode 里选择。构建阶段通过 Gradle 生成框架（`embedAndSignAppleFrameworkForXcode` 或等效脚本）。
- 根界面：Compose 首页，列出「文稿」目录下的笔记与文件夹（名称、类型、修改时间）。本波不打开文档，也不复用 Android `ui/`；`ui/` 的复用在第三波进行。
- `com.xnotes.storage.ios.IosStorageLocations`（`shared/src/iosMain`）：`documents`（用户笔记，在「文件」App 可见）；`private`、`noBackup`、`cache`（都在 `Library/` 下，首次访问时创建，`noBackup` 设置 `NSURLIsExcludedFromBackupKey`）；`stagingFor(dir)`（与目标同卷，放在「文稿」内时以点号开头隐藏）；`isExcludedFromBackup(path)`。
- Pencil 输入纯逻辑 `com.xnotes.pen.pencil`（`shared/src/commonMain`），契约见 [Apple Pencil 输入契约](ipados-pencil-input-contract-2026-11-23.md)。本波只实现纯逻辑与 `UITouch` → `PencilSample` 的转换函数，画布宿主在第三波接入。
- 测试：`PencilStrokeAssemblerTest`、`PencilGesturesTest`（commonTest，两端运行）、`shared/src/iosTest/.../IosStorageLocationsTest.kt`、`shared/src/androidUnitTest/.../IosAppManifestTest.kt`。另外 `xcodebuild -project iosApp/XNotes.xcodeproj -scheme XNotes -destination 'generic/platform=iOS Simulator' build` 必须成功，并在 iPad 模拟器上启动后截图确认首页显示。

## W2-4 CoreGraphics 与 Metal 绘制

- `com.xnotes.platform.ios.CgSurface` / `CgSurfaceFactory`（`shared/src/iosMain`）：实现 `RasterSurface` / `SurfaceFactory`，内部是 CoreGraphics 位图上下文，左上角为原点、y 轴向下。另外提供 `pixel(x, y): Rgba`，返回非预乘值，供测试读取。`renderer()` 实现 `core/pal.Renderer` 的全部抽象方法，语义对照 `app/.../platform/AndroidRenderer.kt`：变换栈、裁剪、两种填充规则、cosmetic 笔宽、虚线、`saveLayerAlpha` / `saveLayerBlended`（MULTIPLY、SCREEN）、栅格绘制，以及 `fillDiskRibbon` 的圆头笔迹。文字与公式在本波可以先留空实现，但必须在本文列明，第三波补齐。
- `com.xnotes.platform.ios.MetalInk`：`createOrNull()`；`renderOffscreen(width, height, background, viewport: MetalViewport(originX, originY, scale), meshes: List<MetalMesh(MeshData, Rgba)>) : IntArray`（ARGB）。着色器语义对照 `app/.../gl/InkShader.kt` 等。MSL 源码放在 `shared/src/iosMain/resources` 或字符串常量里，运行时编译。屏幕上的 `CAMetalLayer`、湿墨与延迟在第三波接入。
- 测试：`shared/src/iosTest/kotlin/com/xnotes/platform/ios/CgRendererTest.kt`、`MetalInkTest.kt`。

## 共同规则

- 工具链：JDK 21（本机 `/opt/homebrew/opt/openjdk@21`；默认 `java` 是 25，Gradle 会失败）、Gradle 8.13、AGP 8.12.3、Kotlin 2.2.21、Xcode 27.2。
- 不得削弱、跳过或删除测试；做不到的测试停下说明原因。主代理写的契约测试中如有明显笔误，在报告中指出，不得自行改变断言含义。
- 每个任务都要跑 `:app:testDebugUnitTest` 与 `:app:assembleDebug`，确认 Android 不受影响。
- 不安装设备、不打包交付、不推送、不改 Git 配置。本地提交，提交信息末尾附 `Co-Authored-By: gpt-6.2-astra <noreply@openai.com>`。

## W2-3 实现记录

- `:iosKit` 仅在两个 iOS ARM64 目标使用 Compose Multiplatform 1.9.3，依赖 `:shared`、`:nativelibs`，生成静态框架 `XNotesKit`。Kotlin 保持 2.2.21；未改变 Android 依赖。`iosApp/XNotes` 的 Swift 场景入口为每个窗口创建独立的 Compose 首页；列表读取元数据，不打开笔记，支持进入文件夹、返回与刷新。
- `:shared` 的 `com.xnotes.pen.pencil` 负责采样、预测、估计压力修正和手势排队，iOS 提供 `UITouch` 的坐标转换入口；`com.xnotes.storage.ios` 管理文稿、私有、不备份、缓存和同卷暂存目录。尚未接入画布；压感、悬停、双击、捏压与实际延迟待真机验收。
- 构建：在仓库根目录运行 `xcodebuild -project iosApp/XNotes.xcodeproj -scheme XNotes -destination 'generic/platform=iOS Simulator' build`，或在 Xcode 打开该工程，选择 XNotes 和任一 iPadOS 18+ ARM64 iPad 模拟器后运行。构建阶段选择 JDK 21（非标准安装可指定 `XNOTES_JDK_21`）；无需重新生成 Xcode 工程。Xcode 27.2 模拟器构建与 `:iosKit:linkDebugFrameworkIosArm64` 均通过；未安装真机。
- 契约验证：Pencil 两类测试各平台均为 23 项；Android JVM 另有清单 5 项，iOS 模拟器另有目录 5 项和列表 1 项。Android `:app:testDebugUnitTest` 2,425 项通过，`:app:assembleDebug` 通过，无 APK 交付。仅在本地临时 Gradle 初始化脚本中隔离并行任务尚处 RED 的 `BundleInteropTest`、`CgRendererTest`、`MetalInkTest`；未移动或改写其源码，以上结果不代表第二波全部转绿。
- 在 iPad Pro 13 英寸（M5）／iPadOS 27.2 模拟器安装并启动 `com.xnotes.ipad`，刷新后确认文件夹、分页笔记和无限画布均显示中文类型与修改时间。截图 `tmp/w2-3-ipad-home.png`（空目录）、`tmp/w2-3-ipad-documents.png`（样例列表）仅留在本地工作树。
