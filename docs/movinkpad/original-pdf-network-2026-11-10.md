# 原 PDF 在文件关系网络中的身份与状态（2026-11-10）

用户要求：在当前工作树基础上新建工作树，继续按完整思维导图需求自主实现、验证和本地提交。
状态：实现中；计划已独立审核，首次生产源码编译通过，相关行为测试与实现独审尚未完成。
本轮边界：仅源码、相关编译／测试、文档、本地提交；不打包、安装、推送、合并或发布。

状态：in_progress，完整计划已独立审核；聚合输入上界／真实 GC 墓碑、已接受创建续接、实际 no-fallback、冷库只读四项 Required 及跨域 epoch 约束均纳入。基线 `64fec417` clean；上一 goal turn 为 progress：原文创建／原件库／只读阅读往返已实现、185 相关测试及 Android 源码编译／独审通过，并本地提交。全部已知构建终态，不重复启动旧作业。

范围：持续需求第 7—13 组的原文网络衔接，原完整 14 组不缩减。本轮仍仅实现、相关编译与测试、文档、本地提交；不打包、安装、推送、合并或发布。按2026-11-10最新要求，从原工作树64fec417新建codex/mind-map-original-network，带入38个未提交文件并逐字节核对，原树完整保留；使用新树独立构建／项目缓存。

## 用户结果

- 局部／多文件／全库的格式筛选区分「笔记／PDF 批注」与「原 PDF 原文（原件库）」。相同 PDF 的不同批注 Note 与独立原文保持各自 UUID，不按标题／后缀猜类型。
- 原文的删除、原件缺失、资料不支持／损坏等状态在默认网络及筛选后都可见。保留真实关系、完整概念、来源备注与最后 PNG；已知删除／缺失仍能按原文类型匹配。不能因一条原文不可用阻断同概念其他来源或查看所属图。
- 原件库不属于普通笔记目录，不继承任何批注 Note／导入 URI 的目录。目录筛选明确排除该来源，混合概念仍保留它作上下文，界面解释归属。
- 全库按剩余额度列出独立保留而尚未入图的原件，多文件选择可选。真实关系优先于孤立文件；超额说明不完整，不隐藏已有引用。
- 原文可作为文件身份加入新／已有关系画布，保存重开后仍能用真正原文 reader 打开并返回。文件卡片不产生内容引用；显式卡片普通线继续投影为手工关联。不能制造 FILE＋原文 UUID 的不可打开卡片或禁用该流程来回避接线。
- 真实链路：网络证据→所属导图具体来源→原文 reader→同图／相机／选择／来源条目→同网络／筛选／展开／相机。脏 native Note／Canvas 不额外保存；原文开失败保图和原模型。

## 只读事实与预算

`OriginalPdfCatalog.networkMetadata` 返回独立 metadata batch，不复用会物化全页 UUID 的 `observe/list`，不发原 Observation／native 保存证明。输入包含有界文件 UUID、逐目标永久页 ID／SHA／ordinal 描述、可选剩余 orphan 额度。Header 仅保存有界标量与 canonical 首页永久页锚，不含页数组。 请求文件最多10000个；独立orphan暂存最多min(250,maxSceneNodes)，不因请求已满10000就跳过，也不无条件预留名额缩减纯Native结果。最多10250个候选共用总页预算；最终choices仍限10000、scene按原预算，真实证据／中心优先，再用实际有效的Original及Native孤立文件填余量，超限明确提示。

分类区分 ABSENT、VALID、CORRUPT、UNSUPPORTED、LIMITED；资源 presence 为本次 stat 提示，绝不等于 PDF 内容已核实。验证 schema／原始 SQL 类型与范围、完整 own pinset、资源登记及受管路径；页目录用有界 SQL 聚合核数量／ordinal／原始类型／canonical UUID，不把全部页目录带入 JVM。总页元数据预算默认至少允许一个最大合法原件（`ReferenceAnchors.MAX_TARGETS`），批量总额有界；超预算明确 LIMITED，不能把截断结果当损坏或完整有效。目标只按永久页 ID 点查并核 projection SHA／index，不按 ordinal 改指。

聚合须限制输入子查询（或等效可证扫描上界），不能只给最终 COUNT/MIN/MAX 结果 LIMIT；加 raw 超量页行回归。deleted 原文经历真实 GC 后，资源登记行消失是合法状态，仍保原文类型／TRASHED；active 缺 resource 登记与普通文件缺失分别处理。

禁止读取／hash／解码 PDF，禁止创建 engine、lease、正文快照或目录。不得调用会 hash／登记的 `PdfResources.resolveChecked/holdResolved`，也不初始化会 mkdir 的 PdfResources。只 stat 已验证的私有 `ai-pdfs/$sha.pdf`，坏登记路径不得访问外部文件。坏／未来／缺失身份只分类，保全部 pin 和资料，不修复、不注册新 UUID、不清日志。

新 metadata／domain API 独立只读打开已经存在的 SQLite 文件，不借 `readableDatabase` 自动 create／upgrade。缺库不创建；旧 schema 没有 catalog 表判 ABSENT；未来／能力未知不迁移、不假称已支持。冷缺库／旧库／未来库检查文件、表、版本、PDF 目录和字节前后不变。原有普通 native index bootstrap 不在本片重写，完整网络的其他旧入口按已初始化 registry 前置验证，不把 warm fixture 或本 API 结论扩大为全应用冷启动零 SQL 写入。

catalog 与 native documents／locations／page contracts，以及实际观察到的 map 同 UUID 时明确身份冲突，不任选一个，也不从原文缺失降级 native 打开。已有错误 FILE＋原文 UUID 卡片不自动换目标；保卡／旧图并报告不匹配，来源打开不得因此误走同 UUID 的另一原生文档。

no-fallback 验证须调用实际 MapSourceNavigator，不能只测 facts：构造 FILE＋原文 UUID 的旧坏卡，同时登记同 UUID 的真实 Note，打开必须失败保图，不调用原生采用。PDF_ORIGINAL 的 catalog absent／unsupported 同样拒绝，普通 native 来源保持可打开。只读 identityDomains 观察固定原票；最终采用核同 domain 票，Actual Original Access 亦拒 native 同 UUID 冲突，不靠 metadata UI 挡住。

## 状态、筛选与生命周期

NetworkFileFactsReader 同时接 ids 与 exact MapSourceTarget，输出文件类型／目录、逐目标状态、orphan 列表及只读 batch.current。文件种类与来源可读状态分开：有效 catalog 的 tombstone／缺失资源保 PDF_ORIGINAL kind；坏／未知／冲突身份才是未知类型。元数据不得把旧来源升为 fresh CURRENT，不验证像素／范围内容或生成 EMPTY。

逐目标诊断仅复制到派生网络 row 的显示状态，不改 MapSource、历史、目标、备注、PNG、membership 或用于所属图权限的 blocked。坏成员不毒化整概念，查看所属图仍按真正 map membership；实际 reader 按 Access 全 SHA／完整 catalog 契约重核。

Controller 默认也读取原文事实，不沿原先无筛选的 emptyFacts 快路漏检；完整 targets 不先按指定文件筛掉，以保混合概念上下文诊断。filter selector 对 known kind 的可用性不混作 UNKNOWN；目录事实未知不得冒充 OUTSIDE，明确原件库域则 OUTSIDE。

metadata 从调用开始捕获 canonical DB 共享 network epoch，IO／provider／stat 后及 Main 最终采用均核同票。catalog mutation START 立即退旧票／旧动作，END 通知重投影；不能等异步通知到 Main 才使旧请求失效。独立的 NetworkEpoch(version,busyCount)／只读 StateFlow 不改变旧 Observation／Candidate 的写入与恢复证明。Catalog.mutate 和 Access.relink **完整资源 IO** 各用内部 token bracket，失败也 finally END；不能将原 beginRelink 的瞬时检查当作实际修复 END，nested busy 不能提前清零。busy 期间不签新 metadata batch。只读 observer 不保 Activity／View／Editor。catalog delete→restore ABA、失败回滚或 relink 都不能复活旧 batch。captureFocus／relation／集合／加卡片快照的原有效性闭包纳 batch.current，不只依赖 actionGeneration。关闭／取消退休 metadata work 与 observer。

native 身份首次出现且同 UUID 已有 raw Original 占位时也要退票。检查在实际写事务内覆盖 xnote/xcanvas/xmap；START 在身份 INSERT 前，token 由最外层事务在实际 commit／rollback 后 END，嵌套不得早结束。NetworkEpoch 锁内只改内存，不取 SQL／catalog／resource 锁。普通已有图 acquire/register 不改变此 epoch，避免合法网络打开因自己登记旧图而失效。须测慢 metadata 中新增碰撞、失败回滚旧票不复活，以及完整 relink／并发 nested busy 不提前归零。

## 原文文件卡片衔接

MapFileCardSeed 冻结 exact target；普通卡仍 FILE。原文卡仅接受 canonical PDF_ORIGINAL target（catalog UUID＋首页永久 pageId＋实际 SHA，pageIndex=0、viewTurns=0、userRegion=null、普通 region=null）。它代表整份原件，首页是已有可翻页 reader 的初始锚，不能从某条区域来源复制非首页范围。

MindMapDocument／addFileCards／MapSession receipt／NetworkToCanvas matching 均使用同一校验。相同 UUID 异 target 不得 distinctBy 丢弃或覆盖旧卡；已有卡字段不被重新加入覆盖。source ID／exact target 参与已追加结果核对，不能只凭 fileCardId 相同判重。保存重试只重存已经固定的图 payload；catalog 变化阻止旧选择继续新增，但已成功追加后不能重取目标／重复追加或丢失保存失败的图内容。

新图也在实际接受固定 create payload 时切开旧 selection 票：已接受发布、CREATE_RETAINED 续完、已发布后的重存／查看只核固定 candidate／graph receipt／exact source 与 node，不再依赖 selection.current。仅未接受的新建／追加继续受 metadata 票约束。provider 发布阻塞期间 delete/relink、retained 显式续完、保存失败后 epoch 改变均保同 payload，不创建第二份或漏掉已写成果。

继续按 fileCardId 排除卡片来源的内容引用；visitFileRelations 不再只认 FILE，复用 canonical card 校验。原文 projection 已使 JSON 写 v6，普通卡版本不变；旧 v1—5 raw gate 保持。实际打开只走已有 OriginalPdfNavigator／reader，不发 native 采用结果或保存权限。

## 分工与验证

- A：catalog 独立 typed metadata／有界 SQL／presence／epoch 通知与存储测试；必要只读 identity-domain 检查，不改旧写入契约。
- C：network facts／filter／controller／派生 row／orphan 及域测试。
- B：canonical 原文 fileCard 及 selection／receipt／relation 提取衔接，真实网络→图→reader→图／网络与加卡片 Host 回归。
- root：计划／独审、筛选及详情 UI／三语解释、相关跨模块接口协调、统一构建／证据／文档和本地提交。作者不各自构建、提交或派生代理。

必须验证：默认及格式／目录筛选、Note 与同 SHA 原文不混身份、混合概念好坏成员、retained orphan／预算、deleted／GC 后缺失／坏 SQL／未来记录／页映射／native UUID 碰撞、START／END／IO 慢回调／ABA、只读扫描零 PDF body／engine／lease／写入；新旧关系画布的混合卡一次撤销、同 UUID 异 target 冲突、显式线投影、保存失败仅重存、v6 冷重开与真正打开返回；保 PNG／脏 native／原 PDF 字节。

沿用相关回归，不重跑未变全套。真实 Android JSON／事件边界保仪器源码并编译；未经实际运行，Android／DocumentsUI／SAF／Compose 重建、PDFium 像素／MovinkPad Pro14 Android15 ProPen3／Surface／GPU／性能都不能宣称通过。原文范围调整／换源、完整引用备份、导图来源／双窗格和其余完整需求仍待后续阶段。
