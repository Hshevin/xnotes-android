# 主线独立测试版 test5（2026-11-08）

用户要求：拉取更新并打包测试版 APK，不覆盖正式版，自动将打包日志推送远端。
状态：打包成功，2849 项 JVM 测试通过；Android 138 项中 1 项失败，新增调整的目标设备验收待执行。
本轮边界：本地交付独立测试 APK，推送文档和日志，不发布 GitHub Release、不上传 APK、不安装用户设备。

- 从 `60d477b3` 快进到 `fdc1f0fe`，正式版本不变。复用干净的独立工作树 `tmp/master-test2/source`，切换至本轮源码，ARM64 与模拟器仍使用不同构建目录及项目缓存。
- 测试身份 `com.xnotes.movinkpad.branchtest.master` / versionCode 5，标签 `XNotes master 测试版`；与正式版并存，已有主线 test4 更新为 test5。数据和权限独立，正式版笔记请经应用导出便携包再导入测试版。
- 更新包含无限画布图层侧栏改为覆盖而不缩放 Surface、图层编辑与撤销重做按差异原子更新、隐藏层几何保留而在绘制时过滤。本轮不改产品或测试源码。远端 Release 98 的真机验收记录作为历史证据保留，不扩大为本次新增调整的验收。
- JDK 21 / Gradle 8.13 / AGP 8.12.3 / SDK 36 / Build Tools 36.0.0 / NDK 27.0.12077973 / CMake 3.22.1。ARM64 Debug 测试版，沿用原签名，无 IDE ABI 注入。
- ARM64 命令（独立工作树）：`./gradlew --offline --no-daemon --max-workers=2 --project-cache-dir tmp/gradle-arm64 -I ../build.init.gradle -I <主工作区>/scripts/branch-preview.init.gradle -PbranchTestId=master '-PbranchTestLabel=XNotes master 测试版' -PbranchTestVersion=5 :app:testDebugUnitTest :app:assembleDebug`。
- 模拟器使用 `tmp/gradle-emulator` 缓存、`build/emulator-app/` / `build/emulator-cxx/` 输出，附加主工作区 `tmp/branch-tests/emulator.init.gradle`，运行 `:app:assembleDebug :app:assembleDebugAndroidTest`，仅 x86_64 回归。
- 日志：[构建](build-logs/master-test5/build.txt)、[模拟器构建](build-logs/master-test5/emulator-build.txt)、[仪器测试](build-logs/master-test5/instrumentation.txt)、[APK 核验](build-logs/master-test5/verification.txt)。归档替换本机路径、清理行尾空白，不含密钥与密码；完整 XML 和 APK 核验资料留在本地 `build/branch-tests/master-test5/`。
- 原有三处本地修改单独保护并恢复，不纳入提交。未执行完整上游 lint、真实供应商或 MovinkPad 真机验收；模拟器 EGL／窗口结果不代表目标 GPU、笔延迟或真实前缓冲显示通过。渲染审查文档的尚未处理项原样保留。



## 最终结果与未解决项

- ARM64 构建成功（1 分 2 秒），2849 项 JVM 测试通过，失败／错误／跳过均为 0；模拟器构建成功（40 秒）。
- Android 15 / API 35 x86_64、SwiftShader、1600×1000 本机模拟器执行 138 项、1 项失败（137 项通过）。启动前解除锁屏并确认 Launcher 焦点，没有 System UI 无响应弹窗。
- 仍失败：`PaneChromeHostTest.floatingToolbarInterceptsWindowStylusBeforeNativeContent` 的 `injectWindowStylus` 目标窗口 UID 不匹配异常，与前版相同。本轮不修改源码绕过失败，未标记为全量通过。新增侧栏覆盖与图层闪烁效果仍需目标设备验证。
- APK `com.xnotes.movinkpad.branchtest.master` / `0.9.8-test-master-5` / versionCode 5，仅 ARM64、Debug（debuggable）、无 testOnly；与正式版包名不同，与 test4 原签名一致。证书 SHA-256 `a0453b7be6c0c8239e8616208612c412cf501da8898c7a0450cc923c8e358451`；验签及 16 KiB ZIP 页对齐通过。
- 文件 `build/branch-tests/master-test5/XNotes-master-fdc1f0fe-test5-arm64.apk`，53,921,964 字节；SHA-256 `4a603cce7ef308bc131f2136ff84b0c66770dce667f83d353d947c5ebaed166e`，与 Gradle 输出逐字节一致。
