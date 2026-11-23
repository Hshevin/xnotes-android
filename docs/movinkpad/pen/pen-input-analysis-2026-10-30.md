# MovinkPad 真机输入日志分析（2026-10-30）

用户要求：分析用户提供的 MovinkPad 真机输入日志。
状态：已完成分析。
本轮边界：仅按数据分析；未修改应用，未重新连接硬件测试。

## 原始证据与完整性

- 文件：`pen-input-1793337896987.json`（原始日志仅在诊断工作区本地保存，不包含在 fork），原样归档，7,786,901 字节。
- SHA-256：`eec4edd4647d43963c41996313f535c3500c5499617643ffa75ebc6ab023f79a`。
- 导出时间：`2026-10-30T14:24:56.978+09:00`（Asia/Tokyo；来自日志时间戳）。
- schemaVersion：1；应用版本：0.1.0。
- 事件共 3921 条，序号 1–3921 连续；元数据总数与保留数一致，丢弃数为 0，事件时间非递减。
- 3919 条 motion、2 条 marker、0 条 key；开始至导出标记相隔 183.096 秒，包含空闲间隔，不能据此推算输入采样率或书写延迟。
- 全部场景均为“无按键基线”，没有按物理按键分别命名。原始日志证实按钮值及输入表现；第一／二／三键与物理位置的对应沿用用户先前说明。

## 设备与系统

- manufacturer：`Wacom`；model：`DTHA140`；Android 15 / SDK 35。
- buildFingerprint：`Wacom/Wacom_MovinkPad_14/Wacom_MovinkPad_14:15/M01V0010MS/20260721:user/release-keys`。
- 笔设备：`Wacom HID 422 Pen`，vendorId 1386（`0x056A`），productId 1058（`0x0422`）。本会话 deviceId 为 8；事件 source 为 `0x00005002`。
- 手指设备：`Wacom HID 415 Finger`，vendorId 1386，productId 1045（`0x0415`）。本会话 deviceId 为 7；事件 source 为 `0x00001002`。
- 笔有 3907 条 motion，手指有 12 条。deviceId 是本次会话中的标识，后续不能硬编码为 8 或 7。
- 16904 个笔指针样本（含 12997 个历史样本）全部为 `TOOL_TYPE_STYLUS`（2）；未观察到 `TOOL_TYPE_ERASER`。仅凭设备名称不能进一步识别笔的实物 SKU。

## 按钮映射证据

以下只统计笔设备，排除手指的无按钮事件：

| 状态（用户编号） | buttonState | 笔 motion 事件数 |
| --- | --- | --- |
| 不按键 | `0x00000000` | 2435 |
| 第一键 | `0x00000020` | 1060 |
| 第二键 | `0x00000040` | 291 |
| 第三键 | `0x00000004` | 121 |

三个非零按钮值均见于悬停事件及接触书写的 DOWN / MOVE / UP 事件。没有观察到多键组合掩码；不能据此确认或否定组合按键支持。

最关键的输入特点：

- 整份日志只有一组 `ACTION_BUTTON_PRESS` / `ACTION_BUTTON_RELEASE`，对应第一键：事件 #1530（buttonState 32、actionButton 32）与 #1576（buttonState 0、actionButton 32）。
- 第二键与第三键没有独立的 BUTTON_PRESS / BUTTON_RELEASE；其状态变化可见于 HOVER_MOVE。比如 #625 开始为 64、#749 恢复为 0；#2359 开始为 4、#2372 恢复为 0。
- 第一键也多次只在 HOVER_MOVE 中体现变化。因此不能只监听 BUTTON_PRESS / BUTTON_RELEASE。
- 未收到 KeyEvent；本次笔侧键应通过 motion 的 buttonState 识别，不按键码或 Ctrl 等修饰键识别。此结论仅限这次设备与系统配置。

## 实际观察到的轴与取消行为

以下范围包含当前及历史笔样本，是本次操作范围，不代表硬件极限：

| 轴 | 观测最小值 | 观测最大值 | 说明 |
| --- | --- | --- | --- |
| PRESSURE | 0 | 0.6726895571 | 设备声明范围 0–1；应用收到归一化压力，不能据此验证 8192 级全部可区分 |
| TILT | 0.4739843905 rad | 0.9509267807 rad | 约 27.16°–54.48°，存在变化 |
| ORIENTATION | -1.7854559422 rad | -0.3672341108 rad | 方向角存在变化 |
| DISTANCE | 22 | 140 | 原始轴值；设备声明范围 0–255，未证明单位为毫米 |
| RZ | 0.0017421603 rad | 0.0017421603 rad | 设备声明该轴，但本次恒定，不能认定支持笔杆旋转 |

手指事件 #3366 为 `ACTION_CANCEL`，flags 为 `0x00000020`，导出 canceled=true。它证明真机此次发送过取消事件，但没有证明取消原因一定是手掌检测，也没有验证 FLAG_CANCELED 的 UP 分支或防误触整体效果。

## 后续实现依据

1. 结合笔设备／source／toolType 识别输入；source 可能包含多个能力位，不用严格等于单个 SOURCE_STYLUS 常量作为唯一判断。
2. 在收到的每条相关 motion 上比较原始 buttonState，计算 `pressed = current & ~previous`、`released = previous & ~current`；按位识别 32、64、4。独立按钮事件也经过同一状态比较，避免重复触发功能。
3. 初次接入时建立状态基线；失焦或取消时明确清理派生交互状态。HOVER_EXIT 不等同于物理松键：本次有携带非零按钮位的 HOVER_EXIT。
4. 保留 MotionEvent 历史样本；本次历史样本占据大部分笔样本，丢弃它们会降低轨迹信息完整性。
5. 将按钮识别和橡皮擦／撤销等功能绑定分开；功能绑定仍由后续需求决定。

按键编号的统一入口仍为 [Pro Pen 3 按钮位映射](pro-pen-3-button-mapping-2026-10-30.md)。这次证据补齐了设备／系统与按钮输入表现，尚不构成完整的笔记软件兼容性或性能验收。
