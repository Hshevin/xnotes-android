# 架构与实现审核（2026-11-10）

用户要求：仔细检查此前实现的架构与细节，由 Claude 修复，由主代理独立编写测试并复核。
状态：首批已实现并经 JVM/Robolectric 回归验证；共享窗格与剩余架构边界继续审核，待真机验证。
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
