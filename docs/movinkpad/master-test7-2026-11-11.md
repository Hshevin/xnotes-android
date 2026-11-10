# 主线独立测试版 test7（2026-11-11）

用户要求：拉取更新并快速打包测试版 APK，不覆盖正式版，自动推送打包日志。
状态：ARM64 APK 构建与安装身份核验通过；14 项定向 JVM 回归通过；待 Android 运行时与真机验证。
本轮边界：本地交付测试 APK，推送记录与日志；不发布 Release、不上传 APK、不安装用户设备。

- 主线由 `b07a19ea` 快进至 `e4739bd6`；独立工作树 `tmp/master-test2/source` 打包，不修改业务源码，保留原有三个本地修改。
- 包含 [文件身份校验修复](presentation-file-identity-fix-2026-11-11.md)：避免 Android creationTime 回退为 mtime 导致自身写入误报变化，补充原件库入口失败提示与恢复边界。
- 包名 `com.xnotes.movinkpad.branchtest.master`；版本 `0.9.8-test-master-7` / versionCode 7，标签 `XNotes master 测试版`。与正式版并存，会升级已有 master 测试版。
- 文件：`build/branch-tests/master-test7/XNotes-master-e4739bd6-test7-arm64.apk`。SHA-256：`ab2eb58cf5d563d8ffa709de77121b879ca6d2a405929faf94191c7f1c459121`。
- 与 test6 比对包名及签名一致，仅 ARM64、非 testOnly，16 KB ZIP 对齐通过。Debug 构建可调试；正式版版本与签名配置不改动。
- 证书 SHA-256：`A0:45:3B:7B:E6:C0:C8:23:9E:86:16:20:86:12:C4:12:CF:50:1D:A8:89:8C:7A:04:50:CC:92:3C:8E:35:84:51`。
- JDK 21，独立 ARM64 Gradle 项目缓存。命令：`./gradlew --offline --no-daemon --max-workers=2 --project-cache-dir tmp/gradle-arm64 -I ../build.init.gradle -I <主工作区>/scripts/branch-preview.init.gradle -PbranchTestId=master '-PbranchTestLabel=XNotes master 测试版' -PbranchTestVersion=7 :app:assembleDebug :app:testDebugUnitTest --tests com.xnotes.ui.PresentationFileIdentityTest --tests com.xnotes.pen.ColdMainPresentationHostTest`。
- 本轮 14 项定向 JVM 测试通过（文件身份 6 项、冷恢复宿主 8 项），失败／错误／跳过均为 0。按快速交付范围未重跑全量 JVM、Android 仪器、lint 或真机测试；JVM 不证明 Android 文件系统行为，不能宣称 test6 的 76 项 Android 失败已全部修复。
- [构建日志](build-logs/master-test7/build.txt)、[APK 核验](build-logs/master-test7/verification.txt)、[校验和](build-logs/master-test7/SHA256SUMS.txt)。日志已替换本机路径并扫描密钥模式，原始日志和 JVM XML 留在本地交付目录。
- 登记表 v3 的旧版回装限制仍适用，见 [回装边界](reference-registry-version-policy-2026-11-10.md)。
