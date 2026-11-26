# iPadOS W1-A：共享核心与便携 I/O（2026-11-24）

用户要求：按 [iPadOS 移植方案](ipados-migration-plan-2026-11-23.md) 建立 `:shared`，实现 `com.xnotes.kio`，迁移整个核心和原有测试，保持 Android 行为与工具链不变。
状态：已验证（Android 主机单元测试、ARM64 iOS 模拟器测试、Android Debug 构建、iOS 设备目标与 Android 仪器测试编译）；未做真机验收。
本轮边界：仅本地提交；`assembleDebug` 用于构建验证，不交付 APK、不安装设备、不推送、不发布。

## 模块与平台边界

- `:app` 依赖 `:shared`。原 `app` 中的 198 个核心源码文件已迁出；模型、几何、笔迹、历史、文本、搜索和编解码所用的纯算法在 `commonMain`。另迁入这些模型直接依赖的画布几何、导图模型、文档身份与存储消息；未迁移应用编辑器或整套文档编解码器。
- 工具链沿用 JDK 21、Gradle 8.13、AGP 8.12.3、Kotlin 2.2.21、Android SDK 36 / Build Tools 36.0.0。新模块使用 Okio 3.10.2。没有升级 Android 依赖，也没有在这一纯核心阶段接入 Compose Multiplatform UI。
- 文件身份取自设备号和 inode。原子写在目标目录排他创建临时文件，关闭缓冲输出、同步文件描述符后再改名；写入失败重新抛出异常并清理临时文件。iOS 读写句柄直接在原描述符上执行 `pread`、`pwrite` 和 `fsync`。
- 两端都用 Okio 写原始 DEFLATE。iOS 的 Okio 解压路径在截断输入上没有满足契约，因此 iOS 读取使用系统 zlib，必须得到 `Z_STREAM_END`；JVM 保留 Okio 读取路径。
- 文件资产在 Android 保持 `java.io.File`，iOS 使用 `okio.Path`。锁、弱引用、按对象身份建表、Unicode 标准化、分词、搜索任务调度与模板数字格式按需分平台实现。Android 的文档身份仍提供原 JSON / ZIP 入口；iOS 不因迁移数据模型而获得原文件写权限。
- Markdown 围栏语言和链接规则两端共用。完整的 Markdown 导入仍在 `androidMain` 使用原 commonmark-java 0.30.1 与原扩展；没有给 iOS 提供一个近似解析器。iOS 替代方案按原计划留待后续阶段。

## 验证与覆盖范围

- 联合运行 `./gradlew :shared:allTests :shared:compileKotlinIosArm64 :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --continue`；最终 `BUILD SUCCESSFUL`。
- `:shared`：Android JVM Debug / Release 各 1,898 项；ARM64 iOS 模拟器 1,669 项。三份报告的失败、错误和跳过均为 0；均含原样保留的 18 项 `KioContractTest`。两端还验证共享的副本恢复、写前打开来源和跨窗格缩略图代次。
- `:app:testDebugUnitTest`：2,425 项，失败、错误、跳过均为 0。对照迁移前核心测试的方法名，1,963 个原测试均在 Android JVM 的 `shared` 或 `app` 报告中找到。依赖 Android 编解码器和应用资源的集成用例在 `shared/src/androidUnitTest/appIntegration`，由 `:app` 执行，避免 `:shared` 反向依赖应用。共用测试替身不进入生产代码。
- Android 仪器测试、iOS 设备目标只完成编译，未安装应用或运行真机事件测试。不能据此判定 MovinkPad 输入显示、iPad GPU 或 Pencil 兼容性；本轮也没有交付 iPad 应用或验证跨端整包往返。
