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

## S3 实现记录

文件库位于 `com.xnotes.storage.library`。iPad 文件列表按所在目录读取颜色，每个文件夹的 `.xnote` 旁车继续隐藏。复制分页笔记与画布使用 Android 的同一套 `BundleMigration.prepare(copy = true)`，逐个核验文件夹中的副本，保留笔迹采样与 PDF 原件字节。重命名和移动保留原身份，并更新被移动文件夹下各笔记的置顶路径。进入回收站会移除置顶，还原时恢复颜色。

恢复记录保存了原文件路径和来源凭据，打开的恢复会话也持有这条路径。本次采用契约允许的拒绝方式。用户恢复保存或明确放弃记录后，可以再次移动原件。新副本或还原目标也不能占用待恢复的缺失原件路径。最终移动前锁定并核对恢复索引，索引读不全时回滚本项操作。

各项操作独立提交。先准备副本和旁车更改，最后移动文件，失败时撤回本项的旁车写入并清理暂存。永久删除先把完整包装移出回收站，再从磁盘删除；途中遇到写入错误或中断，下次列出回收站会继续清理。原目录找不到时还原至顶层；同名时从 ` (2)` 起编号。

### 公开 API

方法名和返回类型沿用建议形状。构造器的 `clock` 增加系统毫秒时钟默认值，`store` 须管理同一文件系统与顶层目录。以下接口均为同步 IO；批量结果与输入逐项对应。成功路径是操作完成后的绝对路径，回收站项目的 `path` 也是包装内的实际文件或文件夹路径。`originalFolder` 为从顶层到原目录的名称列表，`deleted` 为 Unix 毫秒时间。

```kotlin
class Library(fs: FileSystem, root: Path, store: LiteStore,
              clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    fun colors(dir: Path): Map<String, Rgba>
    fun setColors(dir: Path, changes: Map<String, Rgba?>): Boolean
    fun colorNames(): Map<Rgba, String>
    fun setColorName(color: Rgba, name: String?): Boolean
    fun findByColor(color: Rgba): List<Path>
    fun notePins(): List<Path>
    fun setNotePinned(doc: Path, pinned: Boolean): Boolean
    fun rename(item: Path, newName: String): LibraryResult
    fun moveInto(items: List<Path>, target: Path): List<LibraryResult>
    fun copyInto(items: List<Path>, target: Path): List<LibraryResult>
    fun trash(items: List<Path>): List<LibraryResult>
    fun listTrash(): List<TrashItem>
    fun restore(item: TrashItem): LibraryResult
    fun delete(item: TrashItem): Boolean
    fun emptyTrash(): Int
    fun purgeExpired(days: Int)
    fun search(start: Path, query: String): List<Path>
}

data class TrashItem(val path: Path, val isDir: Boolean, val originalFolder: List<String>,
                     val deleted: Long, val color: Rgba?)

sealed interface LibraryResult {
    data class Success(val path: Path) : LibraryResult
    data class Failure(val reason: LibraryFailure) : LibraryResult
}

enum class LibraryFailure {
    INVALID_NAME, NAME_CONFLICT, TARGET_IN_SOURCE, NOT_FOUND, PENDING_RECOVERY, IO_ERROR
}
```

`com.xnotes.storage.ios.listIosDocuments(dir: Path): List<IosDocumentEntry>` 沿用原签名。`IosDocumentEntry` 构造器末尾新增 `override val color: Rgba? = null`。

### 验收

JDK 21，iPad Pro 13 英寸（M5）模拟器，iPadOS 27.2。以下计数取自 Gradle 的 JUnit XML 报告。

| 测试任务 | 通过 | 失败／错误／跳过 |
| --- | ---: | --- |
| `:shared:testDebugUnitTest` | 2,739 | 0／0／0 |
| `:shared:iosSimulatorArm64Test` | 2,550 | 0／0／0 |
| `:iosKit:iosSimulatorArm64Test` | 40 | 0／0／0 |

18 项新增公共用例分别在 JVM 与 iPad 模拟器运行。互通用例读取按 Android 实现编写的颜色、颜色名称、置顶和回收站样例，再用独立的字段解析逻辑检查 iPad 写出的四种文件。文件操作用例覆盖嵌套副本身份、笔迹与 PDF 原件、失效置顶、回收站时限、恢复保护、越界与符号链接、发布失败回滚及清理重试。iOS 另新增 1 项目录列表用例，验证父子文件夹独立取色与旁车隐藏。

三套测试通过后，`:app:assembleDebug` ARM64 构建成功。本轮仅在本地提交，真机文件操作待验。
