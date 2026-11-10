# Release 98 之后主线审核与修复（2026-11-10）

用户要求：审核本地 `master` 在 Release 98（`b5175e8e`）之后的全部新提交；随后用 Opus 子代理负责文本翻译与文档整理，并修复审核发现的问题。
状态：已实现；JVM 单元测试与 Android 测试源码编译通过（环境与数字见下）；分屏列表刷新与视图恢复效果待模拟器／真机复测。
本轮边界：源码、测试、文档与本地提交；不打包 APK、不安装、不推送、不发布、不改 Git 配置。

## 审核范围与结论

- 范围：`b5175e8e..8d12bacf`，290 个提交（含分支内提交），752 个文件，约 9.5 万行新增，其中两次思维导图合并约 8.8 万行。
- 审核前基线验证：完整 JVM 单元测试 4567 项全部通过，Android 仪器测试源码编译通过；模拟器与目标设备未运行。
- 逐块通读了笔记／画布编解码（只新增完整性观察，宽容读取不变）、`.xmap` v5／v6 门禁、文档登记表 v2→v3 升级与拒绝降级、分屏持久化（暂存＋fsync＋原子替换）、原 PDF 原件库（无写原件路径）、画布与分页输入链路、按键分发，以及 `Editor.kt`／`MainActivity.kt` 的全部改动。
- 另注意到 `origin/master` 有一个本地 `master` 没有的 test5 打包提交（`8ee33799`），本轮修复以本地 `master` 为基线，未合入该提交。

## 发现与处理

| 编号 | 问题 | 处理 |
| --- | --- | --- |
| R1 | `Editor` 构造时在永不取消的 `autosaveScope`（`MainScope`）中启动无限 `splitRecentWriter.state.collect` 更新 `hasSavedSplit`；副窗格关闭分屏后被置空并在下次分屏重建，`MainActivity` 用 `remember { Editor(context) }` 创建主窗格，Activity 重建也新建实例。每个旧实例都被该协程持有，形成泄漏。来源提交 `ca5eb88d`。 | 已修：删除收集器，`hasSavedSplit` 改为直接读取共享写入器的 `StateFlow.value`；首页本身经 `collectAsState` 观察该流，行为不变。新增主机测试断言新建 Editor 的自动保存作用域没有活跃子协程。 |
| T3-1 | 分屏时一侧首页新建文件后，另一侧首页列表不刷新（test3 模拟器冒烟发现）。两个窗格的资源管理器各以自己 Editor 的 `treeVersion` 为键。 | 已修：新增 `Editor.notifyTreeChanged()`，对根窗格和副窗格同时递增 `treeVersion`；`refreshStoredFile`、`undoTrash` 改用它；`Backstage` 的 17 处本地 `refreshKey++`（新建、导入、重命名、移动、删除、改色、置顶、合并）统一改为 `listAgain()`，同时刷新本窗格与另一窗格。新增主机测试覆盖双窗格与单窗格。 |
| U-3 | 单屏打开过的笔记进入分屏后页面贴边、不居中：记住的视图只保存缩放与滚动，在更窄的窗格恢复时沿用旧缩放，`fitWidthActive` 失效。 | 候选修复：`ViewStateStore` 新增 `fitWidth` 标记（仅为真时写入，旧文件读回为假），`InitialView.Restore` 携带该标记，`CanvasState.establishInitialView` 对带标记的视图重新按本窗格宽度适配并按缩放比例保留顶部内容位置；用户手动缩放的视图仍原样恢复。JVM 验证了窄窗格重适配、位置换算与旧行为保留；贴边现象本身待真机复测。 |
| D1 | 14 份文档与任务记录含本机绝对路径 `/Users/kita/...`。 | 已修（子代理）：改为 `<仓库根>`、`<工作树 名>/xnotes`、`$ANDROID_HOME` 或相对路径，只改路径文本；AGENTS 新增不写本机绝对路径的约定。 |
| D2 | README 思维导图章节累积约四十段按阶段写的测试数量流水，与「README 面向用户」分工不符。 | 已修（子代理）：重写为按主题的功能说明，保留「安装兼容」与总体验证边界，删除测试数量，57 处记录链接去重为 53 条集中在「详细记录」。 |
| D3 | 登记表 v3 升级不可逆，覆盖安装后不能直接回装 Release 98。 | README「下载与安装」新增回装提醒并链接版本策略记录。 |
| D4 | `values-zh-rCN/` 缺少 `strings.xml`，只有 `values-b+zh+Hans/` 有完整翻译（Release 98 时即如此）。 | 已补齐（子代理）：逐字节复制，1004 项 name 与文本一致；核对无未翻译英文句子。 |

未处理、保持开放：渲染回移审核的 P-2／P-3／P-4、S-4、D-4～D-7、U-2、B5 系列、B6-4／6／7（见 `render-backport-review-followups-2026-11-07.md`），以及 `tasks/todo.md` 中的导图剩余需求与真机验收。

## 修改文件

- 源码：`ui/Editor.kt`、`ui/Backstage.kt`、`canvas/CanvasState.kt`、`platform/ViewStateStore.kt`。
- 测试：`canvas/InitialViewTest.kt`（+2）、`platform/ViewStateStoreTest.kt`（+2）、新增 `pen/WorkspaceTreeVersionHostTest.kt`（3 项，Robolectric 宿主）。
- 资源：新增 `values-zh-rCN/strings.xml`。
- 文档：README、AGENTS、`tasks/todo.md`、本记录，以及去路径的 14 份记录。

## 验证

- JDK 21 / Gradle 8.13 / AGP 8.12.3 / SDK 36，`--offline`，独立 `--project-cache-dir` 与构建目录（放在 iCloud 目录之外）。
- 定向：`InitialViewTest` 5 项、`ViewStateStoreTest` 6 项、`WorkspaceTreeVersionHostTest` 3 项、`FitWidthSnapTest` 19 项，全部通过。
- 完整：`:app:testDebugUnitTest` 与 `:app:compileDebugAndroidTestKotlin` 结果见本节末尾。
- 未运行：Android 仪器测试、模拟器、MovinkPad Pro 14 / Android 15 / Pro Pen 3 真机。T3-1 与 U-3 的用户可见效果须在双窗格真机上复测：左窗格首页新建文件后右窗格立即出现；单屏以贴合页宽阅读的笔记进入分屏后页面充满窄窗格而非贴边。

完整 JVM 结果：481 套件、4574 项（较基线新增 7 项），失败／错误／跳过均为 0，构建成功 1 分 40 秒；`compileDebugAndroidTestKotlin` 成功。
