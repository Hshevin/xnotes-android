# 架构与实现审核（2026-11-10）

用户要求：仔细检查此前实现的架构与细节，由 Claude 修复，由主代理独立编写测试并复核。
状态：两批已实现并完成源码复核、完整 JVM/Robolectric 套件验证；Android 事件与目标设备待验证。
本轮边界：仅本地代码、测试和提交；不打包、安装、推送或发布。

## 审核基线与方法

基线为 `48041b53`，开始时工作区干净。先检查近期冷恢复、工作副本、导图混合宿主和保存交接，再沿实际调用检查生命周期与持久化。以下是分阶段审核记录，不代表全仓库已完成逐行审核。

主代理负责失败用例与最终验证，Claude 负责生产实现。测试使用真实 Editor、私有会话文件与宿主协调器；不把 JVM/Robolectric 结果当成 Android 进程终止或目标 GPU 验收。

## 首批发现

### 必须修复：原生恢复入口与文件格式校验漂移

`LastPresentationCodec.validateNative` 与 `Editor.prepareColdNativePresentation` 各自维护一套契约。前者限定比例为 0.18—0.82、校验有限视图坐标；后者只限定比例在 0—1，且不校验视图。直接传入内存描述可准备一个无法持久化的恢复请求，甚至携带 NaN 坐标。非法请求还能先创建副 Editor。

独立新增 `ColdNativeValidationHostTest`：非法比例与非有限坐标两例在基线失败，合法边界通过。应使用同一语义校验入口，在读取缓存、创建窗格之前拒绝非法请求；JSON 语法校验仍由编解码器负责。

### 必须核验并修复：视图状态未完整进入观察与凭据

`Editor.presentationChanged` 的手工 `List<Any?>` 不包含 `viewOverrides` 与 `zoomLocked`；`updateViewOverrides` 的滤镜/滚动条路径不通知呈现变化，`toggleZoomLock` 也不通知。冷呈现观察器只观察 `presentationVersion` 等值，因此纯视图操作可能未生成新 marker，重建时旧 marker 的视图覆盖新设置。

`checkpointWorkingSession` 会写入 zoomLocked，但其 `stable()` 不核对该字段，因此旧凭据可能在锁定状态改变后继续被视为有效。新增真实宿主往返用例与凭据失效用例确认行为，不能通过在测试中手动 checkpoint 来掩盖自动观察缺失。

### 架构整改：职责与状态契约集中

基线 `Editor.kt` 13,360 行、`MainActivity.kt` 3,795 行、`InteractionController.kt` 5,362 行。行数是审查线索，不单独证明错误。实际问题是新增恢复协议直接进入巨型 Editor、相同规则分叉，以及依赖位置的无类型状态列表漏字段。

首批整改应围绕恢复描述校验和完整的呈现状态快照建立明确边界；避免引入巨大通用接口或公开 Editor 私有字段来机械搬运代码。后续仍需检查保存/恢复协调职责与主窗口路由，不能以这一批局部修复宣布整体架构问题已解决。

## 验证记录

- `ColdNativeValidationHostTest`：基线 3 例，2 例预期失败，1 例通过。
- 纯视图修改自动发布与重建恢复：基线超时失败，修复后通过。
- 缩放锁定使旧 checkpoint 失效：基线断言失败，修复后通过。
- Android 事件测试、目标设备、Surface/GPU：本轮尚未验证。

## 首批修复与复核

Claude 实现 `LastPresentationContract`，编解码与内存原生恢复共用语义校验，非法描述在分配窗格前拒绝；原有 JSON 语法、预算及版本兼容不变。Editor 使用命名的 `PresentationSnapshot` 和 `NotePresentationView`，会话元数据捕获与凭据校验共用视图快照，补齐普通设置操作的通知。

主代理复核差异，并补充“显式覆盖值等于默认值，渲染不变但持久化状态改变”的用例。新增测试均由主代理编写；Claude 未修改测试。

主代理扩大执行 `*Cold*`、`*WorkingCheckpoint*`、`*LastPresentation*`、`*NativeSavePicker*`、`*MixedNoteArrangement*`、`*NativeAction*` 回归，JDK 21、隔离构建和项目缓存，结果通过。Claude 另完成 `compileDebugAndroidTestKotlin`，仅代表仪器测试源码可编译，未在设备执行。

未采用“视图更新必须另加一次 session write”的建议：现在正常宿主观察器已经会生成完整 checkpoint，额外写入需先证明必要性，避免双写与新竞态。共享窗格独立锁定状态的恢复仍在进一步核查。

## 第二批：共享窗格的状态契约仍不完整

首批已本地提交 `4f73e049`，112 项扩大回归通过。随后主代理新增 `SharedColdViewHostTest`，两个用例均在首批修复后的实现上失败：

- 主窗格不锁定、副窗格锁定，序列化实际 checkpoint 并创建新 Editor 恢复后，副窗格锁定丢失。
- 已取得整组呈现凭据后只改变共享副窗格锁定，旧凭据仍返回有效。

根因是 `ColdNativeView` 没有锁定字段，Shared 分支既不写自己的会话正文，也没有其他持久化载体；共享分支的最终证明只核阅读位置。首批的完整快照只覆盖了有正文的拥有者，不能替代共享读者的状态契约。

第二批要求集中完整的逐窗格视图值，借由冷呈现描述保存独立锁定。新增字段必须使用显式格式版本（v3），读取兼容 v1/v2；旧版本缺省字段不能伪造覆盖值，非法类型和未来版本继续拒绝。主代理新增 `LastPresentationZoomLockTest` 验证兼容与严格解析，并将原“未来版本”测试值从即将支持的 3 改为 99。

## 第二批实现与兼容复核

Claude 将 `zoomLocked` 纳入 `ColdNativeView`，移除首批临时的二次视图封装；会话元数据组装与锁定状态安装各集中为一个入口。正文拥有者与共享读者均捕获、比较和恢复同一逐窗格视图值。新记录显式存储 true/false，旧记录缺字段保持 null；正文拥有者回退至原会话元数据，共享读者不继承拥有者的锁。

含新锁定字段的 Native/Mixed 冷恢复记录写 v3；不含字段仍维持原 v1/v2 输出。v1/v2 不接受新字段，v3 字段必须是 boolean；未知版本、未知字段、空值和类型强转仍拒绝。旧构建读取 v3 会保留但不自动恢复该布局；没有升级 `.xnote` 正文或授予来源覆盖权限。

第二批 Claude 运行 118 项相关回归与仪器测试源码编译通过。主代理进一步补充旧记录读取实际元数据、显式 false 覆盖旧 true、Mixed 视图字段往返三项测试，并执行完整 JVM 套件，最终结果见后续验证结论。

## 架构结论与后续边界

这轮不是对全部文件逐行审核。已沿冷恢复、工作副本队列、原生/共享窗格、混合宿主、保存选择器、来源权限等路径审查，并对实际发现建立失败证据。重点结论：问题集中在宿主层状态契约重复、散落和不完整；不能将整个项目概括为没有设计。

- `core` 的导入检查未发现直接依赖 UI、平台存储、导图或 AI 的路径；核心模型/几何并非直接混在 Activity 中。
- `LegacySessionQueue`、`ProtectedNoteSessionSource` 保留串行拥有者、已接受任务、实际文件复核与取消清理；这些保护不能为了缩短 Editor 而删掉。此次没有重写保存权限或笔迹编码。
- `Editor` 同时承担工作副本、打开/采用、输入、视图、文件浏览与导出；`MainActivity` 的 Compose 局部函数承担保存提示与多种工作区路由。巨型文件仍存在。后续拆分宜按“输入事务与捕获”“呈现及打开协调”“文件浏览/缩略图”划界，以实际拥有者和完整状态值为接口，不能仅把私有字段公开后搬到 helper。
- 平台恢复层依赖 `mindmap.MapNativeRestoreHint`，原 PDF 存储入口依赖 `mindmap.MapSourceTarget`，说明跨功能来源描述的归属仍偏向最初使用它的功能。适合后续在已有契约测试保护下迁至中立领域类型；本轮不做单纯改包名的大面积变更。
- `LastPresentationStore.commit/matches` 在同步最终核验中读取本地标记文件；标记有 64 KiB 上限，但尚未测量目标设备停顿。不能据此宣布性能已合格，也不应将最终复核贸然异步化而打开竞态窗口。
- 显式保存的旧分屏描述主要使用 `ReadingPosition`，与冷恢复完整视图仍有差异；这不是本轮已经修复的能力。另行扩展时需独立处理格式兼容，而不能把冷恢复 v3 等同于所有 `.xsplit` 已升级。

剩余上述结构/性能/显式分屏演进项目不构成已复现的数据丢失结论。本轮已复现并纳入修复的缺陷是重复校验漂移、纯视图更新漏通知、锁定变化凭据失效遗漏和共享窗格锁定丢失。

## 最终验证

- 主代理执行 `:app:testDebugUnitTest` 完整套件：510 个测试类、4765 项测试，失败 0、错误 0、跳过 0；JDK 21，结果通过。
- 本轮主代理新增 15 项测试（含校验反例、自动发布/恢复、独立锁定、凭据失效和旧格式回退）。基线失败日志保存在忽略的 `tmp/architecture-audit-*-red.log`，完整绿灯日志为 `tmp/architecture-audit-full.log`。
- Claude 报告 `:app:compileDebugAndroidTestKotlin` 通过，但其文件日志为空；主代理独立执行该任务成功（UP-TO-DATE），日志为 `tmp/architecture-audit-android-compile.log`。未运行 Android 仪器测试，没有设备安装或目标 MovinkPad/GPU 验收。
- 未打包、推送或发布；保留本地分阶段提交与用户既有内容。
