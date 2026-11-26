# iPadOS Apple Pencil 输入契约（2026-11-23）

用户要求：iPad 版以 Apple Pencil Pro 为测试笔；笔不支持压感时按固定压力计算。本文为第二波 W2-3 的输入契约，见 [iPadOS 移植方案](ipados-migration-plan-2026-11-23.md)。
状态：契约与 RED 测试已写，待实现
本轮边界：只做本地提交；不推送、不打包、不安装

## 范围

- 纯逻辑放在 `shared/src/commonMain/kotlin/com/xnotes/pen/pencil/`，测试在 `shared/src/commonTest/kotlin/com/xnotes/pen/pencil/`（`PencilStrokeAssemblerTest`、`PencilGesturesTest`）。
- iOS 宿主（`iosMain`）只负责把 `UITouch` 和 `UIPencilInteraction` 回调转换为下列数据并调用，不在宿主里做判断。

## 笔迹采样：`PencilStrokeAssembler`

- `PencilSample(x, y, timeSec, force, maxForce, estimationIndex: Long?, forcePending: Boolean)`：坐标已由宿主换算到内容坐标；`timeSec` 为 `UITouch.timestamp`；`forcePending` 对应 `estimatedPropertiesExpectingUpdates` 含 `.force`。
- `PencilStrokeAssembler(pressureSupported: Boolean)`：宿主按 `traitCollection.forceTouchCapability` 及触摸类型判断是否支持压感。
- 压力：`pressure = clamp(force / maxForce, 0, 1)`；不支持压感或 `maxForce <= 0` 时恒为 `CONSTANT_PRESSURE = 1.0`（与 Android 手指绘画相同）。压力曲线以真机手感为准，后续可能调整映射，不改接口。
- 时间：`Sample.t` 为距第一个采样的毫秒数。
- `begin(sample)`：开始新笔画；已在笔画中时丢弃旧笔画，重新开始。
- `move(coalesced, predicted)`：coalesced 依次追加到 `committed`；`predicted` 替换上一轮预测，只供湿墨显示，永不进入结果。
- `updateEstimated(index, force, maxForce): Int`：把对应 `estimationIndex`、仍在等待更新的采样压力改为新值，返回该采样在 `committed` 中的下标；未知、已更新过、不在等待、无压感或笔画已结束时返回 -1 且不改动。
- `end(last): List<Sample>`：追加最后的 coalesced 采样，返回最终采样并复位；结束后迟到的属性更新被忽略（决定：不为最后几毫秒的压力修正推迟提交笔画）。
- `cancel(): List<Sample>`：返回已提交采样并复位，是否保留由宿主按 Android 现有规则处理。
- `active`、`committed`、`predicted` 只读。

## 双击与捏压：`PencilGestures`

- `PencilGesture { DOUBLE_TAP, SQUEEZE }`；捏压只在 `.ended` 时上报一次。
- `PencilPreferredAction` 对应系统偏好（`UIPencilInteraction.preferredTapAction` / `preferredSqueezeAction`）：`IGNORE`、`SWITCH_ERASER`、`SWITCH_PREVIOUS`、`SHOW_COLOR_PALETTE`、`SHOW_INK_ATTRIBUTES`、`SHOW_CONTEXTUAL_PALETTE`、`RUN_SYSTEM_SHORTCUT`。
- `PencilBinding`：`FollowSystem`（默认）、`Tool(id)`、`Command(id)`、`None`。用户绑定优先于系统偏好。
- 跟随系统时：切换橡皮 = 在橡皮与之前的工具间切换；切换上一个 = 回到上一个正式选择的工具；三种调板 = 命令 `SHOW_TOOL_PALETTE`（`"show_tool_palette"`）；忽略与系统快捷方式不做事。
- `TOOLS` 与 `COMMANDS` 与 Android `PenBinding.tools` / `PenBinding.commands` 相同。`toggle_eraser`、`toggle_previous`、`toggle_pan` 在内部改变工具，不交给宿主；其余命令以 `PencilOutcome.commands` 交给宿主；未知 id 忽略。
- 手势只做 SELECT 与命令，没有 HOLD；工具变化即正式选择，更新「上一个工具」。
- 笔画中（`strokeActive = true`）收到的手势排队，返回当前工具不变；`onStrokeEnd(cancelled = false)` 按顺序应用并只应用一次，`cancelled = true` 全部丢弃。与 Android「笔画中的按钮转换仅在正常抬笔后提交」一致。
- `manualSelect(tool)`：用户在工具栏手动选择，同样更新「上一个工具」。
- `PencilOutcome(tool: String, commands: List<String> = emptyList())`。

## 悬停与笔身旋转

- 悬停（`UIHoverGestureRecognizer`、`zOffset`）只显示工具光标，不产生笔迹；触摸开始即结束悬停。由 W2-3 在宿主实现，真机验收。
- 笔身旋转（`rollAngle`）本轮只采集、不使用。

## 验收

- 上述两个测试类在 JVM 与 iosSimulatorArm64 上通过。
- 延迟、压感手感、悬停、捏压、双击只在 iPad Air（M2 及以后）+ Apple Pencil Pro 真机上验收，单独记录；模拟器无法验证。
