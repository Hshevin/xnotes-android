# 主线独立测试版 test4（2026-11-08）

用户要求：拉取更新并打包测试版 APK，不覆盖正式版，自动将打包日志推送远端。
状态：打包成功，2843 项 JVM 测试通过；Android 138 项中 1 项失败，目标设备验收待执行。
本轮边界：本地交付独立测试 APK，推送文档和日志，不发布 GitHub Release、不上传 APK、不安装用户设备。

- 从 `54f05b0f` 快进到 `711470e8`，正式版本不变。复用干净的独立工作树 `tmp/master-test2/source`，切换至本轮源码，ARM64 与模拟器仍使用不同构建目录及项目缓存。
- 测试身份 `com.xnotes.movinkpad.branchtest.master` / versionCode 4，标签 `XNotes master 测试版`；与正式版并存，已有主线 test3 更新为 test4。数据和权限独立，正式版笔记请经应用导出便携包再导入测试版。
- 更新包含打开／新建画布时等待首帧期间保留菜单（最多 600 ms）、首帧前纸面颜色覆盖及无尺寸帧清屏；夜间使用深色纸面避免闪白。源码来自远端，本轮不改产品或测试逻辑。黑屏闪烁效果尚未通过逐帧录屏或目标 GPU 验收。
- JDK 21 / Gradle 8.13 / AGP 8.12.3 / SDK 36 / Build Tools 36.0.0 / NDK 27.0.12077973 / CMake 3.22.1。ARM64 Debug 测试版，沿用原签名，无 IDE ABI 注入。
- ARM64 命令（独立工作树）：`./gradlew --offline --no-daemon --max-workers=2 --project-cache-dir tmp/gradle-arm64 -I ../build.init.gradle -I <主工作区>/scripts/branch-preview.init.gradle -PbranchTestId=master '-PbranchTestLabel=XNotes master 测试版' -PbranchTestVersion=4 :app:testDebugUnitTest :app:assembleDebug`。
- 模拟器使用 `tmp/gradle-emulator` 缓存、`build/emulator-app/` / `build/emulator-cxx/` 输出，附加主工作区 `tmp/branch-tests/emulator.init.gradle`，运行 `:app:assembleDebug :app:assembleDebugAndroidTest`，仅 x86_64 回归。
- 日志：[构建](build-logs/master-test4/build.txt)、[模拟器构建](build-logs/master-test4/emulator-build.txt)、[仪器测试](build-logs/master-test4/instrumentation.txt)、[APK 核验](build-logs/master-test4/verification.txt)。归档替换本机路径、清理行尾空白，不含密钥与密码；完整 XML 和 APK 核验资料留在本地 `build/branch-tests/master-test4/`。
- 原有三处本地修改单独保护并恢复，不纳入提交。未执行完整上游 lint、真实供应商或 MovinkPad 真机验收；模拟器 EGL／窗口结果不代表目标 GPU、笔延迟或真实前缓冲显示通过。渲染审查文档的尚未处理项原样保留。


## 最终结果与未解决项

- ARM64 构建成功（59 秒），2843 项 JVM 测试通过，失败／错误／跳过均为 0；模拟器构建成功（39 秒）。
- Android 15 / API 35 x86_64、SwiftShader、1600×1000 本机模拟器执行 138 项、1 项失败（137 项通过）。运行前解除锁屏并确认 Launcher 焦点，无 System UI 无响应弹窗。
- 仍失败：`PaneChromeHostTest.floatingToolbarInterceptsWindowStylusBeforeNativeContent` 的 `injectWindowStylus` 目标窗口 UID 不匹配异常，与 test3 相同。未修改源码绕过失败，未将本轮标记为全量通过。新增打开画布闪烁方案未做逐帧录屏与目标设备验收。
- APK `com.xnotes.movinkpad.branchtest.master` / `0.9.8-test-master-4` / versionCode 4，仅 ARM64、Debug（debuggable）、无 testOnly；与正式版包名不同，与 test3 原签名一致。证书 SHA-256 `a0453b7be6c0c8239e8616208612c412cf501da8898c7a0450cc923c8e358451`；验签、16 KiB ZIP 页对齐通过。
- 文件 `build/branch-tests/master-test4/XNotes-master-711470e8-test4-arm64.apk`，53,916,036 字节，SHA-256 `9d68c1cb9ee46793670835936f1c26e3f813f61b373951cb0e37540fadfc88bb`，与 Gradle 输出逐字节一致。
