# 导图 v2 test3 打包与真机复验（2026-11-22）

用户要求：「来吧，开始调试，你知道要测试哪些功能吧」——在 MovinkPad 上复验 test2 的三项修复：长按画布空白处弹出菜单（发现 2）、来源面板放在节点旁（发现 3）、同一文件两个 URI 不再误报重复 ID（发现 5，登记表 v4）。
状态：已打包、已安装、已真机验证（手指 / 软键盘路径）。三项修复真机通过；`AndroidSameFileProbeTest` 在目标设备运行通过（测试夹具已改，见下）。笔输入、旧笔记启用弹窗、旧 `.xmap` 提示、原生分屏恢复仍未测。
本轮边界：仅打包 test3 并覆盖安装同一测试包，另安装其仪器测试 APK 运行单个测试；不动正式版及其他测试包，不推送、不发布 Release。

## 构建

- 源码 `3b347dbd`（`mindmap-v2`，含 `650c234a` 界面修复与合并 `f41ae03f` 同文件位置修复），工作树 `<工作树 wt-mindmap-v2>`，JDK 21，独立 `--project-cache-dir`。
- 命令：`./gradlew --init-script scripts/branch-preview.init.gradle -PbranchTestId=mindmapv2 '-PbranchTestLabel=XNotes 导图v2 测试版' -PbranchTestVersion=3 :app:assembleDebug :app:assembleDebugAndroidTest`（ARM64）。
- 产物 `XNotes-mindmapv2-3b347dbd-test3-arm64.apk`；SHA-256 见 [`build-logs/mindmapv2-test3/SHA256SUMS.txt`](build-logs/mindmapv2-test3/SHA256SUMS.txt)。
- 包名 `com.xnotes.movinkpad.branchtest.mindmapv2`，versionCode 3，versionName `0.9.10-test-mindmapv2-3`，仅 arm64-v8a；签名证书 SHA-256 与 AGENTS.md 一致（`A0:45:3B:…:84:51`）。`adb install -r` 覆盖 test2，保留数据。登记表随之升至 v4，此后不能再装回 test2。

## 仪器测试

`am instrument -w -e class com.xnotes.storage.AndroidSameFileProbeTest …mindmapv2.test/com.xnotes.pen.PenTestRunner`：

- 首次运行失败于夹具：`Os.link` 在 Android 15 应用进程内被 SELinux 拒绝（`EACCES`），未进入被测代码。
- 夹具改为同一文件分别经 FileProvider `content://` 与 `file://` 两个 URI 打开（同一 inode、不同 URI 文本）；字节相同的副本仍判为不同、缺失文件仍返回 null。重跑 1 项通过。

## 真机结果

设备：Wacom MovinkPad Pro 14（DTHA140），Android 15，夜间模式；手指触控与 Gboard，未使用笔。

| 项目 | 结果 |
| --- | --- |
| 来源面板：节点右侧有空间时放在右侧，不遮挡节点与操作栏 | 通过 |
| 来源面板：节点靠右边缘时改放左侧，不遮挡节点与操作栏（会盖住父节点，符合规则） | 通过 |
| 来源面板加入第二条来源变高后仍不遮挡；返回键只关面板 | 通过 |
| 手指长按画布空白处（剪贴板无可粘贴内容）弹出「插入图片…／复制画布链接」 | 通过 |
| 「复制画布链接」复制 `xnotes://link?v=1&kind=canvas&doc=…` 并提示；粘贴到节点来源显示为画布 `untitled_1.xcanvas`、节点出现「+1」 | 通过（随后删除该自引用来源） |
| 重复 ID 重现：应用存储中的画布经系统「下载」根「保存副本」到 `Download/XNotes-mapv2-test`（登记为 `downloads.documents raw:`），再把该文件夹设为工作文件夹并从首页打开 | 通过：未弹重复 ID；登记表只留工作文件夹 tree URI，`raw:` URI 以同一 UUID 存入 `location_alias_expectations` |
| 经系统设备根（DTHA140 › Download）保存：返回 `externalstorage document/` URI，与 tree URI 同一位置键 | 通过（走原有键匹配，不经 inode 探测） |
| 崩溃缓冲区与本包错误日志 | 无 |

## 观察（未处理）

1. 覆盖安装后首次启动，画布上短暂出现「未保存的更改：继续之前保存对“Untitled”的更改？」，未操作即自行消失；之后离开该画布时同一提示稳定出现，标题仍为「Untitled」，而文件名为 `untitled_1.xcanvas`。该画布在 test2 中被选为「设为独立副本」，保存走另存为。标题显示与启动时自行消失的原因未查。
2. 关闭复制链接后的系统剪贴板浮层时，节点选中被清除（可能是点击落到画布）。影响小，未查。
