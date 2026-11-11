# 主线独立测试版 test8（2026-11-11）

用户要求：拉取最新更新，快速打包独立测试 APK，自动推送打包日志。
状态：ARM64 构建、4 项分屏发布 JVM 回归及 APK 身份签名核验通过；待真机验证。
本轮边界：本地 APK、推送文档与日志；不发布 Release、不安装用户设备。

- 源码 `6eb71cf9`，独立工作树 `tmp/master-test2/source` 增量构建；保留原有本地修改。
- 包含导图编辑、手写工具栏与分屏发布修复，见 [修复记录](mind-map-usability-repair-2026-11-11.md)。
- 包名 `com.xnotes.movinkpad.branchtest.master`，版本 `0.9.8-test-master-8` / code 8；与正式版独立，会升级已有 master 测试版。登记表 v3 回装限制沿用。
- 交付 `build/branch-tests/master-test8/XNotes-master-6eb71cf9-test8-arm64.apk`。与 test7 签名一致，仅 ARM64、非 testOnly、16 KB ZIP 对齐通过；Debug 可调试。
- JDK 21，使用 `scripts/branch-preview.init.gradle`、`branchTestId=master`、`branchTestVersion=8`，独立 `tmp/gradle-arm64` 项目缓存，执行 `:app:assembleDebug :app:testDebugUnitTest --tests com.xnotes.ui.SplitPublicationFallbackTest`。构建耗时 43 秒。
- 4 项定向 JVM 测试通过，无失败、错误或跳过；本轮未重跑全量 JVM、Android 仪器、lint 与真机验证，不宣称历史平台失败全部解决。
- [构建日志](build-logs/master-test8/build.txt)、[核验日志](build-logs/master-test8/verification.txt)、[SHA-256](build-logs/master-test8/SHA256SUMS.txt)。日志替换本机路径并扫描密钥模式；原始日志与 JVM XML 留在本地交付目录。
