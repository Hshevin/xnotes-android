# 0.9.9beta 发布（2026-11-23）

用户要求：推送到远端并发布 GitHub Release；发布不写内部序号，只写版本号（如 0.9.8），并写入项目文档；本次发布命名为 0.9.9beta。
状态：Release JVM 4,303 项、R8、资源压缩、lintVitalRelease、APK 验签与 16 KiB 对齐通过；已推送 `origin/master` 并发布 GitHub 预发布版 `v0.9.9beta`。本 APK 未安装到设备（设备上为同源、仅 versionName 不同的此前构建）；真机流畅度与冷启动恢复待验证。
本轮边界：推送、GitHub 预发布、本地记录；不安装。

## 源码与版本

- 源码：`1a94148ce2406e979a6d3ec30e87fe298e551624`（versionName `0.9.9beta`，versionCode 102；versionCode 只用于覆盖升级，高于设备已安装的此前构建）。
- 相对上一个公开版本 0.9.8：画布思维导图、顶部 AI 按钮与统一截图、多供应商与配置编辑器、上游书写功能、工作区文件冷启动重开、只在离开应用时写恢复记录（[记录](../storage/pause-only-recovery-2026-11-23.md)）等。完整说明见 GitHub Release。

## 构建与核验

- 隔离工作树 `tmp/release-0.9.9beta/source`，`--project-cache-dir <仓库根>/tmp/release-0.9.9beta/gradle-arm64`；命令 `:app:testReleaseUnitTest :app:assembleRelease`（离线、4 workers，工作树内未跟踪 `keystore.properties` 使用历史签名）。
- 日志：[构建](../build-logs/0.9.9beta/build.txt)、[APK 核验](../build-logs/0.9.9beta/verification.txt)、[SHA-256](../build-logs/0.9.9beta/SHA256SUMS.txt)。
- APK：`build/xnotes/XNotes-0.9.9beta-arm64.apk`，29,154,335 字节，SHA-256 `3ab1eb370f1e65fcbc81000959b18251013a4d0a6fac91bf59e5100621d90bdf`；证书 SHA-256 `a0453b7be6c0c8239e8616208612c412cf501da8898c7a0450cc923c8e358451`。

## 发布约定

自本版起，发布标题、标签、说明与 APK 文件名只用版本号（规则见 AGENTS.md「Git、打包与发布」）。

用户随后要求安装包文件名去掉「movinkpad」（应用不再只为 MovinkPad 编写）：发布附件改名为 `XNotes-0.9.9beta-arm64.apk`，字节不变，SHA-256 相同。包名 `com.xnotes.movinkpad` 保持不变，否则无法覆盖升级、原有数据也不会带过去。
