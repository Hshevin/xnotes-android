# 混合分屏进程恢复（2026-11-10）

用户要求：继续完整思维导图流程，先同步主线修复并保留已完成改动，再接混合布局的进程恢复。
状态：已验证布局记录、NOTE／Canvas受保护工作副本、普通布局自动记录与冷恢复（JVM及源码编译）；实际Android与目标设备待验，完整目标未完成。
本轮边界：实现、相关编译／测试、文档及本地提交；按用户本次要求同步主线，不打包、安装、推送或发布。

S3a0 verified；整体思维导图目标 active，complete=false。S1/S2 已提交至
`c97f58f9676e9e1a75906184778269b6dd6c00c1`，本轮按用户要求新建 `mind-map-cold-restore/xnotes` 工作树，分支
`codex/mind-map-cold-restore`；原工作树保持不动。
上轮为 progress：混合保存／显式重开已实现、51套387项有效相关用例及源码
编译和独立审核通过。沿既有实现／测试／文档／本地提交授权，不打包、安装、
推送或发布。用户本轮授权同步主线后继续，已同步master8d12bacf及实际fetch的
origin/master8ee33799（仅test5文档），基线61ba44c3；先完整保留草稿后复原，
README使用较新主线说明，loop-state合并两侧状态。新树使用独立构建/cache。

## S3a0：最后呈现协议与存储（verified，已本地提交）

本片只实现第8项，不接Main/Editor，不放行S3a1录音或Native恢复采用。

- LastPresentation.Home / Mixed(ColdMixedPresentation)。Mixed存map:SplitSourceRef、
  nativeHint:MapNativeRestoreHint（仅NOTE）、layout、可选camera/nativePosition。
  native.path字段必需且只它可为JSON null；nativeHome必须false。
- wire为format=xnotes-last-presentation/version=1/state=home|mixed，64KiB/32层。
  采用独立严格递归下降解码，不交给宽松平台JSON解析；核完整语法和解码后
  重复key，拒未知字段/坏UTF8/类型/
  非有限与越界数值/future，不降级。Home写tombstone，不删除。
- observe返回Absent/Invalid/Ready与opaque实际head指纹。默认不覆盖Invalid；
  beginReset只接受同root的原Invalid observation，指纹改变即拒；无法可靠取得
  指纹的不可读/过限head拒任何写入。
- store接口observe/reserve/begin(ticket,value)/beginReset(ticket,expectedInvalid)。
  attempt.prepare在IO独占stage写完sync，字段先持有stage再恢复调用者；commit(current)
  在Main同步执行，文件门内重核固定canonical路径/无symlink、完整实际head SHA、
  ticket epoch及current，再领取许可checked原子替换。reserve仅内存、不等IO门；
  已claim前缀完成，后发提交不得被旧任务反盖。written在成功替换立即登记。
  close用NonCancellable IO只清自己stage，外层holder覆盖IO→Main取消。
- 文件限定filesDir/last-presentation.json，拒非regular/越界；file seam仅
  openRead/replace/deleteIfExists，路径/预算/指纹/代次始终实际执行。
- 先RED：协议所有严格边界、null Hint、Home/Absent/Invalid、取消/失败保旧文件、
  同canonical多实例代次/accepted前缀、旧reset坏A不能盖好B、同mtime/size不同SHA。
  不改recent/.xsplit/原件。相关编译和独立终审后本地提交。

## S3a0 实现与验收

- DTO/codec本地提交 `39812473`，Store/Attempt及存储回归提交 `7dc75587`。
  4个生产类均已实现，不再是接口占位；不接Main/Editor/recent，不授原件权限。
- Codec独立完整语法解析，64KiB、32层，数值token上限128，BigDecimal精确整数
  与范围校验；拒所有对象解码后重复key、非ASCII Unicode escape位、坏UTF8
  和未配对surrogate。编码仍复用平台JSONObject与既有视图编码，nullable path明确。
- Store按canonical root共享epoch／claim与提交门，reserve不等IO；最终head和stage
  指纹/身份读完后才核current，再CAS核epoch。stage持FileChannel锚定原inode，
  同字节foreign替换也不发布/删除。普通replace成功立即记written；抛错时仅以
  stage消失且actual target同原identity/SHA核实成功。默认保Invalid、显式reset
  必须同root同原指纹，未知／不可读不能用reset覆盖。
- Attempt在IO返回前持candidate，close早到由晚candidate自清，NonCancellable
  只清自己stage；发布后取消／清理失败不撤成品，不保Editor或宿主闭包。
- 原始RED34项25fail；独审补强2项1fail（不可读head的fail-closed占位已过），
  均仅说明占位尚未实现，不冒称旧Main缺陷。新源码grammar RED1/1实证非法
  fullwidth Unicode escape曾变Home，修严格ASCII；Store边界RED2/2实证成功
  替换后读失败误报Failed、final读期间current变化仍写，分别修成功事实/末核顺序。
- 最终GREEN终态0：Codec15＋Store24＝39唯一项，0失败／错误／跳过；1300项
  app/src KT/Java/XML与5构建配置的完整库存、前后与当前SHA一致。独立源码终审、
  raw XML逐方法集合与输入/runner/init审核PASS，3项Required已闭合。
- grammar/Store边界两轮主／unit／Android Kotlin编译实际执行；最后GREEN主代码
  compile与测试实际执行，unit/Android compile为UP-TO-DATE。Android新增9方法
  仅源码编译，从未运行仪器；Android私有FS、SAF、重建／Surface／GPU／设备未验。
- 原始证据在tmp/cold-marker-{red,boundary-red,grammar-red,store-boundary-red,green}
  对应log/results/inputs/summary，汇总tmp/cold-marker-verified-summary.json。
  当前无在途构建。未打包、安装、推送或发布。完整冷启动恢复仍待S3a1/S3b。

## 同步最新本地主线

用户进一步确认本地master已更新后，同步 `9cecd51b`。代码合并无冲突，保留
双Home刷新、记忆适宽视图、移除永久recent收集器及新版用户README/简中资源。
两处文档冲突合并双方记录；主分支不修改、不回推本开发分支。

组件7个生产/测试文件及其DTO依赖接口未变，既有39项证据按模块边界复用。
本次只针对主线改动验证3套14项全过，1302项输入前后SHA同；主程序、unit和
Android测试源码实际编译通过，平台未运行。证据tmp/local-master-sync-*。
后续恢复采用必须保留saved.fitWidth、双Home通知与有限写任务屏障，不恢复
旧常驻收集器。恢复只读准备、FIFO与Native协调已有边界优先复用，只补实际
流程所需的Absent许可、完整资源转交和来源保护，不建立额外全局架构作为前置。

## S3a1：可采用的受保护 NOTE 工作副本（verified，已本地提交）

本片为实际冷恢复必需依赖：把 legacy NOTE 恢复分成只读准备／末读复核／
同步采用，接现有 restoreSession 和 mixed 配置恢复；补原件写保护与另存交互。
独立 cold marker 纯协议／私有存储已经由S3a0完成，不重复建立。此片不宣称已经实现完整混合
冷启动；S3b 接 Map 冷准备、Main bootstrap 与所有呈现切换的 durable marker。
不能用仅显式 SaveSplit 后恢复来替代普通混合布局恢复。

1. 新 staged NOTE 入口返回 Absent／Cleared／Invalid／Mismatched／
   ProtectedRecovery／Ready，不能混成 null。先读 NativeWorkingSession，只有
   实际 Absent 才检查 legacy；Cleared 不复活旧槽，Active/source/handoff 与
   坏/未来资料交既有只读恢复路径，不启用 Native producer／claim 半套权限。
2. 目标物理槽先通过实际 LegacySessionQueue owner 的 FIFO 固定原始 bundle
   与有界严格 metadata（64 KiB、UTF-8、完整类型／数值／重复键／层数限制）。
   准备期间不改 sessionLoaded/slotAdopted/model。解码、输入等待和 UI 不持FIFO。
   原始 body UUID/kind、metadata 路径与可选 Hint 核对；Hint 允许未保存 Note 的
   path=null。缺件、未知字段/版本、坏类型、可检测错配保持资料并拒采用。
   legacy 无原子 body+metadata 绑定，同 UUID/path 的旧 meta 无法证明 clean。
3. Root 提供独占解码资产 holder：严格 complete NOTE 读取，保页面契约和原
   PDF SHA，独立目录存图片／音频／模板，PDF 用受限 admission 与租约；不读
   原件 URI，也不生成 EditSource。异常/取消/错Hint只清本次目录/raw/lease，
   不删除受管 PDF 或其他请求文件。采用后图片等沿现全局资产寿命，不即时删
   可能被复制项共用的文件；PDF lease 转给实际模型，退休后等已接受编码再释。
   不采用 PreparedRecoveryPreview.note 后直接 close 来误删活模型资产。
   录音恢复固定actual model/owner/adoption/key，每次延迟和duration/peaks/remux
   回调核同票；失效不写新模型。成功放置前保sidecar/原始音频可重试，不能先
   消费后异步丢弃；同记录独占恢复，取消只清本次副本。挂起duration后换文档、
   关闭或Canvas，旧结果零采用且材料不丢。仅延后sidecar到place成功仍不足：
   成功放置不等于正文已持久。后片须有durable Applying/Consumed交付状态，
   以实际正文rid与登记音频SHA对账；无法精确确认时保材料并显式处理，不能
   自动重放已经删除/撤销的录音。需要文档级receipt才能无提示跨所有崩溃点
   exactly-once；本片不作该承诺，录音方案仍待独审。
4. cap.current 绑定目标实例、实际 owner、adoption sequence、model/slot状态和
   请求票；verifySession 在最后 FIFO 重核 Native 状态仍 Absent、body/meta
   指纹与 owner，返回只活于本操作的最终票。随后可跨 Map 最后 IO，以同 Main
   栈 commit；票绑定LegacySessionQueue接受代次与Native head/intent epoch，后续
   同owner命令或native预约/发布/clear立即失效，Main核所有代次，不只Owner.isCurrent。
   Map末IO挂起时插入新命令或Native Cleared/Active/坏head须零采用并保资料。
   outer holder 必须处理 FIFO/IO 结果在取消后才产出
   的清理；采用成功后 close 不撤资产或模型。不把描述/tuple当保存凭据。
5. 所有实际从 legacy NOTE cache 采用的模型均装 sticky UnverifiedSessionOrigin，
   即使旧 dirty=false 也不能普通关闭静默清除。它跟实际 model，不因隐藏Map、
   配置重建、换路径或后读原件 SHA 自动消失。旧保护随实际model退休；新model
   按本次实际采用来源重新装权限，同UUID不继承原件权限。暂停/自动/显式
   同目标保存与 flush 拒原件写入；不绑定 autosaveUri，不伪造 SourceObservation。
   本地 Session 仍可保存恢复后的新编辑；再次启动走同保护，不能回旧弱路径。
   legacy body 本身不是原件的 verified baseline；后续新 Native producer 的完整
   接线仍是独立必需项，不能借本片宣称普通 cold 原位写权限已完成。
6. Main 保存和关闭保护识别该状态：保存导向已有 requestSaveAs/NativeCreation
   空目标独立副本，保原URI、UUID、资产与恢复资料；取消／失败不解除保护。
   只有实际换模型或独立副本成功采用才解除该模型保护，晚到 C1 继续 dirty。
   宿主/选择器仍绑定原模型和请求；不用显示内部UUID，不新增全局监听。
   启动旧 Native split 不得用旧 workspace 的另一UUID路径盖掉已恢复的保护
   模型；未知或不匹配布局保当前模型/资料，不静默补读其他原件替换它。
7. NativeWorkingSession 非 Absent 的槽不授 legacy capture/clear 权，错误保原
   head/T/旧文件，不通过新模型的 legacy 写绕过它。只读恢复/独立保留继续可用；
   NOTE写+清Canvas、Canvas写+清NOTE、Closed双清三分支都经共同final gate：
   编码/metadata stage在门外，锁内仅核实际Absent并有界发布/删除，门与Native
   head commit/clear及intent预约共享退票机制，拒check→write竞态。ProtectedRecovery
   返回独立结果，不标正文成功、不自动重试风暴；受保护物理槽拒新无路径model
   采用，保现状并提示既有只读恢复/独立保留，不产生无恢复副本的编辑草稿。
   具体API/锁序仍待独审，未准实现。
   本片不新增 Native 接管、future clear 或 producer。Canvas 不进入混合，本片
   不更改 Absent 下既有单独 Canvas 模型/输入路径；NOTE 资产清理不能波及它。
8. B 建立 LastPresentation 的独立协议/私有 prepared writer，仅存纯描述：
   Home tombstone 与 Mixed（Map文件 UUID/URI、Native NOTE Hint允许无path、
   layout、普通camera、可选阅读位置），Absent/Invalid 明确分开；坏/未来不
   自动覆盖，显式 reset 独立表达。文件不是 .xsplit/recent，不存 SourceVersion
   作为缓存写凭据、不伪造 URI。新文件严格预算与类型，受限 canonical 路径、
   独占stage/sync、Main最终同步 checked replace，旧代次/取消只清自己stage。
   S3a 不接 Main marker 消费，因此不会用 Home/Invalid 误走旧 Native bootstrap。

验收先 RED 后实现：真实 slot/FIFO/cache/Editor，含 PDF／图片／音频／模板
在错Hint、迟到、取消及 Map 准备失败时清自己的资源；采用后资源保持，原
受管 PDF 不删。body/meta 缺件/坏字段/重复/null/同UUID旧dirty=false；Native
Absent/Cleared/Active/未来不降级；末指纹/owner变化拒；cold保护缓存的新编辑
能写Session但不写原件，普通Save/关闭导独立副本，失败/取消保资料、成功新
身份与C1保留；二次冷开/配置/去掉Map后保护不丢；旧split不得覆盖保护模型。
marker协议测无path Native、Home/Absent/Invalid/未来/严格UTF8/预算、代次、
取消、失败保旧文件与未知文件。相关 JVM、Android 测试源码编译，保存 raw
XML/日志/前后 SHA，独立复核后分组本地提交；未运行的仪器与设备如实记录。

## S3a1 已放行的最小实现边界

阶段独审已确认复用 NativeRootState→WorkingSessionCache.state，无需新增全局G。
Cache签发可消费Absent观察：canonical协调器、generation、无可发布latest且实际
NOFOLLOW head不存在；Native intent/claim/完成、预约及受控legacy发布推进现有代次。
legacy先在门外编码/同步独占候选，最终只在原RootState→Cache.state锁序核Absent
并按固定步骤发布/清除。失败保已发布前缀与恢复资料，不逆向回滚或清对侧后续项。
exact退休只清本票，成功Native出版仍保原isCurrent/proof语义。

Queue为每个accepted操作和attach fence编号；恢复最后FIFO实读实际body+metadata
SHA，等整项job（含结构化子任务）settled，再取得绑定原命令的票。Main仅在
Queue.root→NativeRootState→Cache.state顺序消费，核仍是最新accepted命令与实际
Absent，随后同一Main栈采用；不在锁内调用宿主/输入/资源回调。后发同owner命令
同样退旧票，不只检测owner是否更换。

NOTE strict metadata精确要求dirty/zoom/scrollX/scrollY/zoomLocked五字段，path/name
与12项平铺视图override可省略；严格类型/重复key/UTF8/有限数值/预算，不用旧
JSONObject的强制转换。恢复专用readComplete以局部参数禁旧writer加载简化，原
采样/压力/时间前缀保留；不改全局笔迹开关。独占解码目录和受管PDF租约在actual
model采用后转交，取消只清自己。旧dirty=false和记忆路径始终不授原件写权。

所有legacy NOTE恢复装actual-model sticky保护，正常保存和最后视图关闭导独立
另存或明确放弃；成功新身份采用才清保护，取消/失败保资料，late C1仍dirty。
关闭同一模型的另一共享视图只退视图，等待双方编码前缀且保实际owner与保护。
旧split tuple不能将已恢复模型改为另一UUID/Missing。失败/错Hint恢复不提前结算
sessionLoaded，同一Editor可重试。三类working capture与无路径新模型均走实际
物理槽Absent保护，不开启半套Native producer。

录音固定实际model/owner/adoption/key/目录，所有延迟、duration、peaks、remux和
Discard Undo核同目标。raw留在recordings，Applying原子登记后才放置；actual已
发布正文的唯一rid+已登记AAC/M4A SHA才可Committed并清原材料，place/dirty=false/
元数据刷新均不构成回执。冷读证据来自本次实际不可变包、末核并成功采用，不能
由新ownerToken或内存pill伪造。Applying重启不盲目重放Undo后的录音；legacy、
orphan、冲突和坏资料保留且可显式恢复/独立加入/导出/放弃。独立另存通过本次
实际candidate/version和同模型新UUID采用的窄mapping消费，不用后续新UUID猜旧记录。
Discard Undo窗口排除候选且删除前核新引用，原录音临时文件名独占创建。
没有待交付记录时不扫描整个正文来生成音频证据。

## S3a1 实施与验证结果

- 首批真实Editor旧行为RED：4项3失败，Native Cleared、未知head及dirty字符串均
  错误采用；同目标保存用例已有其他preflight保护，原样green，不冒称其为RED。
  1303输入前后同SHA，证据tmp/cold-note-behavior-red-authorized-*。
- 统一接口/行为RED：7套22项18失败，1316完整输入同SHA。含旧writer/clear在
  Native恢复旁错误写清及旧采样简化真实缺陷，也含新API scaffold尚未实现；不把
  所有失败称旧UI回归。主/unit/Android Kotlin实际源码编译已通过，仪器未运行。
  证据tmp/cold-adoption-red-*。
- 实际NOTE缓存恢复→编辑→本地保存→独立另存新身份已经接通；取消/失败保保护与
  原文件，late C1保dirty；同一Editor重试、共享两向关闭及旧split保护均验证。
  录音覆盖恢复→SaveAs失败保原材料→重试成功新UUID→实际包音频SHA回执清理，
  stale duration/peaks/remux/Undo、孤立资料与导出界面销毁均有实际主机用例。
- 统一GREEN首轮39套414唯一项，3失败全部来自新fixture写成不支持的
  assets/image.png，严格complete decoder正确拒绝。仅改该fixture两处为既有
  assets/image-000.png，所有断言不变；整套PreparedProtectedNoteTest复验5/0。
  用新整5替换旧整5，最终仍39套414唯一项，0失败/错误/跳过，不重复相加。
- 1326项完整输入前后同SHA；green→recheck仅上述fixture变化，最终库存与当前SHA
  一致。主程序/unit/Android Kotlin首轮均实际编译成功；复验unit实际编译、主与
  Android为UP-TO-DATE。新增Android metadata4方法仅源码编译，从未执行仪器。
- NOTE、平台、音频及导出生命周期delta源码独审PASS，无未闭合Required；独立
  raw XML/完整库存/当前SHA/runner/init/命令/三套15资源key与双中文正文审核PASS。
  LastPresentation原7文件SHA未变，既有39项证据复用、不计入此次414。
- 原始证据tmp/cold-adoption-green-*、cold-adoption-fixture-recheck-*；汇总
  tmp/cold-adoption-verified-summary.json，正式摘要见
  evidence/mixed-protected-recovery-validation-2026-11-10.json。全部作业终态。
  未打包、安装、推送或发布；真实Android/SAF/音频codec/杀进程/Surface/GPU与
  MovinkPad Pro14 Android15 ProPen3未验。完整mixed冷启动仍待S3b，不称整体完成。

本地代码提交：平台72469b44；音频日志6f849087、运行时/界面6b7a3cb4；
严格准备与资产aef14975；实际Editor/Main接线5e3a322b。阶段文档另行提交。

## S3b：原计划与验收约束（实现结果见后续记录）

- Map冷准备须允许自身有效恢复日志，绑定实际加载的 raw/journal 指纹和UUID，
  不把后来另一份文件或新journal当旧actor基线；Native与Map都就绪才同步采用。
- Main先 retained VM，再 cold marker，再决定 legacy bootstrap。Home、Invalid、
  future不当Absent，不调用旧restoreSplitWorkspace/savedMapUri重活旧布局；
  保恢复资料并提供明确重试／查看／重置路径。
- 普通pair、slotMap、splitMap、Saved、source NOTE替换、Map子图push/pop、
  Canvas/Original返回均自动 checkpoint；不能只接SaveSplit。Native新建/SaveAs
  身份改变也退休旧票。输入/IME期间保普通camera，不为checkpoint提交预览。
- 离开/关闭/Home/停车/换workspace在最后实际采用前，先有checked durable
  tombstone并最终同栈变化；未成功打开保原workspace与其有效恢复安排。异步
  gate收敛HomeView赋值，避免Ctrl+O/设置读旧状态。旧camera/旧host不能把
  tombstone写回Mixed；配置解绑不写离开，永久退出也保最后完整显示。
- pause 等实际接受的工作副本写入成功，再登记最新纯描述。Native未来接管／
  producer完整迁移仍按原契约做，不能用legacy head旁路原件保存授权。

完整14组、Map+Map/同图双视图、网络/原件库mixed往返等继续必需。

## S3b：实际自动启动与全部呈现切换（in_progress）

本阶段独立计划审核已通过。严格 Home 安全首页与 MapOnly（可含 Map＋Home
槽布局）分开；v2 增加 Native/Shared/Canvas typed 描述及显式 fitWidth，继续
严格读 v1，未来版本和坏资料保持 Invalid。Map 的首次实际读取凭据与后来
本机正文持久化凭据分开，期望永久 UUID 在整理日志前核对；末验复用同一
actor/lease。Canvas 新准备入口只在实际 Native Absent 后读取旧槽，保持
raw 字节、严格 metadata、原始 UUID 和独占资产寿命，不授原件写权限。

当前正在完成 Editor 的实际本地 B 成功票、Canvas 恢复后独立另存保护、双
Native 全许可后统一采用，以及 Main 普通布局自动记录、冷启动、离开门和
停车资料保留。不能把仅显式 SaveSplit 路径当作此片验收。

首个真实行为 RED 为 `cold-bootstrap-protocol-red`：18 唯一项均失败。此前
两次仅编译失败、0 测试，不算行为 RED。最近组件运行
`cold-bootstrap-components` 终态 exit 1，100 唯一项中 97 通过、3 失败，
1346 完整源码／资源／构建输入前后 SHA 一致。Map 20 与 LastPresentation
47 全过；Canvas 15 中 1 项新夹具使用 speedStrength=0，writer 未写时间值，
已仅改夹具为非零，待重验；Main 两项实际新流程因 working checkpoint 尚未
实现而超时。主程序、unit 与 Android 测试源码实际编译通过。此结果不构成
S3b verified；后续端到端验证及独立审核完成后再更新验收与本地提交。

Android 新旧严格 JSON 合同已接仪器 runner，新增 V2 null path、显式 null
拒绝与 shared 单 owner 合同仍仅待源码验证。未运行 Android 仪器、系统
DocumentsUI、真实进程终止、Surface/GPU 或目标 MovinkPad 验收。没有打包、
安装、推送或发布。本地 master 仍为 `9cecd51b`，已包含在开发分支中。

### S3b Map 准备组件已验证，宿主集成仍进行中

MapColdAccess、MapStore、MapSession、MapDocuments 及其三套新用例通过
限定组件验收：实际 raw XML 中 20 唯一用例全过，7 个源码／测试文件的
当前 SHA 与该次固定输入一致；限定源码独审无 Required。原 components
整轮仍是 100 项／3 失败，不将它改记为全绿。独立证据见
[evidence/map-cold-proof-component-2026-11-10.json](evidence/map-cold-proof-component-2026-11-10.json)。
本片允许先保存 Map 组件进度，不代表 Main 自动恢复或完整 S3b 已完成。

## S3b 本批集成完成并已本地合并

本批普通自动记录／冷恢复、typed Native／Shared／Canvas、真实本机正文成功票、
Canvas独立另存与实际UI保存／放弃路径、最后采用门、Map journal-only停车及旧占位
离开保护已接入。源码Required已闭合。最终507套4750唯一主机用例全绿，主／unit／
Android测试源码实际编译通过，1350项KT／Java／XML与Gradle配置SHA前后及当前同。
详细修复、验证范围和本次合并事实见[本轮记录](mind-map-cold-progress-merge-2026-11-10.md)，
后续原14组未完成项见[接续计划](mind-map-next-plan-2026-11-10.md)。
本阶段不将JVM丢全runtime后的重建当成Android实际杀进程、Surface或GPU证明；
更广入口组合、系统选择器／SAF、真实音频与MovinkPad验收继续保留。

本批已本地合并master `0c1682c7`，保留开发分支。源码／验证及剩余计划见上述本轮记录。
