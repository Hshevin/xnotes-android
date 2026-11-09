# 思维导图续作检查点（2026-11-10）

用户要求：按完整持续需求逐步实现直到完成；原树不能绑定后，用户明确允许在它基础上新建工作树继续。
状态：D2d2 与 Canvas 整文件来源完整流程已实现并验证，整体未完成；下一阶段 Canvas 区域来源创建与定位返回。
本轮边界：实现、相关编译／测试、文档、本地提交；不打包 APK、安装、推送、合并或发布。

## 工作位置

当前树 `/Users/kita/.codex/worktrees/mind-map-continue/xnotes`，分支 `codex/mind-map-continue`，由原 `codex/mind-map` 的 `63371f5d13ca4d7457249d468c315db59cc69b5c` 创建。当前完整 HEAD／状态和已知作业见忽略的 `tmp/mind-map-continue-runtime.json`，接手须核 Git，不 reset 来凑一致。原树只读保留，勿默默在默认目录写代码。

先读本文件、AGENTS.md、MOVINKPAD.md、README.md、docs/movinkpad/project-brief.md 和 docs/movinkpad/mind-map-requirements-current.md。完整14组范围不缩减。页面级永久 UUID、Canvas 文档 UUID、不恢复对象编号、原笔迹/PDF字节保护和失败保留已写版本不变。

## 当前新增能力

D2d1 已交所选文件加入可保存关系画布并返回网络。D2d2 现从同次实际图读取及 live overlay 提取显式 `fileCardId` 两端普通关系，投影为独立用户关联证据：局部／多文件／全库、筛选、与概念交替展开、跨图聚合、来源侧手工组→原画布真实选线→编辑／保存／删除→原网络／阅读文件返回。卡片身份来源不当内容引用，不从概念多来源推文件依赖。

每条证据保原图位置／关系ID、端点／文件UUID、方向／类型／说明及独立寿命票。打开复核真实 actor、位置/身份/恢复、双端折叠祖先和原生停车；跨图重复聚合只减渲染边，不丢具体记录。存盘路径在外部 actor 出现／退休后退旧票，目标图本次独占首次 acquire 可签同图实际前缀，不额外 acquire 前驱图。保存冲突进入实际观察快照，报告更新前也拒旧 live 请求。

## 验证

45869：生产/Android源码编译过，root新unit夹具Unit.join误写导致未执行测试；已修。
72986：153项152过，1新回收站夹具缺登记；补真实登记后保TRASHED断言。
93077：108项107过，1新增真实冲突Host发现conflict未纳snapshotFlow；已修观察并加旧票拒绝断言。
4017：终态0，81受影响定向全过。逐suite替换保未变有效证据后合并159项，0失败/错误/跳过；Android源码编译过；1063最终输入SHA全同。独立审核及必要修复复核通过。证据：tmp/file-relations-verified-summary.json、-conflict-inputs.json与同名前轮日志/summary。正式记录 docs/movinkpad/file-relation-exploration-2026-11-10.md。

未运行：真实 Android／DocumentsUI／SAF、配置重建/杀进程、MovinkPad Pro 14/Android 15/Pro Pen 3、掌触/Surface/GPU/性能。未打包或安装。

## Canvas 整文件来源阶段

已接具体来源打开／单来源引用节点双击→实际单文档 Canvas→保活脏模型／历史／视口→返回原 MapEntry／相机／选择／来源条目。请求冻结实际类型；未知显式位置只读实际 manifest，不按名字猜；live 重复身份跨 NOTE/CANVAS。Canvas 整文件仍不带 pageId/region、不恢复分屏。

独审实际发现旧CanvasCodec可drop未知／缺asset对象后仍授editSource，现仅参考Canvas首读及最终源变化重读用严格逐项物化及采用前实际对象proof，旧默认reader不变。Main原生persist消费者对Canvas来源返回只跳过本次呈现，下一普通打开恢复原策略，有实际可写正对照。

50322终态1：production编译过，test私有字段夹具编译失败已修；69241终态0：187相关项0失败/错误/跳过，Android源码compile过，1066输入SHA全同。独立复核通过。证据tmp/canvas-source-verified-summary.json、-recheck-inputs.json、-recheck.log；正式记录docs/movinkpad/canvas-source-navigation-2026-11-10.md。真实平台仍未运行。

## 下一实际动作

继续接 Canvas 区域来源创建→保存重开→预览裁切／实际临时定位→返回原图。现 `FILE + region` 被模型禁止，本片整文件流程不放宽，需明确持久范围表示与兼容，不能只放宽 Navigator 参数。区域只是世界坐标提示、不恢复对象编号、不改原件。先核当前相关接口和有效证据，完成一个真正可操作阶段后验证、本地提交，再继续。

仍缺可选同图出现／失效检查、分页区域创建、导图来源打开与真正导图双窗格、权限/身份/移动/回收站精确恢复、独立副本和完整引用文件备份、主要预览/页目录缩略图、同级插入/边缘平移和实际平台验收。整体保持 active，阶段完成不是任务完成。
