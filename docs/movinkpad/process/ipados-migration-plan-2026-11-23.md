# iPadOS 移植方案（2026-11-23）

用户要求：把 XNotes 移植到 iPadOS，作为下一阶段里程碑。先定方案，不确定处询问；实现交给 gpt-6.2-astra，可并行多个子代理。主代理负责方案、契约与测试，并逐波复核合并。
状态：第一、二波已合入 `ipados` 并联测通过（2026-11-24：共享 JVM 2,580 项、iOS 模拟器 2,376 项、原生库 6 项、Android 应用 1,781 项，均零失败零跳过；`iosKit` 设备框架链接与 Android Debug 构建通过）；第三波见 [第三波契约](ipados-wave3-contracts-2026-11-24.md)
本轮边界：只做本地分支 `ipados` 与本地提交；不推送、不打包、不安装到真机、不发布

## 已确认决定

- 架构：Kotlin Multiplatform。新建 `:shared` 模块，`core/`、`format/`、`storage/`、`map/`、`ai/` 的纯逻辑进入 `commonMain`，Android 与 iPad 共用同一份代码。不用 Swift 重写。
- 界面：首页、设置、菜单、面板经 Compose Multiplatform 复用现有 `ui/` 代码；画布、湿墨、Apple Pencil、文本输入法用原生 UIKit + Metal。
- 平台代码语言：Kotlin/Native（`iosMain` 直接调用 UIKit、Metal、CoreGraphics、POSIX）。Swift 只保留应用入口；Metal 着色器用 MSL。
- 仓库：本仓库 monorepo，新增 `shared/`（KMP）与 `iosApp/`（Xcode 工程）。
- 存储：笔记存在应用「文稿」目录，并在「文件」App 中可见（`UIFileSharingEnabled`、`LSSupportsOpeningDocumentsInPlace`）。外部文件夹 / iCloud Drive 以后再议。
- 文件互通：两端完全互通。同一笔记包可在两端来回打开和保存，格式、文档 UUID、页面永久编号、笔迹原始字节不变。
- 本轮范围：M0–M2（见下文）。图层、导图、AI、录音、分屏下一轮再做。
- 测试设备：iPad Air（M2 及以后）+ Apple Pencil Pro。屏幕 60 Hz，延迟数据不能与 ProMotion 机型对比。
- 压感：笔不提供压感（如 USB-C 款、第三方笔）时按固定压力计算。
- 签名：只有免费 Apple ID。真机安装每 7 天失效，没有 TestFlight 与 iCloud 容器；真机安装仍需用户每次明确要求。
- 默认值（用户未另行指定）：最低 iPadOS 18；应用名「XNotes」；Bundle ID `com.xnotes.ipad`。

## 现状评估

- `core/` 198 个文件，没有 Android import，已有 `core/pal` 平台抽象（Renderer、Text、TextMeasurer、ImageCodec、RasterSurface）；其中 22 个文件使用 `java.*`。
- `format/`、`storage/`、`map/`、`ai/` 几乎没有 Android import，但大量依赖 JVM：`java.io.File`、`java.nio.channels.FileChannel`（含 mmap 与 FileLock）、`java.util.zip`、`MessageDigest`、`UUID`、`java.time`。`format/` 的 JSON 已是自写的 `JsonPull` / `JsonWrite`。
- `ui/` 107 个文件中 77 个只用 Compose；`canvas/`、`gl/`、`platform/` 与 Android 深度耦合（GLES、前缓冲、MotionEvent、SAF、Handler/Looper）。
- 原生库：pdfium（`tools/pdfium-sync.sh` 按 ABI 生成 CMake）与 tree-sitter（C）可编译到 iOS；pdfbox-android、androidsvg、commonmark-java 只能在 JVM / Android 使用，iPad 端需另行替代（M1 起按需处理）。
- 现有 JVM 单元测试约 430 个、仪器测试约 46 个，是 M0 的回归契约。

## 里程碑

### M0 共享层（只动 Android 构建，行为不变）

- 新建 `:shared` KMP 模块，目标：`androidTarget`（库）、`iosArm64`、`iosSimulatorArm64`。`:app` 依赖 `:shared`，包名与类名不变（`com.xnotes.core…` 等），Android 侧零行为变化。
- 便携 IO 层 `com.xnotes.kio`（契约见下文）替代 `java.*`；实现可基于 Okio 3.x、kotlinx-io 或 expect/actual，但对外只暴露契约中的接口与 Okio 的通用类型。
- 依次把 `core/`、`format/`、`storage/`（纯逻辑部分）、`map/`、`ai/`（纯逻辑部分）移入 `commonMain`；平台差异用 expect/actual，Android actual 保留现有实现。
- 测试：原有 JVM 测试全部照常通过（可随源码迁到 `shared/src/androidUnitTest` 或 `commonTest`，不得删改断言）；新增 `commonTest` 在 JVM 与 `iosSimulatorArm64Test` 上都运行。

### M1 iPad 只读查看

- `iosApp/` Xcode 工程 + Compose Multiplatform 宿主：首页文件列表、打开分页笔记、PDF 批注笔记与无限画布，只读显示。
- 绘制：`core/pal.Renderer` 的 CoreGraphics 实现（分页、缩略图、导出）；无限画布把 `gl/` 场景移植到 Metal（共用 `core/infinite` 的网格化）。
- PDF：pdfium 编译为 iOS 静态库 / xcframework，经 cinterop 接入，与 Android 版同一修订。
- 互通：Android 生成的金样文件在 iPad 端解码结果与 JVM 一致。

### M2 Apple Pencil 书写与保存

- Pencil 输入：coalesced 采样入笔迹，predicted 采样只画湿墨不入模型，estimated 属性（压力、角度）晚到时按 `estimationUpdateIndex` 修正；悬停（`zOffset`）显示工具光标；双击与捏压（`UIPencilInteraction`，遵从系统偏好动作）映射到工具或命令；`force` 不可用时固定压力。
- 编辑：分页笔记与画布上的书写、橡皮、撤销重做、保存，走现有受保护保存协议；进后台写恢复记录使用 `beginBackgroundTask`。
- 文件身份：iOS 用 POSIX `stat` 的 `st_dev` + `st_ino`，与 Android 规则相同；不用 `creationTime`。
- 验证：iPad 模拟器上的自动测试；Pencil 延迟、压感、悬停、捏压只能在真机上验收，单独记录。

## 并行分工

每个子代理在独立工作树中工作，完成后由主代理复核、合入 `ipados`。子代理不得削弱或删除测试；做不到的测试要报告原因，不得绕过。

### 第一波（现在开始，可并行）

- **W1-A 共享模块与 IO 层（关键路径）**：建 `:shared`；实现 `com.xnotes.kio` 并通过 `shared/src/commonTest/kotlin/com/xnotes/kio/KioContractTest.kt`（JVM 与 iosSimulatorArm64 都要过）；把 `core/` 整体移入 `commonMain`；`:app` 改为依赖 `:shared`；原有测试全部通过。
- **W1-B iOS 原生库**：新建仅 iOS 目标的 KMP 模块 `:nativelibs`，把 pdfium（同一修订）与 tree-sitter 及现有语法编译为 iosArm64 / iosSimulatorArm64 静态库，并经 cinterop 暴露最小 Kotlin 门面；通过 `nativelibs/src/iosTest/kotlin/com/xnotes/nativelibs/NativeLibsSmokeTest.kt`。不改 `app/` 与 Android CMake 产物。

### 第二波（W1-A 合入后，可并行；契约由主代理在第一波进行期间写好）

已调整为三路并行，详见 [第二波契约](ipados-wave2-contracts-2026-11-24.md)。下列为最初划分。

- **W2-1**：`format/` 进入 `commonMain`，跨平台金样互通测试。
- **W2-2**：`storage/`、`map/` 纯逻辑进入 `commonMain`；iOS 的原子发布、文件身份、目录列举 actual。
- **W2-3**：`iosApp/` 工程、Compose Multiplatform 宿主与文稿目录；Pencil 输入适配（纯逻辑在 `commonMain`，契约测试由主代理编写）。
- **W2-4**：CoreGraphics `Renderer` 与 Metal 画布场景。

### 第三波

- M1 查看器与 M2 编辑器整合（`Editor` / `InfiniteEditor` 的 iPad 宿主），契约在第二波合入后再写。

## W1-A 契约：`com.xnotes.kio`

所有接口在 `commonMain`，JVM（Android）与 iOS 行为一致。

- `Crc32`：可累加的 CRC-32（与 `java.util.zip.CRC32` 相同多项式）。`update(bytes, offset, length)`、`value: Long`、`reset()`；`Crc32.of(bytes): Long`。
- `Sha256`：`Sha256.hex(bytes): String`（小写十六进制）；可累加的 `Sha256()`，`update(bytes, offset, length)`、`hex(): String`。
- `RawDeflate`：无 zlib 头的原始 DEFLATE（对应 `Inflater(true)` / `Deflater(level, true)`）。`inflate(bytes): ByteArray`、`deflate(bytes, level = 6): ByteArray`。必须能解出 JVM 压出的字节；不要求两端压出的字节相同（格式迁移与复制保留原压缩字节，不重新压缩）。
- `Uuids`：`random(): String` 返回小写的 RFC 4122 v4 文本；`isValid(text): Boolean`，接受大小写。
- `KFiles`：`system: okio.FileSystem`，平台真实文件系统；`tempDirectory(): okio.Path`。
- `atomicWrite(fs, target, write: (okio.BufferedSink) -> Unit)`：在同一目录写临时文件，刷新并同步到磁盘后原子替换目标；写入过程抛出异常时目标保持原样，临时文件被删除，异常原样抛出。
- `fileIdentity(path): FileIdentity?`：`data class FileIdentity(val device: Long, val inode: Long)`，取自 POSIX `stat`（Android 用 `Os.stat` 或 NIO `fileKey` 的同一来源）。同一文件经不同路径（硬链接）相同；改名后不变；不同文件不同；不存在时返回 `null`。
- `KFileHandle`：随机读写，`size()`、`read(at, into, offset, length): Int`（末尾返回 -1）、`write(at, bytes, offset, length)`、`truncate(size)`、`sync()`、`close()`。由 `openReadWrite(path)` / `openRead(path)` 得到。`format/FileWindow`、`ZipTail` 迁移时改用它。
- 错误：IO 失败、损坏的压缩数据、只读句柄写入都抛 `KioException`（等于或继承 `okio.IOException`，JVM 上即 `java.io.IOException`，现有 `catch (IOException)` 不受影响）。`KFileHandle` 实现 `AutoCloseable`。
- 测试辅助：`commonTest` 声明 `expect fun createHardLinkForTest(existing, link)`，实现者在 JVM 与 iOS 测试源集里用 POSIX `link` 补上 actual。
- 进程内文件锁不属于本契约；`FileLock` 的替代在 W2-1 中按需要定义。

## W1-B 契约：`:nativelibs`

- 只含 iOS 目标；pdfium 修订与 `app/src/main/cpp/pdfium.cmake` 中的 `PDFIUM_REVISION` 一致，构建脚本可复现、不在构建时联网下载；生成方式写入 `tools/` 下的脚本并记录。
- 门面 `com.xnotes.nativelibs.Pdfium`：`open(bytes: ByteArray, password: String? = null): PdfiumDocument?`；`PdfiumDocument.pageCount`、`pageSize(index): Pair<Double, Double>`（PDF 点）、`render(index, width, height): IntArray`（ARGB，白底）、`close()`。
- 门面 `com.xnotes.nativelibs.TreeSitter`：`languages(): Set<String>` 至少含现有全部语法；`parseSExpression(language, source): String?` 返回根节点 S 表达式。
- 保留上游与第三方许可证声明。

### W1-B 本机构建

- 在 Apple Silicon Mac 上使用 Xcode 的 iOS SDK、CMake ≥ 3.22、Ninja 和 Python 3。运行 `bash tools/ios-native-build.sh iphoneos` 或 `bash tools/ios-native-build.sh iphonesimulator`，分别在 `nativelibs/build/native/<SDK>/` 生成 `libxnotespdf.a` 和 `libxnotests.a`，最低 iPadOS 18。可用第二个参数指定输出目录。
- 脚本从 Android 的固定修订清单读取 ARM64 与跨平台源码，改用 Apple SDK、PDFium Apple 后端和该平台的 ARM64 zlib 配置；语法源码和入口表来自 `CMakeLists.txt` 与 `ts_jni.c`。Android 裁剪时省略的同修订上游文件随 `nativelibs/src/native/upstream/` 入库。原生库编译不下载任何源码；输出目录的 `THIRD_PARTY.txt` 汇集原仓库版本和许可证声明，分发二进制时随附。
- 准备好 JDK 21、Gradle 8.13 与 Kotlin/Native 2.2.21 工具链和依赖缓存后，运行 `./gradlew --offline :nativelibs:iosSimulatorArm64Test :nativelibs:compileKotlinIosArm64`；Gradle 按目标调用上述脚本，静态库嵌入 cinterop KLIB。可传 `-PiosTestDevice=<模拟器 UDID>` 指定 iOS 18 或更高版本的模拟器。
- 本机 Xcode 27.2 验证：模拟器 4 项原始契约与 2 项边界测试全部通过；设备目标测试程序链接、Android `:app:assembleDebug` 通过。模拟器两份静态库在独立输出目录从头构建后逐字节一致。未在真实 iPad 上运行。

## 风险与待定

- Compose Multiplatform 与现有 Compose BOM、Kotlin 2.2.21 的版本对齐由 W1-A 核实；冲突时先报告，不升级 Android 侧依赖。
- Okio 在 Kotlin/Native 上的 zlib 与 zip 支持由实现者核实；不支持时用 `platform.zlib` cinterop。
- pdfbox-android（导出、批注写入）、androidsvg、commonmark-java、LaTeX 库在 iPad 端的替代方案在第二、三波逐项决定，届时询问用户。
- 免费 Apple ID 每 7 天需重新签名安装，最多 3 个应用；不影响模拟器开发。
