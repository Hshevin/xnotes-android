# 思维导图续作交接（2026-11-10）

用户要求：在可验证阶段提交后换全新精简上下文，用 gpt-6.1-sol、ultra 思考接手同一工作树，继续完整目标“按计划继续逐步实现，直到完成”；旧对话停止编辑和自动循环。
状态：D2d1 完整操作流程已实现、验证并本地提交；**整体未完成**，下一项 D2d2。
本轮边界：实现、编译／测试、文档及本地提交；不打包 APK、安装、推送、合并、发布，不新建工作树或切默认分支。

## 启动核验

- **唯一工作树**：`/Users/kita/.codex/worktrees/mind-map/xnotes`；分支 `codex/mind-map`。
- 最后源码 HEAD：`0850b157ee0eb259cfdbf17a56d9e90e5a846cba`。随后仅有交接／说明文档提交。**交接实际完整 HEAD**、Git 清洁状态与终态作业锚点见 `tmp/mind-map-handoff-runtime.json`，新对话初始消息也会给出完整 HEAD；先核对，不 reset／checkout 来凑一致。
- 新对话若初始仍在项目默认目录 `/Users/kita/Documents/ChatGPT/笔记/xnotes`，先用 `attach_worktree` 绑定上述既有树并核验返回目录，再读 Git。工具若拒绝复用，报告限制并停止写入，不能默默在默认目录继续，也不能用 handoff_thread 代替新对话。
- 首读本文件、`AGENTS.md`、`MOVINKPAD.md`、`README.md`、`docs/movinkpad/project-brief.md`、[持续需求基准](../docs/movinkpad/mind-map-requirements-current.md)。`tasks/loop-state.md` 很长，只按需查历史证据，勿复制长对话。

## 完整目标与已确认选择

持续需求基准逐项保留原 14 组要求，原阶段规格链接在其中。原生 Kotlin，独立 `.xmap`，本地离线，无新账号／云／后端／AI 依赖，复用现渲染／编辑／历史／文件身份，不另起编辑引擎。永久页 UUID 引用与文档 UUID 分开；对象编号及后继跟踪按用户后续决定取消，不重新启动 B2c。保存已写而后续失败时保留已写版本，放弃只作用于之后的新修改。原 PDF／旧笔迹字节、PDF SHA-256、资源租约与恢复保护不变。

用户纠偏：交付可操作的完整流程，减少基础设施前置；审核只在新阶段必要边界和实质风险，未变有效证据不重复审查／重跑。不要把计划、模型或测试夹具当功能完成；也不要回头以整套 native 保存迁移作为前置，除非当前流程真的复现阻塞。

## 当前已有实现

- 文本／手绘原位与放大编辑、共享历史、多来源、分支／自由节点、普通关系线、多选／移动／对齐、三种可取消布局、保存重开及失败／冲突保护。
- 笔记／批注 PDF 页面来源创建、永久页定位、来源预览与刷新、跳转返回、来源侧反向列表与精确单条移除；真实平台仍未验收。
- 文件网络局部／多文件／全库、来源格式／目录／集合筛选、逐层展开与共享折叠、完整概念证据、具体来源进图和同网络返回。网络是只读派生场景，不是 MapSession 或写入权限。
- **新完成 D2d1**：网络 FILES 选择 → 加入内部／系统选择的已有图，或系统保存新关系画布 → 普通线编辑、撤销／重做、移动 → 保存关闭重开 → 回原网络范围／相机／选择。成功加入后不强制打断网络，用户点击查看才进入图。
- 真正无中心、可空的 `RELATION_CANVAS`；普通导图仍保护唯一中心。显式 `fileCardId` 按文件 UUID 去重，卡片为 REFERENCE＋单 FILE 身份来源，标题／备注独立。身份来源只用于打开／预览，不混作内容引用；卡片普通线可保存编辑，但**尚未展示在自动网络或文件侧栏用户关联组**。

本片源码提交：`cf0391d7`（组织／格式／身份／历史）、`b7880534`（确认发布与选择器）、`0850b157`（实际入口与返回）。此前 D2c 为 `a257f42c`、`80d049c3`。普通图仍写 v3，含无中心组织或卡片写 v4，读 v1—v4，旧应用拒读 v4。

## 验证与运行状态

本片记录：[network-to-relation-canvas-2026-11-10.md](../docs/movinkpad/network-to-relation-canvas-2026-11-10.md)。25158 已终态 1：405 项中 403 过、两夹具设置／清理失败，Android 编译过；修复及 SAF 确认时序调整后，41460 已终态 0：16 相关项全过。精确替换 15、新增 1，**合并 406 项，失败／错误／跳过 0**，保留未受影响 390 项，没有重跑全仓旧全量。

逐项证据：`tmp/network-to-canvas-related-summary.json`、`-recheck-summary.json`、`-verified-summary.json` 和同名日志；1076 输入 SHA 在 `tmp/network-to-canvas-recheck-inputs.json`，运行后全同。完整 Host 实际跑了上述保存关闭重开／返回链，原生源文件原字节不变、零写入。独立阶段审核通过，Required 全闭合。

JDK 21；隔离输出脚本 `tmp/build-mind-map.gradle`，项目缓存 `/private/tmp/xnotes-mind-map-cache`。相关任务为 `:app:testDebugUnitTest`、`:app:compileDebugAndroidTestKotlin`，环境 `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`、`ANDROID_HOME=/Users/kita/Library/Android/sdk`，使用 `--offline --no-daemon`。旧全仓结果不是当前全仓验证，不为安心重复运行。

**未运行**：真实 Android 仪器／DocumentsUI／SAF、配置重建／杀进程、MovinkPad Pro 14／Android 15／Pro Pen 3、掌触／Surface／GPU／性能。新增 Android JSON 2 项和实际 Main 流程仅源码编译。无 APK／安装／推送／合并／发布。

交接前全部作者冻结并结束，已知构建／测试会话均终态，无未结束作业；未提交源码修改为零。`tmp/` 保留忽略的证据与运行锚点。接手时重新核 Git 和作业状态，勿凭此段直接假设当前仍一致。

## 下一个具体动作

完成 **D2d2：显式文件关联 → 自动网络／来源侧用户关联组 → 定位其真实画布关系并编辑／返回**。从当前 `MapBacklinkLibrary` 的同次实际图读取和 live overlay 收集 `fileCardId` 端点之间的 `MapRelation`，复用实际 MapDocuments／MapSession／MapWorkspace；给用户关联独立的证据类型／稳定关系 ID、方向和说明，不能伪造 `MapSource` 成员，不从普通 REFERENCE 或概念多来源推导文件依赖。局部／多中心／全库、筛选、聚合与展开都保留内容引用／用户关联的区别。一个小阶段接完整操作，验证并本地提交，再继续原范围下一项。

关键现接口：`MapFileCards.kt`、`NetworkToCanvasController/Flow`、`LocalNetworkCoordinator.openCanvas`、`LocalFileNetworkController`、`LocalNetworkScene`、`MapBacklinkLibrary`、`ReferencesTab`。现批量票已核精确 node→file UUID／寿命／历史代次；新建先固定候选，系统 Save 按原 nonce 直接发布，已有图确认前零 acquire；失败只重存或显式续同候选。

其他仍缺：可选同图出现／失效检查；区域／Canvas 来源与 Canvas／导图来源打开（当前来源打开主要 NOTE/PDF）；真正导图双窗格；权限／身份／移动／回收站和永久删除的精确修复；独立副本与完整引用文件备份；主要预览、页目录缩略图；同级插入／边缘自动平移；全部实际平台／目标设备验收。它们继续保留，不能以 D2d1 已过而删掉或宣称只待收尾。
