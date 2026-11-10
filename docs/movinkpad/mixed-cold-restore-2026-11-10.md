# 混合分屏进程恢复（2026-11-10）

用户要求：继续完整思维导图流程，先同步主线修复并保留已完成改动，再接混合布局的进程恢复。
状态：已验证布局记录组件（JVM及源码编译）；完整启动恢复尚未接入，整体目标未完成。
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

## S3a1：可采用的受保护 NOTE 工作副本（待计划独审）

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

## S3a1 尚未放行的细化草案

独立只读检查给出的提交门草案：canonical session目录共享G，分别记录Native、
legacy mutation、owner与queue accepted/settled代次。最终恢复票绑定最后一次
已settled FIFO命令；Main在G内消费，核没有后发accepted命令、epoch不变、无
Native intent/预约，且实际head仍确认不存在（任何文件/目录/link/坏head均拒）。
Main不重hash大正文；证明要求所有私有legacy写清入口都封闭到G与FIFO。测试
直接绕过这些入口改body不能冒称被纯内存epoch发现，实际head末查另行加固。

legacy先stage，G内只做有限rename/delete；所有Native预约/intent/claim/completion
及实际head提交都需同门退票。建议锁序G→Queue.root、G→NativeRootState→
WorkingCache.state，禁止持旧锁再取G；Prepared→G不得被任何G路径反取。
回调、join、Deferred完成和资源释放均在门外。Native预约需显式退休，不得永久
阻塞legacy。这只是候选设计，尚未经后片fresh审查，不可直接当实现授权通过。

录音候选为固定RecordRecoveryTarget＋checked私有sidecar Pending→Applying→
Consumed事务。固定实际model/owner/adoption/keys/目录，durable Applying后才
放置，实际成功正文证明后才Consumed和清原音频。重启Applying只以实际正文
唯一rid＋登记音频SHA对账，不匹配/多匹配/冲突保原件并显式处理，不能自动
重放删除或undo后的录音。旧sidecar路径/槽key不能补当前UUID冒充历史已核。
M4A指纹需先持久登记，再替换item；所有异步结果核原目标与item归属。录音
具体接口、实际正文成功证明接线及杀进程边界仍需独审，S3a0不解决这些问题。

## S3b：完整冷恢复与呈现生命周期（尚未实现）

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
