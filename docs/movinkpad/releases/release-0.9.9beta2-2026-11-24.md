# 0.9.9beta2 本地正式版（2026-11-24）

用户要求：「打包正式版我用用」；版本名由用户定为 `0.9.9beta2`。
状态：Release JVM 4,412 项、R8、资源压缩、APK 验签与 16 KiB 对齐通过；已覆盖安装到 MovinkPad Pro 14（原 `0.9.12` / code 101），启动正常、无崩溃记录。
本轮边界：本地打包并安装；不推送、不发布 GitHub Release。

## 源码与版本

- 源码 `97b07d01`：`com.xnotes.movinkpad`，`0.9.9beta2` / versionCode 103。相对 0.9.9beta：同步上游 0.9.11、图层切换提速与逐层缓存、点选图层立即生效（去掉双击改名）。

## 构建与核验

- 隔离工作树 `tmp/release-0.9.9beta2/source`，`--project-cache-dir <仓库根>/tmp/release-0.9.9beta2/gradle-arm64`；`:app:testReleaseUnitTest :app:assembleRelease`（离线、4 workers，未跟踪的 `keystore.properties` 使用历史签名）。
- APK：`build/xnotes/XNotes-0.9.9beta2-arm64.apk`，29,259,239 字节；仅 `arm64-v8a`，证书 SHA-256 与 AGENTS.md 记录一致。
- 日志：[构建](../build-logs/0.9.9beta2/build.txt)、[APK 核验](../build-logs/0.9.9beta2/verification.txt)、[SHA-256](../build-logs/0.9.9beta2/SHA256SUMS.txt)。
