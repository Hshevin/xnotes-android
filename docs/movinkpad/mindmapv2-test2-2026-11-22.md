# 导图 v2 test2 打包与真机测试（2026-11-22）

用户要求：「开始调试吧，我连上了」——在连接的 MovinkPad 上调试合入主线后的导图 v2（test1 修复与阶段 3 来源）。测试中经用户同意，测试文件存放在新建的 `Download/XNotes-mapv2-test`；对发现的问题，用户选择：长按画布空白处总是弹出菜单、来源面板放在节点旁不遮挡、来源标签尺寸暂不改、本轮修复「同一文件两个 URI 被误判为重复 ID」。
状态：已打包、已安装、部分真机验证（手指 / 软键盘路径）；发现 2、3 已在源码修复并通过 JVM 测试（`650c234a`），未装机复验；发现 5 由 gpt-6.2-astra 实现并合入（`bd9d0601`，登记表升至 v4），全部 JVM 测试 4,237 项通过、仪器测试编译通过但未运行，待装机复验；旧笔记启用页面编号弹窗、旧 `.xmap` 提示、原生分屏恢复与笔输入未测。
本轮边界：仅打包 test2 并覆盖安装同一测试包；不动正式版及其他测试包，不推送、不发布 Release。

## 构建

- 源码 `c448605d`（`master`，含导图 v2 合并），工作树 `<工作树 wt-mindmap-v2>`，JDK 21，独立 `--project-cache-dir`。
- 命令：`./gradlew --init-script scripts/branch-preview.init.gradle -PbranchTestId=mindmapv2 '-PbranchTestLabel=XNotes 导图v2 测试版' -PbranchTestVersion=2 :app:assembleDebug`（ARM64）。
- 产物 `XNotes-mindmapv2-c448605d-test2-arm64.apk`；SHA-256 见 [`build-logs/mindmapv2-test2/SHA256SUMS.txt`](build-logs/mindmapv2-test2/SHA256SUMS.txt)。
- 包名 `com.xnotes.movinkpad.branchtest.mindmapv2`，versionCode 2，versionName `0.9.10-test-mindmapv2-2`，名称「XNotes 导图v2 测试版」，仅 arm64-v8a。
- 签名证书 SHA-256 与 AGENTS.md 一致（`A0:45:3B:…:84:51`）。`adb install -r` 覆盖 test1，保留数据。

## 真机结果

设备：Wacom MovinkPad Pro 14（DTHA140），Android 15，夜间模式；操作经手指触控与 Gboard 悬浮键盘，未使用笔。

| 项目 | 结果 |
| --- | --- |
| 新建子节点只显示一个框（test1 发现 1） | 通过 |
| 输入标题并提交；选中后子 / 同级手柄位置 | 通过 |
| 来源面板打开后按返回只关面板、不离开画布 | 通过 |
| 首页缩略图显示导图节点（新保存的画布） | 通过；test1 时生成的旧缓存缩略图仍为黑色，内容修改后才会重绘 |
| 新笔记「复制页面链接」直接复制，无启用弹窗 | 通过 |
| 来源面板「粘贴链接」：解析为「untitled_2.xnote · 第 1 页」，节点内显示标签 | 通过 |
| 点节点内来源标签跳到对应笔记页；跳转前画布已保存（文件含该来源） | 通过 |
| 「返回导图」回到原画布并保持选中 | 通过 |
| logcat 本包无错误，crash 缓冲区为空 | 通过 |
| 「复制画布链接」 | 失败，见发现 2 |
| 旧笔记启用页面编号弹窗、旧 `.xmap` 打开提示、原生分屏恢复 | 未测（设备上无对应旧文件） |
| 笔写入节点、橡皮擦节点墨迹、连线工具建关系 | 未测（需 Pro Pen 3） |

## 发现

1. 来源标签在 100% 缩放下约 20 px 高，低于 48 dp 触控区。用户选择暂不修改。
2. 「复制画布链接」实际不可达：画布空白处长按只在剪贴板有可粘贴内容时才弹菜单。已改为手指长按空白处总是弹出菜单（笔不弹），测试 `CanvasLongPressMenuTest`。
3. 来源面板以下拉菜单锚在操作栏按钮下方，盖住所选节点。已改为放在节点右侧，其次左侧、下方、上方，不与节点及操作栏重叠，测试 `MapSourcesPanelPlacementTest`。
4. 测试环境问题：agent-device 点击文件选择器需带按住时长才生效；Gboard 拼音输入英文文件夹名会被转换，需切换为英文布局。
5. 存储层（早于导图 v2）：经系统「新建文件」选择器保存到 Download 后再把该文件夹加为工作文件夹，同一物理文件以 `downloads.documents raw:` 和 `externalstorage tree` 两个 URI 登记，打开时误报「发现相同文档 ID 的多份文件」。按用户选择本轮修复：仅以两个 URI 的 `fstat` 设备号＋inode 证明为同一文件时合并位置，不按名称、字节或时间判断，由 gpt-6.2-astra 实现，见 [same-file-locations-2026-11-22.md](same-file-locations-2026-11-22.md)。测试中对该文件选择「设为独立副本」以继续，只影响测试文件。
