# 主线独立测试版 test6（2026-11-11）

用户要求：拉取更新并打包测试版 APK，不覆盖正式版，自动推送打包日志。
状态：APK 构建与签名核验通过；JVM 4,777 项全部通过，Android 模拟器 439 项中 76 项失败；待真机验证。
本轮边界：本地交付独立测试 APK，推送文档与日志，不上传 APK、不发布 GitHub Release、不安装用户设备。

- 从 `8ee33799` 快进至 `c1d6d5a5`，使用干净的独立工作树 `tmp/master-test2/source`。不修改业务源码，原有三个文件的本地修改单独保护并恢复。
- 包名 `com.xnotes.movinkpad.branchtest.master`，versionCode 6，标签 `XNotes master 测试版`。沿用原签名，与正式版独立，已有 test5 升级至 test6。
- 包含思维导图、来源引用与文件关系网络、Map＋笔记混合分屏、保存与显式重开及最后布局冷恢复，以及主线审核修复。未完成范围见 [接续计划](mind-map-next-plan-2026-11-11.md)。
- 安装后测试包登记表升级到 v3，不能直接回装旧测试构建；回退前需应用导出完整备份并处理恢复资料，再清除该测试包数据。正式版独立身份不受影响。见 [回装边界](reference-registry-version-policy-2026-11-10.md)。
- JDK 21 / Gradle 8.13 / AGP 8.12.3 / SDK 36 / Build Tools 36.0.0 / NDK 27.0.12077973 / CMake 3.22.1。交付 ARM64 Debug（debuggable），非压缩 Release，无 IDE ABI 注入。
- ARM64 构建在独立工作树运行 `./gradlew --offline --no-daemon --max-workers=2 --project-cache-dir tmp/gradle-arm64 -I ../build.init.gradle -I <主工作区>/scripts/branch-preview.init.gradle -PbranchTestId=master '-PbranchTestLabel=XNotes master 测试版' -PbranchTestVersion=6 :app:testDebugUnitTest :app:assembleDebug`。
- 模拟器使用 `tmp/gradle-emulator` 缓存、`build/emulator-app/` 和 `build/emulator-cxx/` 输出，附加主工作区 `tmp/branch-tests/emulator.init.gradle`，运行 `:app:assembleDebug :app:assembleDebugAndroidTest`，仅 x86_64。
- 归档 [构建日志](build-logs/master-test6/build.txt)、[模拟器构建](build-logs/master-test6/emulator-build.txt)、[Android 测试](build-logs/master-test6/instrumentation.txt)、[APK 核验](build-logs/master-test6/verification.txt)，替换本机路径、清理行尾空白并检查密钥模式。完整 JVM XML 与原始日志保留在本地 `build/branch-tests/master-test6/`。
- 未运行完整上游 lint、真实 AI 供应商和目标设备验收；模拟器不能替代 MovinkPad 真实笔、防掌触、Surface/GPU、SAF 和进程终止恢复验收。

## 本轮结果

- ARM64 构建成功（4 分 10 秒，含 JVM）；JVM 4,777 项，失败、错误、跳过均为 0。模拟器 APK 与测试 APK 构建成功（40 秒）。
- Android 模拟器 API 35 / x86_64，SwiftShader、1600×1000，本轮启动时确认 Launcher 获得焦点，清空的是模拟器测试包夹具；运行 439 项、失败 76 项，最终报告 `FAIL`。失败涉及导图关系对话框等待、来源与恢复界面、PDF 原件读取、分屏保存与状态断言等，完整栈见 Android 测试日志。失败原因尚未逐项定位，不能将其全部归因于模拟器，也不能声称仅剩历史浮动工具栏输入注入问题；本轮不修改业务代码或测试来消除失败。
- 交付 `build/branch-tests/master-test6/XNotes-master-c1d6d5a5-test6-arm64.apk`，57,036,932 字节，版本 `0.9.8-test-master-6` / code 6。仅 ARM64，非 testOnly，16 KB ZIP 对齐核验通过。
- 与 test5 比对确认包名和签名一致；证书 SHA-256：`A0:45:3B:7B:E6:C0:C8:23:9E:86:16:20:86:12:C4:12:CF:50:1D:A8:89:8C:7A:04:50:CC:92:3C:8E:35:84:51`。
- APK SHA-256：`d0ca9fe7c38da54eedbff21b5a4c975882a9ebda55ef7a3a5132b685d84b7670`。记录见 [SHA256SUMS](build-logs/master-test6/SHA256SUMS.txt)。
- 此包供测试，不代表通过全部回归或真机验收；不发布 Release。
