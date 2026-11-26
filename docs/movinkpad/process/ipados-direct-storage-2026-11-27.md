# iPad 存储改为直接读写，并合入主线（2026-11-27）

用户要求：继续 iPad 迁移，先改存储。主线已拆掉事务存储（见 `docs/movinkpad/storage/direct-storage-2026-11-26.md`），iPad 分支要跟上，iPad 自己的存储也改成同样的轻量做法。
状态：交 gpt-6.2-astra 实现；主代理接 iosKit 的后台保存并验收
本轮边界：本地合并与本地提交；不推送、不安装真机、不合并到 `master`

## 现状问题

iPad 用 `shared/.../storage/lite/`（`LiteStore`、`LiteSession`、`LiteBundle`、`LiteCreate`、`LiteRecoveryFiles`）：

- 打开：`BundleSnapshot` 把整份文件复制进私有目录并算 SHA-256，再整份 `checkSource` 重算一遍。
- 保存：`checkSource` 整份哈希 → 整份写临时文件（边写边哈希）→ `verify` 再哈希并整份解码读回 → 再哈希 → 协调写入时再 `checkSource` 整份哈希 → 原子替换。PDF 笔记每次停笔 3 秒自动保存都要过几遍几百 MB。
- 保存在主线程同步执行（`iosKit/.../InkEditor.kt` 的 `saveStable`），大文件会卡住界面。
- 恢复记录写入后同样整份解码校验。

## 第一步：合入 `master`

`git merge master` 到 `ipados`。`ipados` 已把 `format/`、`storage/` 的纯逻辑移到 `shared/src/commonMain`（okio／`com.xnotes.kio`，不用 `java.*`），`master` 在 `app/` 原位置改动或删除了同一批文件。处理原则：

- `master` 删除的类（`BundleSnapshot`、`BundleEntries`、`BundleReferenceInfo`、`BundleCopyPlan`、`NativeSourceVersion`、`NativeCreation`、`NativeDecodedContract`、`NativeReferenceReader`、`NativeSaveContract`、`ReferenceContract`、`DocumentRegistryIdentityEpoch`、`MigrationPublication`、`PreparedNativeOpen` 等）及其专属测试，在 `shared` 里也删掉；`ipados` 上仍引用它们的只有 lite 存储，第二步会改掉。合并提交本身要能编译，必要时第一步先临时保留、第二步删除，最终分支上不留。
- `master` 修改的格式类（`BundleAnchors`、`BundleHeader`、`BundleIdentity`、`BundlePdf`、`AnchorManifest`、`CanvasCodec`、`DocumentCodec`、`MapJson`、`RecordingBodyEvidence`、`ZipTail`、`NativeReferenceTarget` 等）：把 `master` 的行为改动移植到 `shared` 对应文件，保持多平台写法（`java.*` 只能出现在 `androidMain`）。`master` 新增的 `app/.../format/BundleRewrite.kt` 等如只被 Android 用可留在 `app`。
- 测试：`master` 的 `app/src/test` 按 `master` 原样采用；`shared` 里迁过去的同名测试同步成 `master` 的断言。不删弱断言，做不到的写明原因。
- 合入的其他功能（Zotero、图层切换、导图节点缩放、版本号）照常带进来。

## 第二步：iPad 直接存储

公开接口（`LiteStore`、`LiteSession`、`LiteOpen`、`LiteSave`、`LiteRecovery`、`LiteRefusal`、`LiteNoteSetup`）保持现有签名，另加下面的保存拆分。

1. **打开**：不复制整份文件、不算整份哈希。直接从原文件流式解码；元数据（身份、页编号标记、版本）用 `ZipTail` 读中央目录取得。版本过新、损坏、不支持的情况仍分别返回 `FUTURE_VERSION`、`CORRUPT`、`UNSUPPORTED_KIND`。
2. **版本戳**：`Stamp` 改为 文件身份（`st_dev`+`st_ino`）+ 大小 + 修改时间（纳秒或毫秒，取平台能拿到的最细），不再含 SHA-256。保存前比对戳，不一致按冲突处理（`LiteSave.Conflict` 并写私有副本，行为同现在）。
3. **内嵌 PDF**：iPad 的 PDF 笔记继续自带 PDF（不改成外置）。打开时解出的 `assets/source.pdf` 按「文档 UUID + 中央目录里该条目的 CRC 与大小」缓存在私有目录，同一份 PDF 第二次打开不再解出；缓存有上限或在文件删除／关闭后可清理，写明策略。
4. **保存**：
   - 笔记：已保存的前段资源（图片、音频、模板包、内嵌 PDF，均为 STORED）自上次读入／保存后没变，且尾部起点 ≥ 1 MB 时，照 Android `Editor.writeNoteInPlace` 只重写尾部：把新尾部写成私有临时小 zip，用 `ZipTail.splice` 原地拼到原文件末尾并同步到磁盘。需要把 `ZipTail.splice` 移植到 `shared`（基于 `KFileHandle`）。拼接中途失败就走下面的完整写回（同 Android 顺序）。
   - 其余情况（资源增删、画布、小文件）：完整编码到同目录临时文件，同步后原子改名替换。
   - 去掉写后整份解码读回校验和所有整份哈希。仍在 `NSFileCoordinator` 协调下写，写完用新的元数据更新戳。
5. **后台保存**：`LiteSession` 增加 `fun beginSave(): LiteSaveJob?`（主线程调用：不脏返回 null；在这里取模型写入快照与当前修订号）和 `LiteSaveJob.run(): LiteSave`（可在任意后台线程执行，同一会话同时只能有一个在跑）；`run` 完成后由会话在下次访问时按修订号判断是否仍脏。`save()` 保留为 `beginSave()?.run() ?: Saved`。`writeRecovery()` 同样拆出可后台执行的形式。这样主代理可以把 iosKit 的自动保存挪到后台。
6. **恢复记录**：记录版本升到 2，戳改为上面的新格式，不再写哈希、不再写后解码校验。已经存在的版本 1 记录仍能列出、恢复和丢弃（它们是用户数据）。
7. **新建**：去掉写后读回与哈希，保留排他发布（`link`）与重名编号。
8. 删除改完后不再被引用的旧类与测试，与 `master` 一致。

## 验收

- 测试（新增，commonTest 在 JVM 与 iOS 都跑）：
  - 打开不在私有目录留下整份副本；同一 PDF 笔记第二次打开不再解出 PDF。
  - 带 ≥ 1 MB 内嵌资源的笔记改笔迹后保存：文件身份不变、尾部起点前的字节逐字节不变、重开内容一致。
  - 增删图片后保存走完整写回，重开一致。
  - 外部改动文件（大小或修改时间变）后保存返回 `Conflict` 且写出私有副本。
  - 版本 1 恢复记录能列出与恢复；版本 2 记录写读往返。
  - `beginSave` 后继续编辑，`run` 完成后会话仍为脏；`run` 在后台线程执行通过。
  - 原有 lite 测试中检查哈希的断言改为检查新戳，其余行为断言保留。
- 全部通过，零失败零跳过：`:shared:testDebugUnitTest`、`:shared:iosSimulatorArm64Test -PiosTestDevice=<UDID>`、`:iosKit:iosSimulatorArm64Test -PiosTestDevice=<UDID>`、`:app:testDebugUnitTest`；`:app:assembleDebug` 与 Xcode 模拟器构建通过。
- 在本文末尾追加「实现记录」（中文：合并时怎么处理冲突、最终公开 API、PDF 缓存策略、测试数；不写本机绝对路径）。

## 主代理部分

iosKit 自动保存、离开、进后台改用 `beginSave` + 后台执行；保存中的状态与冲突提示照旧；模拟器上用大 PDF 笔记走查打开与连续书写不卡。
