# 思维导图续作检查点（2026-11-10）

用户要求：按完整持续需求逐步实现直到完成；原树不能绑定后，用户明确允许在它基础上新建工作树继续。
状态：D2d2、Canvas整文件／区域来源及分页区域创建往返均已实现／主机验证；整体未完成，下一原PDF独立原文模式。
本轮边界：实现、相关编译／测试、文档、本地提交；不打包 APK、安装、推送、合并或发布。

## 工作位置

当前树 `/Users/kita/.codex/worktrees/mind-map-region-continue/xnotes`，分支 `codex/mind-map-region-continue`，按用户于 2026-11-10 的明确要求从上一续作树 HEAD `4f413d16dfec5aef207da75b922be9e6ab74aed4` 创建，45 个本阶段未提交文件逐一 SHA-256 校验后复制，原树原样保留。迁移清单见忽略的 `tmp/worktree-transfer-2026-11-10.json`，当前作业见 `tmp/mind-map-region-runtime.json`。接手须核 Git，不 reset 来凑一致。两个旧树只读保留，勿默默在默认目录写代码。

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

## Canvas 区域来源阶段

已接实际选区／截图→固定世界 Rect 的 CANVAS_REGION→已有／新图和概念→保存重开／PNG 裁切→实际挂载定位／返回。含 v5 及旧 raw 拒绝、完整来源观察、显式严格原位保存、未保存画布的 typed 新 UUID 副本与同模型安全采用、晚到 C1 dirty、同候选续完及旧选择器隔离。FILE＋region 仍拒绝，源对象不编号、不跟随。独审发现的误入 NOTE 另存及 pending 定位卸载重放已修。

78922为251中250过、Android源码编译过、1112输入SHA全同；唯一Main轮询夹具超时仅修测试线程，81852单项复验过，逐case替换有效合并251项全绿、0失败/错误/跳过。独立实现／夹具／证据复核通过。详见 docs/movinkpad/canvas-region-sources-2026-11-10.md；tmp/canvas-region-verified-summary.json 与 -recheck-inputs.json。无运行构建。真实平台／设备仍未运行。

## 分页区域阶段

已接单页选区／套索／截图创建→固定actual Doc/Page/localRect→永久pageId＋PAGE.region→已有／新图与概念→保存／失败重试→正式PNG冷重开→实际来源／同图返回。跨页与gap拒绝，旧菜单候选取消／删除后撤销／换模型不复活；request签后不因收菜单取消。Note实际丢item／pending和明确XML失败不授区域空证明或引用触存；区域首读／晚B重读严格，默认普通Note保持宽容。PDF区域明确当前批注笔记上下文，原件字节保留。

54454为281中279过、Android源码compile过、1121输入全同；93555与30768只修两新夹具（SPEED才保存time、分页先relayout、相机数值±0），保原有效279，逐case替换合并281全绿。独立计划／实现／证据复核通过。详见docs/movinkpad/paged-region-sources-2026-11-10.md；tmp/paged-region-verified-summary.json/-locator-recheck-inputs.json；无运行构建。

## 下一实际动作

接原PDF原文独立内容模式：先核源指纹／原PDF页及当前Note永久页映射、source模型／预览／定位的可复用点，写精确typed契约并独审，再完整创建→持久化／预览→打开返回。不能复活废弃includeAnnotations字段或把资源hash当docUUID；当前批注上下文不等于原件来源。继续保留原14组，不重复未变有效测试，不以全native4迁移作前置。

仍缺可选同图出现／失效检查、PDF独立原文模式／范围调整、导图来源打开与真正导图双窗格、权限/身份/移动/回收站精确恢复、独立副本和完整引用文件备份、主要预览/页目录缩略图、同级插入/边缘平移和实际平台验收。整体保持 active，阶段完成不是任务完成。

Canvas本地提交：`60dfacfa`（格式／预览／完整读取）、`c711b11b`（实际入口／保存／副本采用／定位返回）、`8c787750`（主机与Android事件测试源码）。

分页本地提交：`69ccde43`（完整读取／固定预览）、`d5c8fcc2`（实际入口／请求／定位）、`40ce03b5`（主机与Android菜单测试源码）。
