# 思维导图续作检查点（2026-11-10）

用户要求：按完整持续需求逐步实现直到完成；原树不能绑定后，用户明确允许在它基础上新建工作树继续。
状态：D2d2、Canvas整文件／区域、分页区域及原PDF独立原文创建往返均已实现／主机验证；原文网络格式／状态与卡片流程亦已主机验证，独立证据复核通过并本地提交；整体未完成。
本轮边界：实现、相关编译／测试、文档、本地提交；不打包 APK、安装、推送、合并或发布。

## 工作位置

当前树 `/Users/kita/.codex/worktrees/mind-map-original-network/xnotes`，分支 `codex/mind-map-original-network`，再次按用户于2026-11-10明确要求，从原工作树 `mind-map-original-pdf/xnotes` 的实际HEAD `64fec4179beecbc838650f2b570ed36a9025ddc0` 新建。38个未提交文件逐文件SHA与Git差异清单核对复制，原树保持原样；迁移记录 `tmp/original-network-worktree-copy.json`。本地迁移／计划提交 `c604362e`。独立构建 `/private/tmp/xnotes-mind-map-original-network-build`、项目缓存 `/private/tmp/xnotes-mind-map-original-network-cache`，初始化脚本 `tmp/build-mind-map-original-network.gradle`。旧阶段证据已复制保留；不把旧验证当本阶段结果。全部作者／构建须用新树绝对路径。

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

## 原 PDF 独立原文阶段（verified）

新树源码9982ac5b、测试cf276494。PDF整页/区域actual菜单→独立mode request→同SHA原件catalog/永久页身份→已有/新图概念→PNG/保存冷开→专用只读Reader→同Entry/相机/选择/来源返回已接；原件库可list/read/明确delete/restore/relink同SHA。Note无页锚/dirty亦不补ID、不flush；首次打开导图通过request策略到Main真实persist消费者，不额外保存Note。原件与catalog身份未纳完整备份，不称可重建缓存。

独审Required均修并回归：IO-return prompt取消归属、SQL type/range截断、own精确pin保护、批坏frame隔离/late raster回收、旧reader宿主退休、合并pan/pinch锚点、真实Main默认flush路径。45983旧行为两取消例真实失败，fixed同例最终绿；85330首轮180/2失败仅两个独立确认夹具，保原XML。最终34437终态0/33s，31套185唯一case，0失败/错误/跳过，Android源码compile过，1158输入SHA全同；独立实现与证据结案通过。日志/XML/input/summary见tmp/original-pdf-*，runtime中所有作业终态、作者冻结。真实Android/PDFium像素/SAF/重建杀进程/MovinkPad Pro14 Android15 ProPen3/Surface/GPU/性能未运行。

## 原文网络整合（verified）

原文metadata/type/availability、默认／格式／目录筛选与状态观察、独立orphan、canonical首页整原件卡/v6、真实network→图→reader→图／同network、new/existing卡／retained/save重试已接。只读SQL边界有界、原件不read/hash/lease，坏资料不修／不清pin；native同UUID拒fallback。卡片专用原metadata票不被自身目标acquire重投影误退，仍拒实际catalog变化；旧导航严格票保持。

两位独立实现审核结案。56993页NUL旧行为2/2真红；5376首次301/7失败原证据保存，三真缺陷与四fixture按实际生产契约修；9002终态0/28s，55套302唯一项、0失败/错误/跳过，Android测试源码compile过，1174输入前后SHA全同。最终独立XML／输入证据复核通过，已分层本地提交58a186fe／786b6449／45a41073／a7ea3272／e6ad6edb；文档见HEAD。详见docs/movinkpad/original-pdf-network-2026-11-10.md和tmp/original-network-final-summary.json；无运行构建，源码已全冻。

## 下一实际动作

继续来源范围调整／换源与具体目标重核：先读最新SourceListEditor/来源选择、MapSourceTarget和原文projection合同，写完整可操作阶段并独审。保原正文／来源ID与历史PNG语义，不按旧页序号改指；实现→保存冷开→预览→实际打开返回一并验收。

仍缺可选同图出现／失效检查、导图来源打开与真正导图双窗格、Canvas侧反向引用、权限/身份/移动/回收站精确恢复、独立副本和完整引用文件备份、主要预览/页目录缩略图、同级插入/边缘平移和实际平台验收。原14组不缩减，整体active；阶段完成不是任务完成。

历史提交和证据在上文及对应记录保留。旧树不写入、不清理、不合并；所有后续开发仅在新树继续。
