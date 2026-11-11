# 主线独立测试版 test9（2026-11-11）

用户要求：拉取更新，打包独立测试 APK，不覆盖正式版，自动推送日志。
状态：ARM64 构建、4,802 项 JVM 测试及 APK 核验通过；待 Android 与真机验证。
本轮边界：本地交付 APK，推送日志与记录；不发布 Release、不安装用户设备。

- 源码 `d15fa5c0`，独立工作树 `tmp/master-test2/source` 构建；保留三个原有本地修改。
- 包含导图菜单、统一节点创建与来源流程、节点选中轮廓及集成修复，见 [功能记录](mind-map-menu-creation-2026-11-11.md)。
- 包名 `com.xnotes.movinkpad.branchtest.master`，版本 `0.9.8-test-master-9` / code 9。与正式版独立，会升级已有 master 测试版；登记表 v3 的旧版回装限制仍适用。
- APK：`build/branch-tests/master-test9/XNotes-master-d15fa5c0-test9-arm64.apk`。与 test8 签名一致，仅 ARM64、非 testOnly、16 KB ZIP 对齐通过，Debug 可调试。
- JDK 21；分支初始化脚本、`branchTestId=master`、`branchTestVersion=9`，独立 `tmp/gradle-arm64` 项目缓存；执行 `:app:assembleDebug :app:testDebugUnitTest`，耗时 3 分 14 秒。
- JVM 4,802 项，失败／错误／跳过均为 0。本轮未运行 Android 仪器、lint 或目标设备测试，不宣称历史平台失败全部修复。
- [构建日志](build-logs/master-test9/build.txt)、[身份与签名核验](build-logs/master-test9/verification.txt)、[SHA-256](build-logs/master-test9/SHA256SUMS.txt)。日志已替换本机路径并扫描密钥模式，原始日志与 XML 留在本地交付目录。
