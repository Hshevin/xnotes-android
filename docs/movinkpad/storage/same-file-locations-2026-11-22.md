# 系统选择器与工作目录的同文件位置（2026-11-22）

用户要求：修复 MovinkPad Pro 14 / Android 15 上从系统选择器新建画布、再从 Download 工作目录打开时误报重复 UUID 的问题，保留真实副本和无法判断的位置。
状态：已实现，JDK 21 / Robolectric 的 storage、ui、pen 测试通过，Android 仪器测试编译通过；模拟器运行和目标设备复测待验。
本轮边界：本地源码、测试、文档及提交，无 APK 打包、安装、推送或发布。

## 位置与登记表

同一提供方的 authority 和 document ID 沿用原有键。跨键比较通过 ContentResolver 同时以只读模式持有两个描述符，分别用 Os.fstat 检查普通文件、有效 inode、设备号和 inode 编号。两者相同时保留一条可解析位置。不同或无法证明时保留各自的位置，正常打开时报告重复。比较不读取文档正文。

工作目录树 URI 优先，同优先级按 URI 字符串顺序决定。对于同一个提供方键的两种写法，如果旧写法无法证实仍可打开，则采用本次实际观察到的 URI。登记和旧位置合并均在 SQLite 事务内完成。

v4 在 v3 的基础上增加了 location_alias_expectations 表。它记录合并掉的键和原 UUID，使旧入口即使在重启或授权失效后仍能拦截身份替换和误判为空白新文件。判断仍是同一文件时重新探测。另存或恢复的写入凭据仍绑定原目标键。升级 v1、v2、v3 保留已有行；读取某个 UUID 的旧重复行时才探测与整理。只支持 v3 的旧构建会拒绝打开 v4。

## 重复检查入口

| 路径 | 行为 |
| --- | --- |
| Editor.registerForOpen 及失败诊断 | 正常打开和错误提示都从已整理的位置计数。NotebookStorage.ensure 不会因某个提供方无法打开描述符便把该位置标为失效。 |
| DocumentRegistry.locations / resolve | 对旧版已写入的多行在事务内重新读取、比较和合并，保留同 UUID 的真实副本。最近使用、会话恢复、AI 和导图来源使用同一登记结果。 |
| Editor.prepareReference / 打开已加载的引用 | 来源准备和采用前的登记计数读取同一结果；初次及复查的 liveReferenceOwners 检查仍然保护实际编辑模型。 |
| NotebookStorage.referenceKind / verifyReferenceSource | 来源类型检查和核实登记后的唯一性检查使用同一结果。 |
| Editor.checkedPagedSource / 页编号启用的 registeredHere | 对当前 URI 和保留位置使用同一文件比较。分页分屏在身份票据有效期内只读比较，以免清理旧记录时让自己的票据失效。显式选取的分屏成员仍分别核实来源与拥有者。 |
| DocumentRegistry.registerObserved(uniqueSource = true) / verifyCreation / registerCreated | 严格登记在事务中拒绝其他可用文件；创建重试同时检查不可用位置、类型与页契约，保留不同文件或证据不足时的拒绝行为。 |
| NotebookStorage.rediscover / Editor.relocateMissing | 搜索多个工作目录时仅计入不同文件，显式重新定位时保留同一文件的可用登记位置。 |

## 验证

执行 :app:testDebugUnitTest，按 com.xnotes.storage.*、com.xnotes.ui.*、com.xnotes.pen.* 过滤。分别通过 281、183、322 项，共 786 项，失败、错误、跳过均为 0。

DocumentRegistrySameFileTest 的 8 项均通过：

- pickerThenTreeKeepsOneTreeRowAndPreservesLocationProtection
- treeThenPickerKeepsTheTreeAndRepeatedObservationsAreStable
- differentFilesWithOneUuidRemainADuplicate
- unknownPhysicalIdentityRemainsADuplicate
- persistedAliasesAreReconciledBeforeResolutionWithoutRemovingACopyOrTouchingOtherDocuments
- retiredGrantRemainsAnIdentityExpectationWithoutProvingTheFileIsStillTheSame
- aCreationRetryAndUniqueObservationAcceptOnlyTheProvenAlias
- explicitReassignmentThroughAnAliasKeepsTheOtherPhysicalCopyWithItsOriginalUuid

NotebookIdentityReadTest.openingAFileDoesNotRetireAnotherLocationWhenItsProviderCannotProveFileIdentity 通过，覆盖普通打开路径。v1/v2 升级、v3 已有重复行和未知版本拒绝打开均经过 JVM 验证。

AndroidSameFileProbeTest.differentContentUrisForOneInodeMatchButAByteIdenticalCopyDoesNot 通过 :app:compileDebugAndroidTestKotlin 编译，已加入 PenTestRunner。在应用私有缓存创建硬链接和独立副本，由 FileProvider 向真实 ContentResolver 提供不同的 content URI。本轮未在 Android 上执行。
