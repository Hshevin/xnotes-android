# Canvas 整文件来源打开与返回（2026-11-10）

用户要求：在新续作工作树按完整思维导图需求持续实现；D2d2 已提交后继续接 Canvas 来源的真实操作流程。
状态：已实现；187 项相关主机测试及 Android 测试源码编译通过，独立复核通过；整体未完成，实际 Android／目标设备仍待验收。
本轮边界：从 4373587d 继续实现、相关编译／测试、文档与本地提交；不打包 APK、安装、推送、合并或发布。

## 本片流程与边界

现有来源选择已经能添加 Canvas。接通具体整文件来源的“打开”或单来源引用节点双击，进入实际单文档 Canvas；编辑后明确返回同一导图、相机、选择与具体来源条目。已打开 Canvas 保同一模型、历史、视口和未保存修改，不重建编辑器模型，也不因查看或返回强制保存来源。

本片只接整文件 Canvas，按实际文档 UUID 定位。Canvas 不接受 pageId，`FILE + region` 仍按原模型禁止；区域来源创建、持久化、预览裁切与临时定位另成下一可操作片，不冒充本片能力。Canvas 不进入或恢复分屏。

沿用 PreparedNativeOpen／NativeSourceVersion／Editor 原生读取和采用，以及 MapSourceNavigator／MapWorkspace 返回书签。请求冻结经实际 live 模型或身份登记并实际源验证的 kind 与相应模型；未知的显式 URI 必须只读核实际类型，不按文件名猜。冷打开首次读取与最终复核都匹配 kind、UUID、唯一位置和实际源版本；旧笔迹、PDF 原字节及资源租约保护不变。

独立计划审核已通过，补入以下边界：live 重复身份检查跨 NOTE／CANVAS；retained Canvas 走 awaitCanvasCapture，不误用分页屏障；采用与隐藏图同 Main 不挂起。另发现实际 Main 的 persistNativeForMapPresentation 会默认保存停放原生文档，Canvas 来源返回必须只为这次呈现跳过此操作，普通 NOTE／普通图打开及网络原策略保持。新 Host 测试需调用真实 Main 消费者并让负对照具备实际写入能力，不能只用不会自动保存的夹具冒充零写证明。

## 验证范围

- 实际 Canvas 类型／UUID／重复位置、页或区域拒绝、取消／迟到、冷打开保存后重读；保原件字节。
- 已打开脏 Canvas 保实际模型、历史、视口与输入，其他 native owner 沿现保存／放弃／取消保护。
- 导图具体来源→Canvas→编辑→返回同图／具体来源条目／相机；返回等待 Canvas 笔事务，取消不丢内容或书签。
- 实际 Main 持久化消费者跳过本次 Canvas 返回，下一普通打开恢复原策略，防止跨呈现泄漏。
- 原 NOTE／PDF 参考打开、MapSourceNavigator、原生停车与 MapWorkspace 回归；Android 新 Main 场景源码编译。

## 执行记录与平台边界

首编 50322 终态 1：生产源码编译通过；新 Host／Android 夹具直接访问私有保存目标字段导致测试编译失败，已改为夹具反射设置，不扩大生产接口。尚无该轮行为测试结果。

独立审核确认原 CanvasCodec 可略过未知／资源缺失对象而仍获参考编辑权限；已将严格逐项解码和实际对象证明限定接入 Canvas 参考首读及最终版本重读。默认宽容读取不变；失败清理临时资产、保原文件且不采用／不授新 editSource。采用后的正常编辑不受初始对象列表限制。已补原始坏包、有效待处理资产顺序、同数量对象替换和真实保存期间坏 B 重读回归；静态独立复核通过。

相关复验 69241 终态 0：**187 项全部通过，失败／错误／跳过 0**；Android 测试源码编译通过。1066 个源码／资源／配置输入 SHA 运行前后全部相同。证据在忽略的 `tmp/canvas-source-recheck.log`、`tmp/canvas-source-recheck-inputs.json`、`tmp/canvas-source-verified-summary.json`，不是全仓重跑。

覆盖新存储 13 项、新原生参考 Host 9 项、新 Map Canvas Navigator Host 5 项，以及原 NOTE／PDF、默认 CanvasCodec、原生停车／MapWorkspace／前阶段网络往返回归。真实 Main 保存消费者既有 Canvas 返回零写断言，也有下一普通打开实际产生写入的正对照；不是不能写的夹具冒充零写。

原始未知对象／缺失图片在首次打开及真实 outgoing NOTE 保存期间换入同 UUID、同类型坏 B 的最终重读路径均拒绝采用。后一场景允许并验证已授权的 outgoing NOTE 正常保存一次，Canvas B 原字节不被覆盖；不把它说成所有 native 文件零写。严格解码修复及最终实际证据均经独立复核，无未闭合必要问题。真实 Android 仪器／SAF、配置重建／杀进程、MovinkPad Pro 14／Android 15／Pro Pen 3、掌触／Surface／GPU／性能仍未运行。主机验证不能替代这些平台验收。
