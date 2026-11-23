# iPadOS 第二波契约（2026-11-24）

用户要求：按 [iPadOS 移植方案](ipados-migration-plan-2026-11-23.md) 推进 M0–M2，由 gpt-6.2-astra 并行实现，主代理写契约与测试。
状态：W2-1 已通过 Android JVM、ARM64 iOS 模拟器与两端编译验证；其他波次在各自工作树验证。
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

### W2-1 金样来源与复现

`tools/interop/AndroidBundleGoldens.kt` 是历史 Android JVM 测试生成器，不参加当前编解码器的构建。在仓库根目录，设置 `JAVA_HOME` 为 JDK 21 后，对两个**空目录**执行：

```sh
mkdir -p tmp/w2-legacy tmp/legacy-fixtures
git archive a07e803e -- app gradle gradlew build.gradle.kts settings.gradle.kts gradle.properties | tar -x -C tmp/w2-legacy
cp tools/interop/AndroidBundleGoldens.kt tmp/w2-legacy/app/src/test/java/com/xnotes/format/
export XNOTES_GOLDEN_DIR="$PWD/tmp/legacy-fixtures"
tmp/w2-legacy/gradlew -p tmp/w2-legacy :app:testDebugUnitTest --tests com.xnotes.format.AndroidBundleGoldens.generate
tmp/w2-legacy/gradlew -p tmp/w2-legacy :app:testDebugUnitTest --tests com.xnotes.format.AndroidBundleGoldens.verifyLegacyRoundTrip
```

生成器固定文档时间和 UUID，调用旧 `DocumentCodec` / `CanvasCodec` 正常保存与重开，记录解出的 UUID、页序和各未压缩条目 CRC。`inkDigest` 在 ZIP 顺序下读取 `manifest.json`、`map.json`：对每个 `samples` 数组从 `[` 到 `]` 的原 UTF-8 字节按出现顺序连续计算 SHA-256，不经数值解析再打印。`verifyLegacyRoundTrip` 另行通过旧模型快照正常保存，核对条目顺序、压缩方法与 CRC。已用 `a07e803e` 的 JVM 编解码器通过这两项测试并登记五对文件；未来验证仅比较，不覆盖已提交的金样。**无易变条目**：五个包的所有条目内容在原版重存中均稳定；ZIP 物理头时间戳不属于条目内容，无须豁免。

### W2-1 实现边界

- `commonMain` 负责笔记和画布的读写、模板包、SVG/XFDF、图层与导图清单、引用与页编号、不可变快照、原字节升级与复制、PDF 内嵌与外部化、ZIP 尾部拼接和增量 PDF。顺序读取和按中央目录读取保持独立；对比两种条目视图、元信息、指纹和写回结果，不以重编码后的模型代替原始文件。
- 导图的编辑、布局、命中、投影、剪贴板和来源解析共享；实际 SQLite 登记、通过 Android 编辑器打开来源与回跳留在 `:app`。共享存储负责版本与模型核验、只读页目录、创建许可和迁移日志的落盘与解释。SAF、同文件探测、PDF 资源租约的实际发放、恢复选择、交接凭据的验证和向原件发布仍在 Android 宿主；共享的来源、目录或日志记录均不能授权覆盖原件。
- Android 继续使用 JDK ZIP/XML 和文件描述符适配；iOS 使用 Okio、zlib 与系统 XML 解析器。对同一金样，两端除内容和笔迹摘要外，还逐项比对重存后的物理条目顺序及 STORED/DEFLATED 方法。另有双端用例覆盖 CRC、数据描述符、ZIP64、尾随字节、半途取消及清理。需要观察借用的 JVM 通道位置、文件系统时间戳、Android 发布与恢复的测试仍保留在相应宿主。

### W2-1 验证结果

JDK 21、Gradle 8.13、AGP 8.12.3、Kotlin 2.2.21、Okio 3.10.2、Xcode 27.2。一次执行下列六项任务，结果为 `BUILD SUCCESSFUL in 4m 59s`：

| 任务 | 结果 |
| --- | --- |
| `:shared:testDebugUnitTest` | 2,552 项通过 |
| `:shared:iosSimulatorArm64Test` | 2,323 项通过 |
| `:app:testDebugUnitTest` | 1,781 项通过 |
| `:shared:compileKotlinIosArm64` | iOS 设备目标编译通过，未在设备运行 |
| `:app:assembleDebug` | Android Debug 验证构建通过，未交付 |
| `:app:compileDebugAndroidTestKotlin` | 仪器测试编译通过，未在设备运行 |

三份测试报告均为零失败、零错误、零跳过。两端各执行五项原始 `BundleInteropTest` 和一项物理布局比对。按方法名对照，迁移前 `format/`、`map/`、`storage/` 下的 886 个测试方法均出现在本次共享 JVM 或应用 JVM 报告中。

独立工作树未合并 W2-3 / W2-4。运行时临时移开其六个 RED 测试文件（`PencilStrokeAssemblerTest`、`PencilGesturesTest`、`IosStorageLocationsTest`、`CgRendererTest`、`MetalInkTest`、`IosAppManifestTest`），跑完后原样归还并核对与 Git 无差异；没有改动、禁用或提交删除这些测试。上述结论只针对 W2-1，合并后的全波次联测与真机验收仍待进行。

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

## W2-4 实现记录

- `shared/src/iosMain/kotlin/com/xnotes/platform/ios/`：`CgSurface` 用左上原点的 sRGB 位图，各绘制器共享像素、独立保存变换与裁剪。支持非零／奇偶填充、三次曲线、多边形裁剪、固定像素宽虚线、整组透明度与正片叠底／滤色、一次填充的变径圆头笔迹、位图裁切／自拷贝，以及经 ImageIO 缩采样的图片旋转、翻转与裁切。Core Image 高斯光晕不保证与 Android 的 Skia 模糊逐像素相同；没有移植 AndroidSVG 的可见切片和图片缓存。
- `MetalInk` 在运行时编译 MSL，按提交顺序将索引三角形绘入离屏纹理，支持逐顶点颜色和透明混合，返回非预乘 ARGB。不同于 Android 的常驻分块缓冲，此入口每次上传前在双精度下减去视口原点；着色器按笔宽偏移将不足半像素的半宽推至阈值并同比降低 alpha，零偏移的填充不变。
- 复现：先启动有 Metal 的模拟器，再运行 `./gradlew :shared:iosSimulatorArm64Test -PiosTestDevice=<模拟器-UDID>`。Kotlin 默认的 standalone 模式实测取不到 Metal 设备，原 4 项 Metal 契约均失败；改用已启动模拟器的图形服务后原样通过，没有添加跳过或 CPU 替身。并行实现尚未合入时，本地临时 Gradle 初始化脚本只从本次编译排除 `BundleInteropTest`、`PencilStrokeAssemblerTest`、`PencilGesturesTest`、`IosStorageLocationsTest`；`IosAppManifestTest` 属于未运行的共享 Android 单测目标。五个文件均未移动、修改或删除，未提交任何排除规则。
- 验证：Xcode 27.2／iOS 27.2 ARM64 模拟器上，原 CoreGraphics 12 项、Metal 4 项和新增边界 8 项全部通过；连同既有共享核心共 1,693 项，失败／跳过均为 0。`:shared:compileKotlinIosArm64`（设备目标只编译）、`:app:testDebugUnitTest`（2,425 项，失败／跳过均为 0）、`:app:assembleDebug` 通过。未安装、推送或交付包。
- 第三波待接：文字布局、旋转文字和基线文本片段（`drawText`、`drawTextTurned`、`drawTextRun`），公式及占位（`drawMath`、`drawMathPending`），屏幕 `CAMetalLayer`、湿墨和交互预览。模拟器离屏像素结果不代表 iPad GPU 显示或 Apple Pencil 延迟验收。
