# 思维导图分支代码审查与修复（2026-11-07）

用户要求：审查 `codex/mind-map` 相对 `master` 的已提交改动（17 个提交，约 94 个文件、+5.8k 行），随后要求直接在思维导图工作树上修改审查发现的问题，并写清文档方便后续 Codex 交接。
状态：已实现第 1、3、4、5、6、7、8 项修复；第 2 项属设计取舍，未改，待用户决定；第 9 项未改。编译与全量 JVM 测试通过（JDK 21，2828 项，0 失败／错误／跳过）；Android 仪器测试源码编译通过，未运行；目标设备未验证。
本轮边界：只在 `/Users/kita/.codex/worktrees/mind-map/xnotes`（分支 `codex/mind-map`）修改并本地提交；不打包、安装、推送、合并或发布。思维导图整体目标仍为暂停状态（见 `tasks/loop-state.md`），本轮只修审查问题，不推进 B2b2 等后续阶段。

## 审查来源

审查由 Claude Code 的 code-review 流程完成，审查本身未编译或运行。修复前逐条对照源码核实，结论如下表。行号为审查时（`5314a887`）的位置。

| # | 位置 | 问题 | 核实 | 处理 |
| --- | --- | --- | --- | --- |
| 1 | `format/AnchorMetadata.kt:75` | 「仅笔迹」恢复副本要求全部对象读取成功，一个坏图片／未知类型就让整份恢复失败 | 成立 | 已修 |
| 2 | `format/DocumentCodec.kt:1799`（及 `CanvasCodec`） | 已启用锚点的笔记／画布中一张图片探测失败即整份拒绝打开 | 成立，但属 `reference-identity-model-2026-11-07.md` 的有意设计 | 未改，待决定 |
| 3 | `mindmap/MapStore.kt:66` | 冲突出现后 `expectedHash` 停在日志旧值，此后所有保存永远报冲突 | 成立 | 已修 |
| 4 | `mindmap/MapSession.kt:191` | 「另存副本」沿用原文档 UUID，两个 `.xmap` 共用身份 | 成立 | 已修 |
| 5 | `MainActivity.kt:1270` | 分屏窗格首页点导图无反应 | 主要场景不成立：Backstage `onOpenFile`（含窗格首页）已为导图启动编辑页；`openInPane` 只打开抽页生成的 `.xnote`。实际残留：双文件分屏选到导图时先建第二窗格再静默返回 | 已修残留部分 |
| 6 | `mindmap/MindMapDocument.kt:67` | 独立主题（free）下新增子节点不被布局，落在原点叠在根上 | 成立 | 已修 |
| 7 | `mindmap/MindMapActivity.kt:21` | `onStop` 无修改也保存，可能写日志并误报冲突 | 成立 | 已修 |
| 8 | `ui/Editor.kt:4286` | 导图缩略图不缓存，每次读取并渲染整个 `.xmap` | 成立 | 已修 |
| 9 | `MainActivity.kt:1150` | 点文件时在主线程查询 SAF 显示名 | 成立，但 `master` 同样存在 | 未改 |

## 修复内容

### 1. 仅笔迹副本允许部分抢救（`AnchorReadState.complete`）

- 仅 `independentCopy`（`readInkCopy`）放宽：结构、版本、文档归属检查照旧；无法物化的对象（坏图、缺资源、未知类型）略去，数量记入 `AnchorReadState.omitted` → `Document.omittedOnCopy`（瞬态字段，不入格式）。锚定源仍要求已物化成员的源 ID 属于清单且不重复。
- 理由：副本获得全新文档与成员 ID，与原件不相交，略去对象不会冒充原件；原件不写入。
- `Editor.openInkWithoutPdf` 在略去数量 > 0 时提示 `recovery_ink_only_omitted`（plurals，三套语言）。
- 正常 `read` 的严格对账（`missing==0`、`materialized==ids`）未改。

### 3. 导图冲突的显式出口（`MapStore` / `MapSession` / `MindMapScreen`）

- `MapStore.overwrite(doc)`：以目标当前内容为基准（不可读时用哨兵 `ABSENT`），再走普通 `save`：先写 pending 日志，写后读回校验。`save` 比较改为 `(currentHash ?: ABSENT) != expectedHash`。
- `MapStore.discardRecovery()`：先读取并解码目标，成功后才删除日志；目标不可读时抛出，日志保留。
- 界面：冲突提示下新增「保留当前版本」（`save(overwrite = true)`）与「改用文件版本」（二次确认后 `useFileVersion()`）。`useFileVersion` 在关闭中、保存中、落笔中或文字草稿中不执行；成功后递增 revision 丢弃排队中的旧保存，重建历史。
- `map_conflict` 文案同步说明三种选择。

### 4. 另存副本使用新 UUID

`MapSession.saveCopy` 写入 `document.copy(id = UUID.randomUUID())`，与文件浏览器复制（`MapFiles.assignCopy`）一致；当前会话仍编辑原文档。节点 ID 不变（作用域为文档内）。

### 5. 导图不进入分屏的提示

`openSplit` 在创建第二窗格前拒绝导图；`openInto` 遇到导图不再静默返回，均提示 `map_split_unsupported`。为避免与 `master` 上 `kindOf` 移位冲突，`openSplit` 使用与 `openInto` 相同的内联判断。

### 6. 独立主题的分支布局（`MapLayout.arrange`）

独立主题保持用户放置的位置，其子树按主根右侧规则排在旁边（DOWN 方向经坐标交换后排在下方）。`finishTextDraft` 与 `editNodeProperties` 不再对 free 节点跳过重排（手绘节点属性修改仍按原规则不重排）。行为变化：独立分支内子节点在下一次布局时会被重新排列，与主树一致。

### 7. `onStop` 只保存未保存的修改

`MindMapActivity.onStop` 改为 `if (session.dirty) session.save()`。

### 8. 导图缩略图缓存

新增 `Editor.mindMapThumbnail`，与画布一致使用内存 LRU `noteThumbs`、代次检查与 `publishThumbnail`。返回键与关闭按钮都会等保存完成才关闭编辑页，`mindMapLauncher` 回调中的 `refreshStoredFile` 随后清除该 URI 的缓存。未接入磁盘缓存 `thumbCache`。

## 未处理与待决定

- **第 2 项（需用户决定）**：锚定文档在任一对象缺失／探测失败时整份拒绝打开，是为避免「部分模型被当成完整可写文档保存，悄悄丢失对象和引用身份」。代价是单个坏资源让整份文档不可访问；正常打开失败时目前也没有入口引导到仅笔迹副本。可选方向：保持拒绝，但在打开失败时提供「部分恢复副本」入口（复用第 1 项的 `readInkCopy`），或以只读方式打开。目前普通旧文档未自动启用锚点，影响面有限。
- **第 9 项**：`onOpenFile`／`onOpenBeside`／`kindOf`／`stemOf` 在主线程调用 `displayNameOf`。`master` 同样存在（2026-11-07 画布不分屏改动也新增一次同类调用），宜在主分支统一改为使用浏览条目已有的文件名。
- 本轮新增界面路径（冲突按钮、确认对话框、分屏拦截提示、略去数量提示）无 Compose／仪器测试，需手动或真机确认。

## 与 master 合并时注意

- `master` 已合入「无限画布不分屏」（`2f6373b5`，见 `canvas-sketch-only-2026-11-07.md`）：`openInto` 开头新增画布拦截、`openSplit` 新增画布预检、`kindOf` 移到 `openInto` 之前、新增 `canvas_split.xml`。本分支在相同函数开头加了导图拦截，合并时两者都要保留，预检可合并为一次判断。
- `master` 的 `SplitEditorTest` 中笔记 + 画布分屏用例已改为两份笔记。

## 测试与验证

- 新增 JVM：`MapStorageTest` 5 项（重开后冲突仍阻止普通保存、保留当前版本后续保存恢复正常、覆盖损坏目标、改用文件版本后恢复正常保存且不写目标、目标不可读时保留日志）；`ReferenceCodecTest` 改写 1 项（锚定源部分抢救：略去 1 个对象、页面保留、ID 不相交、可写出重开）并新增 2 项（legacy 部分抢救、未知版本仍拒绝）；`MindMapTest` 1 项（三种方向下独立主题子节点布局，主题位置不变）。
- 命令：`JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ANDROID_HOME=/Users/kita/Library/Android/sdk ./gradlew --offline -I tmp/build-mind-map.gradle --project-cache-dir /private/tmp/xnotes-mind-map-cache :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`，输出在 `/private/tmp/xnotes-mind-map-build/`，日志 `tmp/review-fixes-full.log`：2828 项，0 失败／错误／跳过。
- 未运行：Android 仪器测试、真实 SAF、目标设备（MovinkPad Pro 14 / Android 15 / Pro Pen 3）。
