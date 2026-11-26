# 拆除事务存储，回归直接流式打开与尾部快速保存（2026-11-26）

用户要求：排查打开大文件仍然卡顿的原因；确认重型事务存储与多次重复 SHA-256 对单机离线笔记没有必要后，要求主代理制定计划并交给 gpt-6.2-astra 彻底拆掉这套流程。
状态：已实现并复核合入本地 `master`；全量 JVM／Robolectric `testDebugUnitTest` 与 `compileDebugAndroidTestKotlin` 通过；仪器测试未在设备上运行；MovinkPad Pro 14 真机大文件打开与保存流畅度待实测。
本轮边界：本地规划、子代理实施、主代理复核修正、测试验证与本地提交；不打包、不安装、不推送、不发布。

## 背景与原因

`pause-only-recovery`（2026-11-23）去掉了编辑期间与离开文档时的恢复检查点，但打开与文件保存仍走 11 月初为旧文件关系网搭建的重型事务路径：

- 打开一次带页编号的大笔记会经历 3 次整包落盘快照复制（含 `fsync`）、十余遍整包级别的流式读取／解压／SHA-256（含 `BundleEntries.digests` 对全部图片附件做 `ZipFile` 与 `ZipInputStream` 双遍哈希），以及采用前的无条件二次 `openSource` 快照重验。
- 外置大 PDF 批注笔记在 `PreparedNativeOpen` 中两次调用 `NativeDecodedContract.note`，绕过 `PdfResources` 缓存直接对数百 MB 的受管原 PDF 重算两遍 SHA-256。
- `openSingleFromHome` 打开成功后立即调用完整 `persist()`，额外触发一次会话磁盘写入（工作区外文件还会抓快照重编码）。
- 分页笔记保存不再走上游 `ZipTail` 原地替换末尾 `manifest.json`，每次停笔自动保存都会重写整份候选包并经 `MigrationPublication` 做原件／候选双备份落盘与多次整包哈希。

## 拆除与保留范围

拆除的重型组件（共删除 107 个源码与专属测试文件，净减约 1.8 万行）：

- 存储与见证：`MigrationPublication`、`NativeSourceVersion`、`PreparedNativeOpen`、`NativeCreation`、`NativeDecodedContract`、`NativeHandoffAcknowledgement`、`NativeReferenceReader`、`NativeSaveContract`、`ReferenceContract`、`OwnedRecoveryTarget`、`OwnedTargetPresence`、`PreparedProtectedNote`、`PreparedProtectedCanvas`、`PreparedRecoveryPreview`、`RecoveryCopy`、`DocumentRegistryIdentityEpoch`。
- 容器快照与双遍校验：`BundleSnapshot`、`BundleEntries`、`BundleReferenceInfo`、`BundleCopyPlan`。
- 工作会话与恢复预览：`NativeWorkingSession`、`WorkingSessionCache`、`PreparedLegacySessionMutation`、`LegacySessionQueue`、`ProtectedNote*`、`ProtectedCanvas*`、`RecoveryPreviewSource`、`RecoveryPreviewHost`、`ColdPresentationProblemBar`、`PreparedHomeGate`、`NativeHostParking`、`NativePresentationIdentity`、`LastPresentationAttempt`、`LastPresentationContract`。

保留并简化的核心能力：

- 单遍流式打开：`NotebookStorage.ensure` 用 `ZipTail` 直接读取 ZIP 末尾中央目录中的 `BundleIdentity`、`AnchorMetadata` 与内嵌 PDF 标记（零整包复制、零整包 SHA-256），随后 `Editor` 直接从输入流单遍解码模型；`openSingleFromHome` 不再调用 `persist()`。
- 大 PDF 零重复哈希：`PdfResources.resolve` 对应用私有目录内已存在的 `<sha256>.pdf` 只核对文件存在且非空并确保登记，仅在导入新 PDF（`admit`）或手动重新关联缺失底稿（`restore`）时计算一次 SHA-256；保留旧内嵌包首次打开的 `compactPdf` 提取、缺失底稿重连、仅笔迹副本与便携导出（`embedPdf = true`）。
- `ZipTail` 原地尾部快存：`Editor.writeNoteSafely` 优先调用 `writeNoteInPlace`，当笔记前序 `STORED` 图片／音频／模板资源未变且总大小达到阈值时，只把几十 KB 的新尾部（含外置 PDF 标记与页面永久编号）通过 `ZipTail.splice` 原地写到文件末尾并 `force(false)`；增删资源或保存画布时，先完整编码到私有临时文件再经 `NotebookStorage.writeBundle` 单次落盘（自有目录原子 `move`，外部 SAF 单次流式写出并 `sync`）。
- 导图页面链接与升级兼容：`DocumentRegistry` 保持 SQLite 版本 4 及表结构兼容；`AndroidSourceCatalogs` 改为基于 `ZipTail` 的轻量目录与 `manifest.json` 读取；`BundleRewrite` 统一承接不重编码笔迹的元数据升级、副本改身份与 `enablePageIds` 页编号启用；保留录音恢复 `AudioRecovery`。
- 暂停与冷启动：编辑与切换文档不写会话正文；`onPause` 等待在途自动保存后写一次 `last-presentation.json`，干净已保存文档标 `reopenFromFile` 并清除会话正文，未保存文档或自动保存失败时写 `SessionStore` / `CanvasSessionStore` 副本。

## 主代理复核与修正

复核 `t3/remove-transaction-storage`（`478c1457`）时发现并修正两处细节：

- `Editor.decodeFile` 原先在后台 IO 线程直接改写调用方 `Editor.savedAssets`：一方面未采用／已取消的打开会冲掉当前活动笔记的已保存资源记录，另一方面双窗格成对打开（`prepareNativePairOpen`）由根窗格统一解码，导致副窗格 `savedAssets` 为空、主窗格被副窗格 URI 覆盖，两侧首次保存都无法命中 `ZipTail` 原地拼接。改为将 `savedAssets` 放入 `DecodedFile`，在 `replaceDocument` 清空，仅在实际采用到目标窗格时写入对应 `Editor.savedAssets`，并在 `DirectBundleStorageHostTest` 增加双窗格首次保存原地拼接回归测试。
- `NotebookStorage.metadata` 与 `AndroidSourceCatalogs.readContent` 在 `ZipTail.read` 返回空（如 ZIP64）时回退 `ZipFile(/proc/self/fd/<fd>)`；在 Android SELinux 下跨进程 SAF 描述符按路径重开可能触发权限拒绝（`SecurityException` / `FileNotFoundException`），补充捕获后将已打开的 `FileChannel` 归零并回退顺序流读取。

## 验证

- 命令：`./gradlew --offline --no-daemon --max-workers=4 --project-cache-dir <仓库根>/tmp/gradle-direct-storage :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`。
- 全量 Debug JVM／Robolectric 单元测试（含新增 `DirectBundleStorageHostTest` 单遍读取、外置 PDF 零重读哈希、单窗格与双窗格 `ZipTail` 原地尾拼接及增图回退完整写回测试）全部通过；`compileDebugAndroidTestKotlin` 编译通过，保留原开发分支 `t3/remove-transaction-storage`。
- 边界：未运行 Android 设备仪器测试；MovinkPad Pro 14 / Android 15 真机上的大笔记、大 PDF 与大画布打开及保存体感待用户实测（若大画布主线程笔迹三角化或图层缩略图仍有上屏瞬间停顿，可在真机确认后单独优化渲染侧）。
