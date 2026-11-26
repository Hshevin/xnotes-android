# Zotero 测试版 test2：分类侧栏与按分类存放（2026-11-26）

用户要求：打包并覆盖安装，看分类侧栏与按分类存放的效果。
状态：已覆盖安装到 MovinkPad Pro 14（Android 15，原 test1 / code 1），启动正常、无崩溃；分类侧栏与真实文库待用户确认。
本轮边界：打包并覆盖安装 `com.xnotes.movinkpad.branchtest.zotero`；未推送、未发布，正式版与其他测试版未动。

## 构建

- 源码 `686386cc`（分支 `t3/zotero-document-integration`），方式同 test1，`-PbranchTestVersion=2`；版本名 `0.9.9beta`，code 2，仅 `arm64-v8a`。
- 签名证书 SHA-256 与 AGENTS.md 记录一致；16 KB 对齐核验通过。JVM 测试 4,441 项全部通过。
- APK：`build/branch-tests/zotero-test2/XNotes-zotero-686386cc-test2-arm64.apk`，SHA-256 `44bf9f67bc4ca0c3ca77702c85d2ca2a9d7d12b88c28571d956933d2508dcc8b`。
