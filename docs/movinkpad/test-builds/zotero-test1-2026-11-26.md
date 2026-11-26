# Zotero 测试版 test1（2026-11-26）

用户要求：自己打包一个测试版给我看看（Zotero 文献库功能）。
状态：已打包；未安装，界面与真实 Zotero 账号、WebDAV 待用户在 MovinkPad Pro 14 上确认。
本轮边界：只打包 `com.xnotes.movinkpad.branchtest.zotero`；未安装、未推送、未发布，正式版未动。

## 构建

- 源码 `d8bf02f9`（分支 `t3/zotero-document-integration`）。与 master test16 相同方式：Release 构建 `-PmovinkpadPreviewSigning=true :app:assembleRelease`，加 `scripts/branch-preview.init.gradle`，`-PbranchTestId=zotero -PbranchTestLabel="XNotes Zotero 测试版" -PbranchTestVersion=1`。
- 包名 `com.xnotes.movinkpad.branchtest.zotero`，版本名 `0.9.9beta`，code 1，仅 `arm64-v8a`；应用名「XNotes Zotero 测试版」，与正式版和其他测试版并存。
- 签名证书 SHA-256 与 AGENTS.md 记录一致；16 KB 对齐核验通过。JVM 测试 4,435 项全部通过。
- APK：`build/branch-tests/zotero-test1/XNotes-zotero-d8bf02f9-test1-arm64.apk`，SHA-256 `2d81c69a36043f13138435009e27e7a87549f5aab602a9652fb1fc2c01706255`。
