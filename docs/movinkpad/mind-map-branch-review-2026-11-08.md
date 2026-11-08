# codex/mind-map 分支复核问题（2026-11-08）

用户要求：复核 Codex 在 `codex/mind-map` 分支（工作树 `~/.codex/worktrees/mind-map/xnotes`）上的代码，把发现的问题记录下来，之后统一修复。
状态：讨论中。仅记录问题，均未修复。复核基准为已提交 HEAD `cd1dabfb`（相对 master `54f05b0f` 共 45 个提交、193 个文件，约 +18k 行）；Codex 当时尚有未提交的交接确认改动（`NativeHandoffAcknowledgement` 等），不在本次复核范围内。
已验证：在 HEAD 的隔离导出副本中用 JDK 21 跑 `testDebugUnitTest`，303 个套件、3348 项，0 失败、0 错误、0 跳过。仪器测试、模拟器和目标真机均未运行。
本轮边界：只写本记录并本地提交到 `worktree-mind-map-review` 分支（基于 `cd1dabfb`）；不改源码，不打包、安装、推送或发布，也不改动 Codex 的工作树。

## 待修复问题

按严重程度排序。修复时每项补回归测试；标为“推算”的项需先实测再定方案。

### P1 高：所有笔记 / 画布的普通保存都改成完整事务，开销大幅增加，文档未提及

- 位置：
  - `ui/Editor.kt:5617` `writeNoteSafely`：删除了 master 上的 `writeNoteInPlace`（只替换清单的快速路径）。
  - `storage/NotebookStorage.kt:425` `stageExisting`、`:193` `publishSave`：替换了 master 对应用自有文件“写临时文件后原子 rename”的路径。
  - `storage/MigrationPublication.kt:51` `stage`、`:84` `publishValidated`；`storage/OwnedRecoveryTarget.kt` `write`；`storage/NativeSourceVersion.kt` `inspect`。
- 现象（按代码推算，未实测）：每次自动保存约做 5 次带 fsync 的整文件复制、约 20 次全文件 SHA-256。每次调用 `NativeSourceVersion.inspect` 都会对文件哈希两次、解压所有条目算摘要（`BundleEntries.digests`）、解析两遍清单；一次保存要对源文件和候选文件多次调用。整个过程持有全局 `NotebookStorage` 锁，期间其他存储操作都会被阻塞。
- 打开路径同样变重：`prepareOpen`（`NotebookStorage.kt:122`）先经 `sourceSnapshot`（`:82`）把整个源文件复制到 cacheDir 再解码。
- 范围：生产路径，没有开关，所有现有 `.xnote` / `.xcanvas` 都受影响。分支文档只写了“大文件性能待真机验收”。
- 修复方向：先补基准测试，测大笔记（图片多 / 清单大）的保存与打开耗时，并与 master 对比；再决定是恢复快速路径（保留快速路径前的保护检查），还是合并重复的哈希和复制。持有存储锁的时长也要纳入考量。

### P2 中：画布拖动节点和布局预览提交的是旧快照，会悄悄覆盖异步加入的来源

- 位置：`mindmap/MindMapView.kt:370`（按下时 `before = document`）、`:357`（抬起时 `onChange(...)` 提交基于 `before` 的结果）；`mindmap/MindMapScreen.kt:252`（`proposed` 在打开对话框时算好）、`:257`（`session.commit(preview)`）。
- 现象：两条路径都经 `MapSession.commit` 写入，不检查基准文档是否变化。如果中途 `MapSession.addSource`（`MapSession.kt:187`，IO 完成后才写入）落地，新来源会被覆盖。撤销可以找回，但没有任何提示。关系编辑和文本编辑已有冲突检查，这两条路径缺失。
- 修复方向：提交时核对基准（基于 `before` / `proposed` 的来源版本），基准已变就重新基于最新文档应用位移 / 布局，或者拒绝并提示。

### P3 中：来源备注逐字写入内容历史

- 位置：`mindmap/GraphDialogs.kt:146`（备注框的 `onValueChange` 直接调用 `change`）→ `MindMapScreen.kt:260` `session.operate`。
- 现象：每按一个键都触发一次全文档 `validate()`（最多 25 万个采样点）、一条撤销记录和一次自动保存防抖。撤销历史上限 80 条（`MindMapDocument.kt:140`），写一段备注就会把之前真正的编辑步骤挤出撤销栈。这与分支 AGENTS.md 中“不逐键写历史”的规则不一致。
- 修复方向：备注改用对话框内草稿，确认或失焦时一次提交，并核对基准。

### P4 低

- **放大后重叠：** `MapSession.kt:83` `editNodeProperties` 在改为手绘节点或点“放大”后不调用 `MapLayout.arrange`，放大的节点会盖住相邻节点，直到下一次布局操作。
- **未修改也报冲突：** `MindMapScreen.kt:62` 关闭时总会调用 `session.save`。没改过的导图如果文件被外部修改，关闭时会报冲突；`MindMapActivity.onStop` 已专门避免这种情况，关闭路径应保持一致（未修改时直接关闭）。
- **注册表无法降级：** `storage/DocumentRegistry.kt:25` 数据库版本从 1 升到 2，但没有 `onDowngrade`。以正式包名 `com.xnotes.movinkpad` 安装本分支后再装回 master 构建，注册表会打不开。需要决定是提供降级兼容，还是在交付前明确限制只用分支测试包名安装。
- **来源添加失败仍留痕：** `mindmap/MapSourceAccess.kt:24` 先 `registry.register`，再检查 `locations(id).size==1`。检查失败时，注册表里仍留下了这条记录，持久 URI 读取权限也已经拿到。应改为先检查、后登记。

### P5 文档

- 分支 AGENTS.md 新增了约 10 条条目，大多是实现进度（例如“生产接线仍待完成”），写法也很密集。这与该文件“只记录当前有效的长期规则”的约定不符。应把进度移到 `docs/movinkpad/`，AGENTS.md 只保留规则。

## 已确认无问题

- `.xmap` 编解码严格校验类型与上限；`MapStore` 先原子写日志、再写 SAF，写后回读校验摘要，冲突只经用户显式选择解除。
- 手绘输入区分笔、手指与取消事件，抬笔时比对之前的笔迹再提交；关系编辑有基准、草稿与冲突处理。
- 导图在分屏、“在旁边打开”中均被拒绝。
- “未保存更改”提示不会对画布构造 `GuardRequest`（`canvasOpen -> action()` 在前），`discardForOpen` 的 `check(!canvasOpen)` 不会被触发。

## 未深入复核

`format/` 下的锚点、清单与整包复制代码（约 1500 行），`WorkingSessionCache` / `NativeWorkingSession`，`PreparedNativeOpen`，以及 `DocumentCodec` 改动的逐行细节，只读到判断兼容性和保存路径所需的程度。修复 P1 时应一并复核。
