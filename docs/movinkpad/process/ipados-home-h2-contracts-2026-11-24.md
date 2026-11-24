# iPadOS 首页 H2 契约（2026-11-24）

上级计划：[iPadOS 首页对齐 Android](ipados-home-plan-2026-11-24.md)
状态：S3 交 gpt-6.2-astra 实现；界面由主代理在 iosKit 实现

## S3 iPad 文件库操作（gpt-6.2-astra）

目标：iPad 首页对本机 XNotes 文件夹（`IosStorageLocations.documents`，下称「顶层文件夹」）做的文件操作，在磁盘上留下的东西与 Android 完全一致：同一个文件夹用 Android 打开，颜色、置顶、回收站都认得；Android 写下的，iPad 也读对。

Android 参照（`app/src/main/java/com/xnotes/ui/Editor.kt`，行为以代码为准）：

- 颜色旁车：`SIDECAR_DIR`/`SIDECAR_FILE`（约 229–250 行）、`readSidecarColors`/`writeSidecarColors`/`setItemColors`/`moveItemColor`（约 8735–9000 行）、`findByColor`。
- 颜色名称：`COLOR_NAMES_FILE`、`setColorName`/`readColorNames`（只在顶层文件夹的旁车里）。
- 笔记置顶：`PINS_FILE`、`readNotePins`/`writeNotePins`/`pinPath`/`setNotePinned`/`repin`/`pinnedEntries`（路径相对顶层文件夹，以 `/` 分隔）。
- 重命名、移动、复制：`renameDocument`（约 7689 行）、`copyDocumentAs`/`uniqueCopyName`/`copyEntriesInto`/`moveEntriesInto`/`copyFolderAs`/`moveDocumentInto`（约 7883–8060 行）。复制出的笔记、画布（含文件夹里的）必须换新的文档身份（Android `NotebookStorage.assignCopy`），否则两本互认为同一本。
- 回收站：`trashDir`/`trashEntries`/`listTrash`/`restoreTrash`/`freeName`/`deleteTrash`/`emptyTrash`/`purgeExpiredTrash`（约 9100–9320 行）。`trash.json` 字段 `version`、`name`、`path`（顶层到原文件夹的名称数组）、`deleted`（ISO-8601 时刻）、`colour`（可无）；包装文件夹名 `毫秒时间-6位随机`。
- 搜索：`searchNotes`（按去掉扩展名的显示名，不区分大小写包含，递归子文件夹，跳过旁车）。

要求：

1. 新类放 `shared/src/commonMain/kotlin/com/xnotes/storage/library/`（只用 okio `FileSystem` 与现有公共代码，JSON 用项目里已有的公共 JSON 方式，不引入新依赖）。建议形状（名称可调，调了在实现记录里写清）：

   ```kotlin
   class Library(fs: FileSystem, root: Path, store: LiteStore /* 或换身份所需的最小接口 */, clock: () -> Long) {
       fun colors(dir: Path): Map<String, Rgba>
       fun setColors(dir: Path, changes: Map<String, Rgba?>): Boolean
       fun colorNames(): Map<Rgba, String>
       fun setColorName(color: Rgba, name: String?): Boolean
       fun findByColor(color: Rgba): List<Path>
       fun notePins(): List<Path>                // 已不存在的自动剔除并写回
       fun setNotePinned(doc: Path, pinned: Boolean): Boolean
       fun rename(item: Path, newName: String): LibraryResult   // 文档保留扩展名；颜色、置顶跟随
       fun moveInto(items: List<Path>, target: Path): List<LibraryResult>  // 不进自身；颜色、置顶跟随
       fun copyInto(items: List<Path>, target: Path): List<LibraryResult>  // 撞名用「名 copy」「名 copy 2」；新身份；颜色跟随
       fun trash(items: List<Path>): List<LibraryResult>
       fun listTrash(): List<TrashItem>          // 新的在前；空包装清掉
       fun restore(item: TrashItem): LibraryResult   // 原文件夹没了回顶层；撞名用「名 (2)」；颜色恢复
       fun delete(item: TrashItem): Boolean
       fun emptyTrash(): Int
       fun purgeExpired(days: Int)
       fun search(start: Path, query: String): List<Path>
   }
   ```

   `LibraryResult` 区分成功（新路径）与失败原因（名称不合法、重名、目标在自身内、文件不存在、有待恢复记录、读写失败），界面据此给出提示。
2. 名称规则：不能为空、不能含 `/`、不能以 `.` 开头；重命名撞名（不区分大小写）失败不覆盖。所有路径必须在顶层文件夹内，旁车目录 `.xnote` 不可作为操作对象或目标。
3. 有待恢复记录（`LiteStore.pendingRecoveries()`）的文档：重命名、移动、删除进回收站时，恢复记录要跟着指向新位置；做不到就拒绝并返回「有待恢复记录」，不能让恢复记录指向不存在的文件。
4. `listIosDocuments` 读所在文件夹的 `colors.json`，填进 `IosDocumentEntry.color`；旁车继续隐藏。
5. 失败不留半成品：复制失败删掉已写的部分；移动、回收站失败原样留在原处。
6. 测试（commonTest，JVM 与 iOS 都跑；用临时目录或 okio `FakeFileSystem`）：
   - 互通：把 Android 写出的样例旁车（照 Android 代码的 JSON 形状手写样例文件，放 `shared/src/commonTest` 资源或测试内字符串）读对；iPad 写出的 `colors.json`、`color-names.json`、`pins.json`、`trash.json` 用按 Android 代码写的解析逻辑读回字段一致（键名、`version`、颜色十六进制格式、时间格式）。
   - 重命名／移动／复制／回收站／还原／清空／过期清理的正常与失败路径，颜色与置顶的跟随，复制的新身份（复制后两本 `documentId` 不同，原本不变），文件夹不能移进自身，搜索跳过旁车与回收站。
7. 不改 `app/`，不改 `app/src/test`。

验收：`:shared:testDebugUnitTest`、`:shared:iosSimulatorArm64Test`（`-PiosTestDevice=<UDID>`）、`:iosKit:iosSimulatorArm64Test` 通过，零失败零跳过；`:app:assembleDebug` 若被自动审批拦下就在记录里写明，由主代理补跑。在本文末尾追加「S3 实现记录」（中文，测试数，不写本机绝对路径）。只做本地提交。

## H2 界面（主代理）

- 条目 ⋮ 菜单与长按：重命名、颜色、置顶（笔记）、固定到侧栏（文件夹，存本机偏好）、移动到…、复制、剪切、删除（进回收站，可撤销提示）。
- 多选与选择条：全选、移动、复制、剪切、删除、颜色；芯片行出现「粘贴 N 项」。
- 侧栏启用：最近（本机记录打开过的文档）、回收站页（还原、永久删除、清空）、颜色分区（有颜色时出现）、固定的文件夹。
- 搜索：顶栏搜索框，在当前文件夹及子文件夹按名称搜。
- 颜色分组、颜色筛选。
