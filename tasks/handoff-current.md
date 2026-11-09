# 思维导图续作检查点（2026-11-10）

用户要求：按完整持续需求逐步实现直到完成；原树不能绑定后，用户明确允许在它基础上新建工作树继续。
状态：D2d2 完整流程已实现并验证，整体未完成；下一阶段 Canvas 具体来源打开与返回。
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

## 下一实际动作

继续接 Canvas 具体来源单文档打开与返回，优先复用 `MapSourceNavigator`、现 `Editor` 参考打开／停车／采用与 `MapDocuments`，按文档UUID和可选区域提示定位，不恢复Canvas分屏、不重编码原件。先核已实现接口和有效证据，不重做旧全仓审核、未变重测或整套native4保存迁移前置。一个实际可操作阶段完成后验证并本地提交，再继续完整需求。

仍缺可选同图出现／失效检查、区域/Canvas创建、导图来源打开与真正导图双窗格、权限/身份/移动/回收站精确恢复、独立副本和完整引用文件备份、主要预览/页目录缩略图、同级插入/边缘平移和实际平台验收。整体保持 active，阶段完成不是任务完成。
