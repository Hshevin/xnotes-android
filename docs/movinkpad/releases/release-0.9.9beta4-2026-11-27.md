# 0.9.9beta4 发布（2026-11-27）

用户要求：「推送打包，安装到平板上并发布 beta4 release，release 文案让 neon 写一份先」。
状态：Release JVM 4,030 项、R8、资源压缩、lintVitalRelease、APK 验签与 16 KiB 对齐通过；已覆盖安装到 MovinkPad Pro 14 / Android 15（原 `0.9.9beta3` / code 104），启动正常、无崩溃记录；已推送 `origin/master` 并发布 GitHub 预发布版 `v0.9.9beta4`。导图实时裁切与叠放、Zotero 真实账号与 WebDAV、大文件流畅度待真机确认；仪器测试未运行。
本轮边界：推送、打包、安装、GitHub 预发布。

## 源码与版本

- 源码 `8ae3fe3b876bbe94eb125ceb674b0e92f206cfcf`：`com.xnotes.movinkpad`，`0.9.9beta4` / versionCode 105。
- 相对上一个公开版本 0.9.9beta（0.9.9beta2、beta3 未公开发布）：同步上游 0.9.11、拆除事务存储后的直接打开与快速保存、图层切换提速与点选、Zotero 文献库、导图节点手动调整大小与整框书写、新笔迹叠放顺序修复、界面文案修正。完整说明见 GitHub Release。
- 发布说明由 Gemini 4 Neon 按主代理整理的改动范围撰写，主代理核对事实并填入验证结果。

## 构建与核验

- 隔离工作树 `tmp/release-0.9.9beta4/source`，`--project-cache-dir <仓库根>/tmp/release-0.9.9beta4/gradle-arm64`；`:app:testReleaseUnitTest :app:assembleRelease`（4 workers，未跟踪的 `keystore.properties` 使用历史签名）。
- APK：`build/xnotes/XNotes-0.9.9beta4-arm64.apk`，29,244,923 字节，SHA-256 `8c02a525e3bc20f2415d24ecb016e17abba5be14d1e22d73d61822028f6e0157`；仅 `arm64-v8a`，证书 SHA-256 与 AGENTS.md 记录一致。
- 日志：[构建](../build-logs/0.9.9beta4/build.txt)、[APK 核验](../build-logs/0.9.9beta4/verification.txt)、[SHA-256](../build-logs/0.9.9beta4/SHA256SUMS.txt)。
