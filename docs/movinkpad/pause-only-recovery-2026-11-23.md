# 恢复记录只在离开应用时写入（2026-11-23）

用户要求：Release 99 起进出普通 XNote 笔记都卡，Release 98 流畅。自动保存间隔很短，冷恢复用处不大；选择回到 Release 98 的模式：编辑和离开文档时不再做恢复快照，只在应用进入后台（`onPause`）时记录一次；已保存的文档从原文件重开，未保存的修改（工作区外文档、保存失败）才写恢复副本。用户确认分屏布局与页面位置仍像 Release 98 那样在离开应用时记录并恢复。由主代理规划并编写测试，gpt-6.2-astra 编写实际代码。
状态：已实现（gpt-6.2-astra）；已验证全量 JVM／Robolectric `testDebugUnitTest` 4,303 项通过，`compileDebugAndroidTestKotlin` 通过；仪器测试未在设备上运行；MovinkPad Pro 14 真机流畅度待验证。
本轮边界：分支 `pause-only-recovery`（工作树 `tmp/wt-pause-only`）；不打包、不安装、不推送、不发布。

## 原因（gpt-6.2-astra 代码分析，见对话记录）

Release 98（`b5175e8e`）：`persist()` 在离开应用时写回文件并在后台写一次会话；主菜单与关闭窗格不等待任何恢复写入；启动时 `restoreSession()` + `restoreSplitWorkspace()`。

Release 99 起：

1. `f3890bbb` 的 `ColdPresentationCoordinator.observe`：每次展示版本变化（`85c4216d` 起每个触摸事件都会检查）后 180 ms 做检查点。每个检查点等待已有写入、输入稳定、会话队列，发布后对正文做两遍解压条目摘要和一遍容器 SHA，与文件大小成正比；PDF 笔记的工作副本内嵌整份 PDF。
2. 离开文档：主菜单／窗格首页先等 `checkpointOutgoing`；关闭最后一个窗格先等 `prepareDefaultDeparture` 的整份检查点，随后 `workingSessionClosed()` 立即清除这份副本——纯属浪费。
3. 打开文档两次从来源制作完整快照并多次整包 SHA（本轮不改，另议）。

## 新行为

| 时机 | 做什么 |
| --- | --- |
| 编辑、停笔、平移、文件自动保存 | 不做检查点、不写恢复副本（`checkpointStarted` 保持 0）。文件自动保存照旧（1.2 s 去抖）。 |
| 文件保存失败 | 立即在后台写该文档的受保护恢复副本（编码），不等离开应用。 |
| 回到首页／关闭窗格／打开另一个文档 | 照旧先结清并保存文件；只写 `Home` 布局凭据（`PreparedHomeGate`，小文件），不做工作副本检查点；随后清除该窗格的恢复副本。 |
| 主菜单停放文档 | 不等待检查点，直接显示主菜单（文件保存照常在后台进行）。 |
| 离开应用（`onPause`） | 现有流程：`flushAutosaveAll` → `awaitAutosaveWrites` → 一次 `checkpoint()`，记录全部窗格（分屏、页面位置、视图设置）。对每个窗格：已保存且干净的工作区文档标 `reopenFromFile`，**不保留恢复副本正文**（清除该槽位正文并解除 `prefer-working-copy`）；有未保存修改的文档照旧写受保护副本。 |
| 启动 | 照旧按记录恢复：`reopenFromFile` 从原文件打开；否则恢复受保护副本。原文件丢失或身份不符 → 回到首页（`UNAVAILABLE`），不再有备份可退。 |

不变：

- 切换文档仍等待笔画提交（`85c4216d` 的输入边界与 `captureBoundary` 唤醒保留），不强制提交或取消笔画、选择、样式预览。
- 受保护副本、`prefer-working-copy` 否决、未知原生 head 保护、冷启动核对（类型、UUID、位置、SHA-256）、只认本次实际发布并核实的回执等规则不变。
- 检查点结果分类（Ready／Deferred／Superseded／Failed）及「只有实际写入失败才提示」保留，现在只在离开应用时用到。

## 实施要点（给实现方）

- 删除 `ColdPresentationCoordinator.observe` 及 `EditorActivity` 中的调用（含 Superseded 重试循环）。
- `Editor.prepareDefaultDeparture`：不再调用 `prepareNativePresentationCheckpoint`，只准备 Home 凭据。`EditorActivity.preparePresentationParking`：不再调用 `checkpointOutgoing`。`ColdPresentationCoordinator.checkpoint()` 在停放状态下仍可在 `onPause` 时为停放文档写记录。
- 干净文档的检查点：发布一个清除正文、写元数据、`trackDiskReopen(true)` 的变更，不编码、不复制；随后的检查点验证跳过不存在的正文。
- 删除仅为「从干净文件复制备份」服务的代码：`PreparedLegacySessionMutation.prepare*FromEncoded`、`rejectedBackupSource` 等。`SaveStats.workingFromSource` 可保留（恒为 0）。
- `SaveStats` 新增 `checkpointStarted`：每次进入 `checkpointWorkingSession` 时递增。
- 文件保存失败（`startNoteWrite`／`startCanvasWrite` 的 `finally`，`success=false` 且文档仍是当前模型）→ `requestWorkingSession()`。

## 测试

- `SaveSlimHostTest`（已更新）：暂停后已保存文档无恢复副本、仍标 `reopenFromFile` 并可从文件重开；PDF 笔记同样；新增：协调器没有 `observe`；回首页（笔记、画布）只保存文件、不启动检查点、记录 Home、无副本；编辑并等待自动保存全程不启动检查点；保存失败不等暂停即写受保护副本。删除：关闭画布的离开检查点测试、干净来源复制竞态测试。
- `CleanColdReopenHostTest`（已更新）：原「替换／缺失文件时保留备份」改为「不保留过期备份」。
- 删除 `LegacySessionEncodedBodyTest`、`LegacySessionEncodedBodyAndroidTest`（对应代码删除）。
- 其余现有测试必须继续通过。若某个现有测试断言的正是被删除的行为（每次变化检查点、离开前检查点、干净文档保留副本），实现方可以删改，但须在报告中逐项列出（文件、测试名、原断言、原因），由主代理复核；不得为让实现通过而放宽其他断言。

## 本轮不做

- 打开文档时的重复来源快照与 SHA（分析第 2 项）：另起一轮。
- 检查点验证改为增量证明：离开应用时只做一次，暂不优化。

## 验证边界

JVM／Robolectric 只证明逻辑；进出笔记是否恢复 Release 98 的流畅度须在 MovinkPad Pro 14 / Android 15 上实测（需用户另行授权打包与安装）。

## 实施记录

- 删除 `ColdPresentationCoordinator.observe`（及 Superseded 重试）与 `EditorActivity` 的调用、`preparePresentationParking`；`prepareDefaultDeparture` 只准备 Home 凭据。
- 干净文档的检查点改用 `PreparedLegacySessionMutation.prepareNoteDiskReopen`／`prepareCanvasDiskReopen`：删除槽位正文、写元数据、`trackDiskReopen(true)`，不做正文核验。删除 `prepare*FromEncoded` 与 `rejectedBackupSource`。
- 文件保存失败时 `requestWorkingSession()`；`SaveStats.checkpointStarted`。
- 实现方未改动、未删除任何现有测试。主代理修正了自己测试的时钟：`editingAndPausingBetweenStrokesNeverStartsACheckpoint` 改为让持有主 Looper 的测试线程推进 Robolectric 虚拟时钟 1.3 s，断言不变。
- 已知限制：离开应用时的检查点若被新状态超越（`Superseded`）不再自动重试，该次不更新记录，沿用上一份；与 Release 98 一样只在下次离开应用时补记。
