# 思维导图与文件关系网络：当前恢复点

目标：按完整持续需求继续实现，active，overall complete=false。D2d2 verified 并提交 4373587d；Canvas 整文件来源已 verified 并本地提交 4f413d16；Canvas 区域来源 verified；分页区域来源 verified；当前原PDF独立原文 in_progress，整体仍 active。
工作树：/Users/kita/.codex/worktrees/mind-map-original-pdf/xnotes；分支 codex/mind-map-original-pdf；2026-11-10 用户明确要求在当前工作树基础上新建并继续，以 mind-map-region-continue 的实际 HEAD 579e5f54a25579ca8dfb5465f8a80e1a24c52272 创建。原树两份未提交的原PDF阶段计划逐字节校验后复制，原树保持原样。新树迁移清单 tmp/original-pdf-worktree-copy.json；新阶段构建与项目缓存分别独立为 /private/tmp/xnotes-mind-map-original-pdf-build、/private/tmp/xnotes-mind-map-original-pdf-cache。
授权：代码、相关编译／测试、文档、本地提交。用户于 2026-11-10 明确允许从原工作树新建工作树继续，替代之前“复用原树”限制。未打包 APK、安装、推送、合并或发布。
需求：docs/movinkpad/mind-map-requirements-current.md，原14组范围不缩减；页面级UUID、旧笔迹/PDF原字节、保存失败保留已写版本等规则不变。

## 当前阶段：原PDF独立原文（in_progress）

上一goal turn为progress，分页区域已本地提交579e5f54／281有效测试。当前HEAD579e5f54 clean起步，原完整14组不缩减。独立身份关键决策已审：新catalog随机doc/pageUUID，SHA仅资源指纹；与resource pin同SQLite事务，tombstone／显式恢复／mutation-start epoch防ABA。完整规格docs/movinkpad/original-pdf-sources-2026-11-10.md已独审，两项Required已补（实际pageInfo/整页同模式选择），开始实施真实模式选择／保留／加图／预览／独立reader／返回及原件库管理。旧已知构建终态，无重启。

## 既有阶段：分页固定区域来源（verified）

分页本地提交：`69ccde43`（完整读取／固定预览）、`d5c8fcc2`（实际入口／请求／定位）、`40ce03b5`（主机与Android菜单测试源码）。

d2381a33 clean起步；上轮为progress（Canvas区域4本地提交／251有效测试）。当前actual选区／截图→Doc/Page/localRect→PAGE＋永久页ID→已有／新图及概念→保存重开／PNG→来源定位与同图返回已接。PDF明确为当前批注笔记上下文，原文独立mode仍下一片，完整原#7不缩减。选区不信旧index，截图UP固定页，跨页/gap拒；外发DTO用doc/page移除epoch＋选区代次／当前截图身份退休，clear/reselect/delete-undo旧菜单不复活，已签request不随dismiss取消。Note actual item/pending和明确XML失败可观察，默认reader不变；region prepare在known partial flush前拒，region source首读/晚B重读与preview严格读取。固定frame／EMPTY／历史PNG／actual host定位已回归。

独立计划／实现／证据复核d2d2_plan_review完成。50814夹具API编译错未执行测试已修；67148临时旧行为3/3真红后fixed立刻恢复；54454终态281/2（279绿＋两新夹具），Android源码compile过，1121输入SHA全同。93555按speed-time契约／分页relayout修夹具后仅SPEED流程绿；locator最后仅±0对象相等，改三轴delta0数值比较，30768单项绿。保原有效279＋两项逐case替换，281/0失败错误跳过，1121最终SHA全同；之后无app/src变。证据tmp/paged-region-verified-summary.json及final/recheck/locator-recheck日志与inputs。所有作业已终态，作者冻结，root统一本地提交；运行状态tmp/paged-region-runtime.json。

下一实际动作：原PDF原文独立内容模式，先核可复用的原件指纹／原页与笔记永久页映射、当前来源／preview／定位契约，独审typed模型和完整创建→保存→预览→打开／返回后实现。不复活已废includeAnnotations、不把hash当UUID、不将某份批注笔记冒充共享原件；不引入普通保存权限。其余完整14组、范围编辑／失效恢复、Map来源／双pane、Canvas反向引用、原件定位与重授权／移动／回收站、完整引用备份、主预览／目录缩略、同级插入／边缘平移等保留。

真实Android／Compose／SAF／配置重建杀进程／MovinkPad Android15 ProPen3／掌触／Surface／GPU／性能未运行。无APK／安装／推送／合并／发布，overall complete=false。

## 既有阶段：Canvas 固定区域来源（verified）

本地提交：`60dfacfa`（格式／预览／完整读取）、`c711b11b`（实际入口／保存／副本采用／定位返回）、`8c787750`（主机与Android事件测试源码）。

实际选择／截图区域→新 CANVAS_REGION／v5→共用已有／新图与概念导入→保存重开与正式预览PNG→真实单文档Canvas定位／同图返回已接。完整实际采用／publication才给来源观察；显式原位按expected版本，known incomplete拒绝。无路径／未知基线用独立typed选择器→空目标新UUID候选→成功后同model/history安全采用并按原范围签新request，late C1保dirty；retained只续同candidate，不重编码。FILE＋region仍禁止，不恢复对象编号。独审两Required（Canvas fallback误入NOTE另存、已入host pending定位卸载重放）及三locale文字已修并复核。

32941终态208/2夹具失败，68191三个夹具复验通过且旧Locator新增精确case真红；固定Locator已恢复。78922终态251/1（唯一新增轮询放Main的夹具超时），Android源码compile过，1112输入SHA全同。仅把轮询移回worker、保全部PNG持久化断言，81852单case通过；逐case合并251全绿、0失败/错误/跳过，1112最终输入SHA全同，独立证据复核通过。日志／清单／逐case在tmp/canvas-region-{first,locator-red,final,recheck,verified}-summary.json及同名log/inputs；最后两轮之间只该unit夹具变，生产／Android证据有效不重复跑。

无运行构建／代理写入。独审d2d2_plan_review及交叉检查relation_evidence/network闭合，所有作者冻结。构建 /private/tmp/xnotes-mind-map-region-continue-build，project cache /private/tmp/xnotes-mind-map-region-continue-cache；当前完整HEAD／作业见tmp/mind-map-region-runtime.json，旧root45迁移文件最终SHA核对仍相同。详见canvas-region-sources-2026-11-10.md。

下一实际流程：分页选择／套索与截图区域→PAGE＋region→已有／新图及概念→保存重开／预览／定位返回；先精确核世界坐标、永久页ID、PDF原文／含批注语义并独审，不扩成对象跟随或依赖native4全迁移。可选同图出现／失效检查、Map来源打开／真正双窗格、权限／身份／移动／回收站精确恢复、完整引用备份、主要预览／页目录缩略、同级插入／边缘平移等完整原范围保留。整体active/complete=false，真实Android/SAF/Compose重组/重建与杀进程/MovinkPad Android15 ProPen3/掌触/Surface/GPU/性能未运行，不打包/安装/推送/合并/发布。

## 既有阶段 D2d2：显式文件关联的实际探索与编辑流程

输入：D2d1 已提交的 fileCardId 卡片和 MapRelation、同次 MapBacklinkLibrary 实际观察及 live overlay。
输出：独立手工关联证据→局部/多文件/全库网络与来源侧用户关联组→打开具体原画布关系→编辑/保存/返回。不得伪造 MapSource、不从概念多来源推依赖。
计划独立审阅已完成（d2d2_plan_review）：采纳同次扫描bundle、独立关系票与双端寿命、双折叠分支临时展开、跨图重复线聚合、端点过滤与解释上下文、有效证据展开传播、编辑返回后拒绝旧disk投影。其余旧保存迁移无需重审。
实现分工：relation_evidence（扫描/模型）、relation_navigation（actor票/聚焦/导航）、relation_network（网络/过滤/展开/场景）；主代理接来源侧与Main/UI、验证和本地提交。均同树编辑且文件归属分离，原作者已停止。
验证：45869编译通过、unit夹具编译错；72986为153项152过1夹具失败；93077为108项107过1真实观察缺陷（conflict不在snapshotFlow上游）已修；4017终态0，81受影响定向通过。按逐suite替换合并159项，0失败/错误/跳过，1063最终输入SHA全同；Android源码编译通过。全部日志/逐case/input SHA见tmp/file-relations-{related,recheck,conflict,verified}-summary.json及同名日志。两个折叠分支聚焦和真实Host的来源/网络往返保存删除、原native bytes/零写均实际验证。独立阶段审核与三必修+新增冲突观察复核通过。构建输出 /private/tmp/xnotes-mind-map-continue-build；project cache /private/tmp/xnotes-mind-map-continue-cache。
未运行：真实 Android 仪器/SAF、重建/杀进程、MovinkPad Pro 14/Android 15/Pro Pen 3、Surface/GPU/性能。
上一goal turn为progress：D2d2和Canvas整文件来源两阶段已实现/验证/本地提交，当前新树HEAD4f413d16且开始核验时clean；全部已知构建handle终态，无重启作业。
下一实际阶段：Canvas区域来源创建→保存重开→区域临时定位/返回。先只读核范围表示、持久化兼容、预览裁切和现Canvas overlay/viewport接口，写一个完整可操作小阶段，不回头以整套native4迁移为前置。Canvas整文件阶段只读检查、计划与实现复核已完成。现NOTE-only阻点集中NativeReferenceTarget/NotebookStorage参考读核、NativeReferenceRequest/Editor参考准备与采用、保活Canvas停车，以及Main成功提示；底层PreparedNativeOpen/openNative已支持Canvas。
本片先交Canvas整文件往返（现来源选择可添加），不放宽FILE+region。实际kind由live模型/身份登记并经实际源验证，不按文件扩展名猜；Canvas禁止pageId/region。活Canvas保同模型/历史/修改，其他owner沿现保护，冷打开按UUID/类型/唯一位置/版本核验。采用与隐藏图同Main不挂起；单文档不恢复Canvas分屏。独审补入实际Main消费者边界：Canvas返回仅本次呈现跳过persistNativeForMapPresentation，普通NOTE/正常打开和网络既有flush策略不改变；测试调用真实helper验证dirty/history保留且零写。
实现：relation_evidence为storage读核，relation_navigation为Editor/Request+native Host，relation_network为Map Canvas Host/Android场景；root为Navigator/Main返回策略与协调验证。
Canvas首编50322终态1：production过，新unit/Android夹具误直接访问private canvasAutosaveUri导致测试编译失败，root已改反射注入（不扩生产接口）。独审实际复现旧CanvasCodec会丢unknown/missing-asset对象而仍授editSource；现仅reference Canvas首读/最终重读启用严格逐项物化与采用前完整proof，默认reader不变；raw首读/保存期间坏B、顺序/同count替换回归已补并静态独审通过。作者已冻结，1066输入SHA固定；69241终态0：187相关项0失败/错误/跳过，Android源码compile过，1066输入SHA全同，独立证据复核通过；日志tmp/canvas-source-recheck.log，逐case证据tmp/canvas-source-verified-summary.json。无APK/安装/推送/合并/发布。验收覆盖冷/活/保存后重读、错ID/类型/重复位置/迟到、Canvas笔画停车与返回、源字节零写及Android源码编译；真实平台仍未运行。
其余原需求持续保留，见需求清单和交接文件，不将本阶段或交接视为全目标完成。

## 最新恢复点：D2c 全库与多文件网络（verified，已本地提交）

上一goal turn仅状态问答，no progress；本轮直接实现已审核D2c完整路径，不重做全仓审计或未变全量。基线16c98dc8 clean。首页/主菜单（含Rail）→无native文档进入全库→真实多中心选择→保filter/camera→具体来源进图/沿原来源往返→同网络；Local侧栏仍保留。

Query无localFocus的内部key不授native/File身份；FILES同次read先roots/owned/expanded匹配再计direct/deeper预算；LIBRARY全量有界、孤立登记只占余量，Registry只SELECT。新模式实际graph核native kind/永久页契约（含tombstone），同canonical位置已读另一UUID时旧登记也IDENTITY/exclude choices，不改登记/原件。Scope改变退旧票/草稿且保filter/camera；Library隐藏逐层按钮，原有源/owner导航复用。规格docs/movinkpad/file-network-overview-2026-11-09.md。

验证：23405终态1，生产compile完成，Android新增常量拼写错误导致unit task未执行；只修API位置。77467终态1：89相关88绿、Android源码compile成功；唯一旧wait .single()抢读合法暂空live report，Source/Reviewer均核夹具，Root仅await改singleOrNull且后续精确断言不变。75007终态0，仅该1case重跑通过；不重跑未变88。合并89项0fail/error/skip，tmp/network-overview-recheck.log/-summary.json、-wait-recheck.log、-verified-summary.json；77467前后1066源码hash均同，之后仅wait fixture变，final-inputs.json与单case后全部同SHA。新增15方法=Domain3/Data7/SceneView3/Host2。Android Main真实首页/Library/Cancel/多文件T-U/具体U/同scope与camera返回只compile，实际未运行。

review_d2_filters已核阶段差异、唯一Required同位置身份窄修与真实回归、单case夹具调整和89合并证据，Required全闭合可本地提交。作者全formal freeze/completed，无运行Gradle/exec作业；Root已本地提交a257f42c（数据/投影）和80d049c3（首页/界面/往返），文档检查点另存。无用户待答/外部阻塞。无APK/安装/推送/合并/发布。

下一真实流程：显式文件关联及将所选文件加入可保存关系画布，沿既有MapSession/多来源/普通线/保存，不先建另一套网络引擎或持久全库索引；然后可选同图出现/失效检查。原区域/Canvas引用与打开、真正Map双窗格、权限/身份/移动/回收站恢复、独立副本/备份、主要预览/页缩略、无中心/同级插入/边缘平移等完整范围保留，整体active而非只待真机。

只待平台/硬件的已有阶段单列：真实Android/SAF/DocumentsUI、配置重建和杀进程、MovinkPad Pro14/Android15/ProPen3/掌触/Surface/GPU/性能未运行，不以JVM或源码compile替代。

## 最新恢复点：D2b 可操作流程已保存，下一多中心／全库与手工关系画布

用户经侧对话明确纠偏（长期规则已AGENTS）：不要长期基础设施/反复审计，以真实用户流程交付，审核集中阶段边界/实质风险，未变有效证据不重复跑；原完整需求和权限不变。已快速确认D2a提交7932c2d5/36bcc561/9cb7e4b0实际完成且clean、10650成功日志，不重新全仓审计。ps受执行权限限制未据此重启旧作业；所有Root已知构建handle现均终态。

本turn为progress：直接完成选择文件→展开下一层→共享折叠/原位再展→下一层具体条目Open→同一网络返回。功能本地提交bc26a9db；随后docs检查点。阶段记录docs/movinkpad/file-network-expansion-2026-11-09.md，合同tmp/network-expansion-contract.txt。整体仍active，不把这一闭环当全产品或只差收尾。

实际代码：Library additive一次实际read/decode投影S+expanded sources/owner，direct/deeper独立最多5000轻量成员，深层LIMIT不抢光later S，最终仍250nodes/750edges/5000 members；旧single wrappers保留。NetworkExpansion BFS/fixed point只激活明确展开、保共享/止循环；Origin SOURCE是真实本层file成员，OWNER同graphUUID且target作anchor，不伪造S。path存前序last-known轻量rows，live membership ABA退旧，blocked既不能继续作桥也不能Open，不额外acquire所有前序图。Assembler按层/键whole concept；filter保必要完整path context。Scene保16384有界隐藏位置/占槽，仅淘汰最旧hidden，不改现存点/zoom/center/selection；Presentation input退休不fit，Screen控制变化SideEffect不晚到自废。Main/Nav/Native保存/Renderer未改。

验证15623终态1：80相关78绿，Android源码compile成功；2失败是新增Data fixture同图复用Source.id触旧validate，未进入待测行为。只改2fixture t/u的新entry ID（docUUID/target/断言保留），54046仅重跑这2终态0，production unchanged/compileDebugKotlin UP-TO-DATE。其余78和旧全仓不为安心重跑。合并80 case0fail的证据tmp/network-expansion-related.log/-related-summary.json、-fixture-recheck.log、-verified-summary.json。-verified-inputs.json是当前1062 Kotlin/Java/XML交接SHA，不声称初次前后冻结。新15方法=Pure6/Data5/SceneView3/Nav1，包含真实Navigator/Workspace不含S的H/U打开及原S网络返回。原AndroidMain场景扩T→H[T,U]→fold/reexpand→U第二永久页→H→原网络，只有源码compile，Android页面/窗口/GPU未运行。

review_d2_filters一次快速边界审核和本次差异复核已通过，无未闭合Required；唯一known-blocked prefix已最窄修并回归，未回头重审Native/存储。Source/Ui/Reviewer均completed且formal freeze，Root统一Gradle/提交，无运行作业、无用户待答/外部阻塞。未APK/安装/推送/合并/发布。

下一真实流程优先：指定多文件集合/全库网络入口→筛选/探索→解释/打开条目/返回；随后显式文件关联编辑与将所选文件加入可保存关系画布。沿当前源/owner模型、原Actor/Nav/文件身份使用，不再先建完整全库持久索引。阶段计划短，独审只新边界，作者可并行实施。

仍缺（不是只待验收）：集合多中心/全库、显式用户关联/可选同图出现、失效检查、加入关系画布；区域/Canvas引用与Canvas/导图来源打开、真正Map双窗格、来源权限/身份/移动/回收站修复、独立副本/完整备份、主要预览/目录缩略图、无中心/同级插入/边缘平移等原需求。只待平台/硬件的现有阶段另列：真实Android/SAF/DocumentsUI/配置重建和杀进程/MovinkPad Pro14 Android15 ProPen3/掌触/Surface/GPU/性能均未运行，不以JVM或源码compile代替。

## 当前恢复点：D2a 筛选 verified，下一 D2b 逐层展开／折叠

本goal turn为progress：以d393eb08 clean为基线，完整实现当前局部网络的来源UUID集合／last-known原生格式／文件夹条件，已本地提交9cb7e4b0（pure model/selector/readonly facts/Registry DAO与15测试）、36bcc561（Controller/UI/picker/新消费者测试）。仅文档检查点在后续提交；不标整体complete。正式规格docs/movinkpad/file-network-filters-2026-11-09.md，精确API tmp/network-d2-filter-api.txt。

最终10650终态0：368套3879项，fail/error/skip0；Android测试源码compile绿。tmp/network-d2-filters-final-full.log/-summary.json；1058 Kotlin/Java/XML冻结输入(-final-inputs.json)运行后逐项同SHA。新增25主机=Selector10/Reader5/Controller6/Picker3/Nav1。Android新增FileNetworkFilterEditingHostTest 1方法，原AndroidMainFileNetworkHostTest扩filter取消/采用/内部目录与具体T往返；均只compile未运行。全量后无app/src修改，仅docs/提交，当前无运行Gradle/exec。

实际语义：只对同一个真实source求AND条件，concept ANY纳入后保整组全部sources/belongs；owner-only不匹配，确为source才可匹配。filter前catalog来自完整未筛投影，不由250 scene反推；null与empty分开，未知显式partial。格式是registry上次识别NOTE(含PDF)/CANVAS/MAP，无folder时可靠类型不依赖available位置；folder另核当前metadata/实际ancestor，缺失/权限/重复/超限unknown。零Source正文/复制/PDF decode/Native写权；不改SourceStatus/正文undo/原生工作区，不自动fit。

Controller metadata跨IO仅immutable rows/facts，action+projection代次和actual live observation最终核；A→B→A永久退旧票，C1/cancel/close晚结果不覆盖；typed filter下own acquire产生LOADING仍保accepted请求。UI弹窗draft绑定原revision，独立picker runtime key/单槽retire防旧结果转绑；配置重建旧picker不领新callback，纯草稿有64Ki字符保存预算，过大未确认草稿重建关闭，已采用filter仍VM保留。owned目录UI纯URI构造，Reader直接recoveryRoot/File/canonical/listFiles，无mkdir/provider fallback；外部tree不persist新grant，metadata用appContext+真实CancellationSignal，迟到cursor关闭。Scope/error/match/context等为overlay，不resize原Canvas。读路径与位置/目录项/问题有界，DAO只SELECT。

fresh独审review_d2_filters已独立核计划/代码/真实红与XML/全部SHA，无Required未闭合。计划Required（内部root picker/provider会mkdir、owner-only混淆、单source AND）已修；实现Required格式/位置混用和UI名称query不可取消已修。34897是缺接口预期compile红；91156相关31/0+Androidcompile历史绿；19321临时还原旧候选一条noneavailable条件，Reader+Controller两新增case2/2真红(-behavior-red.log/-summary.json)，恢复修复后full绿。未削弱断言。不同UUID同名目录/预览辨识可后续增强，当前绝不按名合并。

所有作者正式生产/tests冻结，三位均completed。Root统一Gradle/提交；source_loop_audit Reader/DAO及5实际provider测试；cancel_event_tests UI/Picker/3 raw Registry与Android；review_d2_filters独审（新的fresh任务，不是旧review_network_plan）。未打包/安装/推送/合并/发布；真实Android/SAF/DocumentsUI/配置重建或杀进程/MovinkPad Pro14 Android15 ProPen3/掌触/Surface/GPU/性能未运行。无待用户回答/外部阻塞。

下一条立即 D2b：源和UI只读审计仍在tmp/network-d2-data-audit.txt及network-d2-ui-audit.txt，先按当前新Controller/Filter接口定可验收API并独审，再tests-first实施真实“选可见文件→展开一层→共享路径/循环→折叠→原位再展→本层具体source→返回”。采用一次scan多个frontier/source+owner origin，旧D1单focus wrapper和S/T严格入口保持；不能拼多个D1 Controller或伪造S source。每层真实local anchor，owner-origin不能造fake来源；前序last-known路径只是解释，不额外acquire所有图。可达fixed-point保另一条路径支持的共享节点；SceneBuilder当前retainAll会丢隐藏positions，D2b须有界保隐藏位置/占槽，不自动fit。filter变更、expand/collapse、source ABA均退旧动作；scope/duplicate/alias/native停车/falseflush/input票不放宽。

D2a只是当前局部条件，不是任意集合多中心/全库；后续D2c这些模式与显式用户关联、可选同图出现、失效检查、加入手工关系画布仍需实现。区域/Canvas来源入口/打开、真正Map双窗格、精确权限/身份/移动/回收站恢复、独立副本/完整备份、主要预览/目录缩略图、无中心/同级插入/边缘平移等全保留。保持goal active，不把代码/主机阶段当目标设备验收或整体完工。

## 当前阶段：D1 局部网络（verified：源码／主机与 Android 源码编译，整体未完成）

工作区在 ad4f4d9a 后完成真实 Sidebar → 局部网络 → 概念证据／具体 co-source T → 同一图 actor → 来源定位／返回图 → 原网络 S 的接线。基础层旧提交946664af／af66ffa5／d906024d仍保留。新本地提交33aabf5c（共享双成员访问与初始scope拒写保护）、698ce34c（纯数值相机／输入能力／原生Canvas）、96bfc049（实际Main入口／往返／生命周期与全部消费者测试）。本阶段记录docs/movinkpad/file-local-network-2026-11-09.md及随后文档提交。整体目标active，不标complete。

最终24659终态0：364套3854项，fail/error/skip全0；Android测试源码compile绿。tmp/file-network-integration-final-full.log／-summary.json／-inputs.json；1048个冻结app/src输入运行后全部相同。全量后仅加AndroidMainFileNetworkHostTest及Runner登记，16050单独Androidcompile终态0（tmp/file-network-main-android-source-compile.log），host/production未改；-main-android-inputs.json记录最新源码。新20主机方法=Core4/Presentation4/Workspace7/Nav3/MainRaw2，均被full覆盖。Android新增1个实际Main长场景仅源码编译，未运行；不宣称实际Main配置重建通过。

已接纯FileNetworkWorkspace/VM、composition Coordinator、新native停车、Sidebar入口/解释UI、原生readonly Canvas与单调input/layout票、Main图形前台raw gate、网络图false-flush、源T返回图保flag及返回网络。初始NativeContext仅入场，Query/Controller独立保中心S；VM不保旧Editor/View/Activity。Access单acquire且S/T原会员双proof，final合actual网络输入票/nativeproof；scope拒写保护actual canonical actor，取消pending debounce/旧release而不取消已接受IO。close/replacement/dispose清parking closure，普通backlink成功退旧return，show回network清graphReturn；普通hide保actor/脏图，未确认draft拒切换。

独立计划/接口/代码及证据审核review_network_plan已闭合，无剩余Required。实行为红23365（4/1，初次scope veto旧debounce写日志）已修且full覆盖；30201（27/26）唯一Nav NPE为夹具首Window layout尚未稳定即签票，更正为稳定尺寸后同一Main签票/启动，保原DOWN/UP退票断言，不改产品；59922相关30/0及Androidcompile绿后再加replacement被full覆盖。

UI复核修正：全部动态counts/partial/limit/busy/error为overlay不改Canvas几何；Open同步pin所呈现report/scene，controller继续真实LOADING不回退disk，busy期间禁止新open且明确提示旧图暂保；held时完全不build未展示报告以保positions；Cancel/失败读最新并清消失选择，不自动选中心。实际新DOWN和S/T会员寿命失效仍退操作。Android长场景用真实content URI／canonical锁制造stored own LOADING，覆盖pin/取消、两concept增量位置与fresh排序反例及T第二页→图→原网络S；T登记基于实际strict header。该场景已独审/编译，未执行。

所有作者与正式production/tests冻结，无运行Gradle/exec。Root统一Gradle/提交；source_loop_audit负责Core/3Nav host及Android实际Main场景，cancel_event_tests负责Canvas/Presentation/Main raw，review_network_plan只读独审；completed需followup_task。无待用户回答/外部阻塞。未打包/安装/推送/合并/发布。真实Android/SAF/IME/PDFium/配置重建和杀进程/MovinkPad Pro14 Android15 ProPen3/掌触/Surface/GPU/大图性能未运行。

下一条立即按原计划推进D后续：在已授权资料范围增加文件夹/类型/指定集合筛选与逐层展开，保持内容引用／显式用户关联／可选同图出现分离；全库、失效检查、加入手工关系画布继续。先定可验收小片/独立审核，再真正实现，不重做已绿D1或native4全量迁移。区域/Canvas来源入口和打开、真正Map双窗格、精确权限/身份/移动/回收站恢复、独立副本/完整备份、主要预览/目录缩略图、无中心/同级插入/边缘平移等原需求全部保留。用户问“完工了吗”已明确答未全部完成，不视为暂停。

## 最新阶段：来源侧栏反向引用（verified）

基线 c9b72ab1。本阶段源码／主机测试／Android源码编译已verified，整体 active/complete=false。功能本地提交 c0519ebd（NativeContext5）、343789fd（Map focus6／readonlyStore5）、e1a5d122（Library7）、00cc4ea2（Controller9／Access）、013d0bdf（Navigator9）；Main／SidePanel／双语资源／Android两场景及本记录在随后的本地界面提交。未打包／安装／推送／合并／发布。

真实路径：笔记／批注PDF SidePanel“引用与关联”→按永久页Current/Other/FILE查询真实sources→图文件/节点/范围/备注/最后status/PNG→sameactor精确source focus／运行时展开→确认只删一source／一次history→实际save／失败只retry，Undo后保存显示SavedChanged不误报Removed。查询沿真实内部树／read grants／knownmaps／alllive actor，未登记图可发现；只读恢复winner不写baseline／日志；loading/live不回退disk，C1仅overlay；samegraph PNG合批、有界总量/partial。Unknown scope／Trash／新alias恢复拒写，Entry粘性保dirty跨last lease，不取消accepted save。

最终44355终态0：357套3812项，失败/错误/跳过全0，Android测试源码compile绿；tmp/map-backlinks-final-full.log及-summary.json。37 frozen源码/tests/resources摘要（tmp/map-backlinks-final-inputs.json）运行前后全同；后仅docs/提交。相关36871为41/40绿+Androidcompile，唯一Undo rawA==diskA却test期望write1，已紧为原bytes不变／writes0／仅初次拒写attempt1；最终full含修。38901真实40/3红：opaque root metadata缺失误scope、Undo Retry误Removed、双lease释放绕过scope；产品窄修与独审闭合。48549 37/8红为先行fixture问题，见正式stage doc，不能冒充产品红。无运行Gradle/exec。

41新增方法：NativeContext5、MapFocus6、readonlyStore5、Library7、Controller9、Navigator9。Android NativeBacklinksEditingHostTest2已登记，仅编译未运行；其Open验typed hook，实际Main parking／actor／focus由主机Nav覆盖，不claimAndroid完整Main路径。全部Required独审闭合。README／AGENTS／plan／todo／review及docs/movinkpad/mind-map-backlinks-2026-11-09.md已同步。

下一条立即执行：D 当前文件局部关系网络。source_loop_audit只读审计在 tmp/file-network-next-audit.txt：从真实同概念sources投影轻量File/Concept/成员证据，不能拿现仅指向中心文件的backlink rows推断邻居；文件按UUID去重，概念保canonical图位置+graph/nodeID；内容引用／显式手工文件关联／可选同图出现分开，多来源概念不推文件互引。复用既有MindMapView/Painter但必须显式只读导航模式，不塞MapSession造成自动保存；Main复用全部native停车与前台raw事件透明门。下一片先定精确规格/API与独审，再tests-first实现真实“侧栏→局部网络→解释路径→同actor具体图来源→返回网络”闭环；过滤／全库／展开／加入手工关系画布仍须持续完成，不以首片替代整个D。

Canvas侧栏/来源打开、region入口／范围调整、真正Map双pane、精确身份/权限/文件移动与回收站恢复、独立副本/完整备份、主画布primary大图/目录缩略图、无中心/同级插入/边缘平移等原未完范围全部保留。真实Android/SAF/PDFium/IME/配置重建/杀进程/MovinkPad Android15 ProPen3/掌触/Surface/GPU/大PDF性能未运行。

协作：Root统一Gradle／提交；source_loop_audit Source/Library全部freeze并完成D只读审计；cancel_event_tests Map/Controller/Access/tests全部freeze；review_input_plan独审收口。无待用户回答／外部阻塞。completed代理用followup_task唤醒，旧send_message不触发新turn。下轮先核git/最终提交与无活作业，再继续D，勿重做已绿来源／反向引用或以native4全量迁移作硬前置。

## 最新阶段：严格来源打开、定位与返回（verified）

基线ad1c012f干净。本片已verified（源码／主机与Android源码编译），整体active/complete=false。用户询问“完工了吗”已明确答整体未完；不视为暂停或缩小范围。本地功能提交：171128c6（native严格来源/actual attach定位/10用例）、70848824（Map来源票/相机与返回/15用例）、25546ccd（Main原guard/save终态/Return接线/2guard用例与2Android场景）。随后仅docs检查点，不需重复已绿测试。

最终17885终态0：351套3771项，失败/错误/跳过均0，Android源码编译绿；tmp/map-source-navigation-final-full.log与-summary.json。所有production/tests在full前冻结，之后仅文档/提交。相关5450终态0：44套395项及Androidcompile绿，tmp/map-source-navigation-related.log与-summary.json；最后fresh笔与save失败专门反馈由final全量覆盖。新增27方法：NativeReferenceHostTest10、MapSourceNavigationSessionTest7、MapSourceNavigatorHostTest8、NativeGuardHandoffHostTest2。新增AndroidMainMapSourceNavigationHostTest2已登记，仅编译未运行；既有Main重建fixture也加空profile保护。无运行Gradle/exec作业，三作者均completed/frozen。

已接真实路径：具体source row Open／单源REF双击→registry唯一UUID与永久PageID→保已开actual owner原槽/修改/history、其它owner走原Save/Discard/Cancel→全窗口单native→actual attach稳定preDraw后页/region与临时对比轮廓→Return先parking再同Entry恢复camera/选择/sourceId。文本/手绘主体双击仍编辑，多源不任意选first；来源备注IME缩屏不覆盖普通camera，失效source收尾解冻；普通ViewMap不消费旧return选择。Entry只存纯Map数据，不retain Native Editor/Activity。

Native stored首/晚/最终expected核验跳ensure，不迁移补ID、不按ordinal；live删页不借disk旧页复活；known registry多位置Duplicate，prepare前后物理model/原live身份scope与最终IO fresh stamps均核。strict source base沿原private actual observation，不拿preview/fresh C洗白RAM A，保已写B、C1与原PDF。identified embedded v1首次严格打开后保存保包装/PDF原bytes；secondary单文档persist/restore不复活closed primary。缺PDF只typed unavailable，不建ordinary自动relink/open续接。来源失败/Cancel保图及最后PNG；guard保存失败专门反馈，不等待挂死；旧Dialog callback先CAS identity不能清新prompt。

独审全部Required闭合，无剩余确定必修。真行为红：89159 footprint把合法negative margin/Rooms误报OUTSIDE；62583 detached旧尺寸提前page0→1；83316 fresh笔DOWN后仍page0→1。窄修后全量绿。66124中Map7为harness Main虚拟delay超时、Native1为把erasePages当删除页的fixture误用，修fixture而非降断言；93625 callback默认形状、56532 runner类名仅compile接线，均不算产品红。详细规格/失败界限/证据见docs/movinkpad/mind-map-source-navigation-2026-11-09.md。

精确scope先NOTE／批注PDF FILE/PAGE与已有region提示；Canvas／xmap FILE打开尚不支持。真实Android/DocsUI/SAF权限恢复、PDFium成功绘制、IME与配置重建/杀进程/Surface/GPU/MovinkPad Pro14 Android15 ProPen3/掌触/大PDF性能未运行；Robolectric Window不替代设备。返回bookmark/选择/history只runtime，不承诺跨进程。无APK/安装/推送/合并/发布。

下一条执行：来源文件／页面的“引用与关联”反向列表，自动来自图中真实sources，按graph/node/source稳定身份定位，不双向手工维护；复用MapDocuments唯一actor、现Main宿主和原保存保护，先独立规格/审查再实现/真实tests。之后局部／筛选／全库网络，严格区分content-reference/user-association/可选co-occurrence，不因多来源概念推导文件彼此依赖。真正Map双pane、region/Canvas入口和范围调整、精确身份/权限/移动/删除与回收站恢复、复制/完整备份、主画布primary大图/目录缩略预览、A2无中心/同级插入/边缘平移与设备验收等原未完范围保留，不重做已绿基础、不把native4全量producer迁移作UI硬前置。

协作仍Root统一Gradle/提交；source_loop_audit源端，cancel_event_tests Map/Android，review_input_plan独审。完成代理需followup_task唤醒。下轮先核Git/证据/活作业；本检查点已无挂起用户问题或外部阻塞。

## 最新已验证阶段：来源往返的Main可见宿主前置

基线08fba33b clean。本片verified（源码／主机与Android源码编译），整体active/complete=false。本地提交85c4216d（native所有物理pane/actual owner停车观察+6测试）、7e6c25f3（MapWorkspace/VM owner/perview presentation+6workspace/4camera/4仪器actualgetter）、14052e12（实际Main接线、2raw Window测试、3Android场景、文档）；本恢复点后续docs-only检查点，不需重复已绿验证。

最终28971终态0：347套3744项，失败/错误/跳过均0，Android测试源码编译绿，tmp/map-main-host-final-full.log及-summary.json；相关66529终态0：37套344项全绿与Androidcompile，tmp/map-main-host-native-map-related.log及-summary.json。生产/tests在full前全部冻结，之后仅docs/提交。无运行Gradle/exec作业。18新增：EditorHostParking6、MapWorkspaceHost6、MapPresentationState4、MainMapInputHost2；Android MapWorkspaceEditingHostTest2+实际MainMapWorkspaceRecreateHostTest1仅编译并已登记，4旧Map编辑仪器测试改actual documentSession，不能声称平台运行。

实际交付：Main图入口复用MindMapScreen/MapDocuments同actor。native GlWetPad on-top不能Compose盖板，先等全部native owner/两physical输入/已接IO与session encoders/原文本提交，最终纯parking proof同Main无悬挂复核才不compose native Scaffold/SplitHost；保模型/历史/阅读相机，不强制源保存或cancel笔。返回native沿surfacesForAttach换湿墨/前缓冲View（设置保留），Map自己的View等待IME/尺寸两帧稳定后restore一次，不fit/arrange/undo/清redo/覆盖新selection。

MapWorkspaceOwner由actual Main ViewModelProvider取得，跨config保同Entry/lease/Session/presentation，VM最终clear才dispose；composition仅按binding token取消自己旧等待/清旧parkingCheck，不强留oldEditor/Main。openMap/current/unbind/async onClosed两端核原token，旧宿主迟到不能关新host相同Entry。acceptedOpens全体join后清理，不只等最后work。URI/title仅processdeath currentnull冷恢复，非跨进程undo承诺。Map body隐藏仍常驻3picker壳/slots；PageToMapFlowHost只停显Added dialog不丢其controller/lease。native所有dialogs/AiChatHost仅停显不取消app jobs/AI，外部import保pending提示关闭图后继续/可取消，不在隐藏工作区起guard。Map前台keys/四指/native click latch隔离，触摸在原四指余流后直接super原事件，无Samsung改写。

独审计划/代码/两Required及末次onClosed修复全部闭合，无剩余确定必修。真实行为红63530：Main raw2均失败（隐藏native click变hold、特殊流[0,211,213,212,1]变[0,3,0,2,1]），最小early-super修后344/3744均绿。73803缺API预期编译红；10375 Root局部activity命名冲突；14989无Recomposer未通知致Workspace5超时→加Snapshot通知7002五绿（非产品红）；59861中17/18绿，canonical读数竞争81468 actual stack确证MapStore.save冲突读，test loop不再快速advance虚拟350ms，原assert/timeout不改，临时diagnostic移除。详细docs/movinkpad/mind-map-main-host-2026-11-09.md，README/AGENTS/plan/todo同步。

全员completed/frozen，Root统一Gradle/提交；source_loop_audit owns Editor/parking/tests，cancel_event_tests presentation/screen/actualgetters/Androidrecreate，review_input_plan independent review。completed需followup_task唤醒。不能把本片hide/show/runtime测试当来源Open闭环，原目标仍全保留。

## 最新已验证阶段：从当前页添加来源到图

基线9691a442干净。本片verified（源码／主机与Android源码编译），整体active/complete=false。已本地提交：510d0abd（严格页来源基线与Editor消费者）、576a35eb（共享MapSession/lease/typed导入保存）、a9fb4573（真实当前页入口/UI/31新增主机/2Android场景/文档）。本恢复点为后续docs-only检查点；不需重复最终测试。

真实用户路径：PDF/分页笔记页缩略图菜单→原实际pane/owner/Document/Page请求→严格源准备或明确旧页启用/实际重开→选择已有/新图→已有概念或Newbranch/free引用→一次history→精确文件保存/失败只重试→保阅读/显式查看图。新doc docs/movinkpad/mind-map-current-page-reference-2026-11-09.md，README/AGENTS/plan/todo同步；不代表region/来源往返/Map双窗格/反向引用/网络已完成。

最终95689终态0：343套件3726项，失败/错误/跳过均0，Android测试源码编译绿；tmp/current-page-to-map-final-full.log及同名-summary.json。全部生产/测试在full前冻结，后仅docs/提交。相关42894终态0：33套326项（源12/目标12/Root7均绿）与Androidcompile，tmp/current-page-to-map-native-map-related.log及-summary.json。新增PageToMapEditingHostTest2已登记，仅编译未运行。无运行Gradle/exec作业。

关键源端：private actual model/revision/path/identity/scope+NativeSourceVersion从真实采用/发布前进，B+C1保B捕获revision，不伪造EditSource/写权。source-only flush串原native job、固定正文、publishExpected，未知base拒写。请求后同model/canonical path的startNoteWrite/saveToThen/flushAutosave/persist/close持续strict，取消modal不能退弱stamps；clean-only重开不先保存脏冲突。保护/请求实际retire清理。旧页原字节启用后实际重开再新请求，不按ordinal补旧模型。已接受B在原owner/model/path/id/scope/旧observation仍对应时推进真实V_B，取消原graph request不丢B证明；最外guard结束对同脏模型恢复原schedule，C1自动写且仍核expected B。

目标：MapDocuments按canonical URI唯一private VMStore/actual MapSession，Activity和reader只持lease；0lease安全保存/无draft才clear，失败保actor，generation CAS防重acquire误清。typed create空目标/Retained不重复创建；per-attempt Written/journal/Conflict不混，C1不误清。MapSourceImportTicket绑定issuer/epoch/doc/node/parent寿命；final源IO后Main latest merge，existing保正文/primary(null)，newnode+source一步undo，textDraft/textRestore时Newbranch Busy不整理，existing/free仍合并且不抢原文本选择。Root选择/IME/确认冻结context/token，reload后旧选择不能重签；branch继承刚选parent并显示标题。

独立计划/API/实际代码复核全部闭合，无剩余确定必修。真实红及修复：28566源dirty A覆盖外部sameUUID/PID C且无stamp、未知legacy被force-save→strict拒；70492中29/5红（源自家晚登记encoder2、primary/layout2、父节点1）→24286中29全绿；26647源12/1红（取消期间真MotionEvent C1，实际只B1写、未自动debounce）→42894新增31与相关326全绿。生命周期断言原生产已清protection且绿。早期harness Snapshot未pump的等待超时、fixture过早SQL失败不算产品行为红；保tmp/current-page-*.log原证据。

所有作者均completed/frozen，Root统一Gradle/提交。source_loop_audit源production/tests，cancel_event_tests目标production/tests，review_input_plan独审；completed需followup_task唤醒。不要重做已验证页/预览阶段，不把完整native4生产迁移当来源UI硬前置。

## 下一条立即执行：严格来源打开／定位／返回图

宿主前置现已落地，不重做它。接着在具体来源列表加Open，复用MapSession现sourcePreview寿命票+graph epoch/node/source target，备注/排序/cache C1不废票，target变/删除undo/reload废票；保存原相机/选集/具体source ID，成功后才hide图，失败一直保图；返回同Entry/actor，原条目还有效才定位。

已有精确只读审计（仍仅建议，下一片定稿独审后实施）：tmp/source-navigation-native-api.txt、source-navigation-map-api.txt、source-navigation-next-audit.txt。Native建议NativeReferenceTarget(UUID,pageId?)、Editor.locateOpenReference/openReference typed结果；Storage.prepareReferenceOpen同源openSource→核NOTE/UUID/PageID→PreparedNativeOpen.read，绝不能先registerForOpen/ensure为缺UUID文件迁移补ID。openNative已有prepare/settle/reread/adopt，但需要首read、晚重read、最终adopt三处目标约束；actual已有owner按永久PageID找，live删除页返回Deleted，不借disk旧页或goToTocEntry ordinal回退复活。PDF缺原件SHA恢复后也须回原expected票重新验证，不借relinkMissingPdf自动普通openAsync的true当定位成功。

重要模式边界：目前图在Main是全窗口单文档，旧native workspace只是停放，即使editor.inSplit仍true也不能把它当成“图正处于双窗格”并在来源跳转时自动显示旧split。下一片须按实际可见工作区模式设计：单图跳来源保持单文档、不自动恢复旧双窗格；真正图+来源双pane仍后续接统一Pane类型/ToolbarLayout/PaneChrome/保存关闭/Surface边界。可以复用已开来源actual owner，但安全收起/保存其他停放pane与避免第二owner需要保护采用接口定稿，不能简单Main.openInto+事后UUID检查。

来源操作还须保持已有private actual baseline/strict保存保护：已开RAM与外部同UUID变化不能因引用操作洗白旧基线或让后续weak autosave覆盖外部版本。先核actual owner/Source UUID及当前页，再使用与PageReferenceSource相同的真实版本证明/保护，不伪造EditSource或写权；已经接受的旧IO语义和保已写B不回滚不变。这里是下一片验收点，尚未实现，不强开完整native4做硬前置。

临时定位轮廓为独立runtime overlay，绑actual model/Page/navigation token，纸面对比palette+实际页变换；不改选区/批注/dirty/历史，退休旧timer。来源准备/保存失败/取消/C1/pane变/权限与身份冲突均保原workspace与图，Main现宿主token可复用。textDraft期间追加来源不抢选择，结束后新增节点定位仍待与此片整合。

原范围仍保留：区域/Canvas入口与范围调整、主画布primary/目录缩略预览、权限/身份/文件移动与回收站恢复、独立副本/备份语义、反向引用、局部/筛选/全库网络、无中心关系画布/同级插入/边缘平移。网络strict区分真实内容引用/手工关联/可选同图出现，多来源概念不推导文件互相依赖。真实Android/DocsUI/SAF/PDFium成功绘制/MovinkPad Android15 ProPen3/掌触/Surface/GPU/大PDF性能未运行；无APK/安装/推送/合并/发布。

## 最新阶段：来源预览、保留最后图片与刷新

基线 f07f8d0b，本阶段 verified 并已本地提交 6cc2293a（feat: persist and refresh mind map source previews）；整体未完成。用户问“完工了吗”已答尚未完成，不视为暂停或缩小范围。后续文档检查点未改源码或测试。

实际交付：已提交节点的来源列表按需 PNG／单条及整图 check/refresh/cancel；可见 AUTO 合批同文件，实际 snapshot/decode/PDF 会话复用。恢复预览与来源卡共享 NativeDocumentPreview CPU 绘制，不 new Editor、不 adopt/写源、不碰 GL/前缓冲。xmap 规范 URI 锁仅包完整 bytes，解码绘图在锁外；锁等待可取消。冷态无需先 MainActivity：解码/测字前幂等 FontCatalog/TemplateLibrary ensure，warm Store/cache 不重载。

模型写 xmap v3、读1/2/3；不可变 PNG 工厂、精确 lastPreview 原目标/版本绑定、sourceVersion 原始 SHA 与缓存 observed/acceptedPreviewVersion 分离，xmap-render-v1 语义域排除派生字段，self/cycle 不自激。总量 1024 项/8MiB raw/16Mi 像素；每图512px/256KiB。IO逐历史帧验证预算且立即丢编码 bytes；Main同history generation原子合并，source lifetime独立防目标/删除 ABA。无新内容 undo、不清 redo，保正文/备注/排序/primary。同target的晚面板操作保actual lastKnownPage。

EMPTY只用于已证明纸面外region；显式刷新可接受无新PNG的空结果而保旧图，check只保同target/version证明；版本/目标变化失效，AUTO已变基线不洗白。失败关闭/reload重建当前doc新source tokens，不复活旧job。不可访问/缺页保旧图；永久删除/回收站分类与SAF修复UI未接。

最终20677终态0：340套3695项，失败/错误/跳过均0，Android源码编译通过；tmp/source-preview-final-full.log及tmp/source-preview-final-full-summary.json。新增MapPreviewFormatTest13、BackendTest9、ConsumerTest9，均绿。新增Android MapPreviewFormatAndroidTest2，既有Compose页面来源用例改等自动预览稳定后捕获；仅编译未运行。所有生产/测试在最终全量前冻结，此后仅文档/提交。

独立计划/代码审核已闭合，无剩余确定必修。行为红证据：49427页hint回写、84657规范锁半文件/取消、36181失败后source lifetime、53706真实EMPTY（36181误filter未跑EMPTY，不计其红）、27663冷态lines/fallback。37003 API缺失预期红；69564首相关248中247绿，唯一Root double反例被JSON规范化，改raw literal后格式绿。相关日志在tmp/source-preview-*.log。最终全量包含所有修复，不以旧3664结果冒新验证。

正式记录 docs/movinkpad/mind-map-source-preview-2026-11-09.md，README/AGENTS已同步v3与当前边界。当前未打包/安装/推送/合并/发布；无运行Gradle/exec作业。真实SAF/DocumentsUI/Android仪器/PDFium成功绘制及rooms/crop/大PDF性能/MovinkPad Pro14 Android15 ProPen3/掌触/GPU/双窗格未执行。

下一步按原计划继续：当前文档添加页面/region来源→来源定位/轮廓/返回与窗格交接→反向引用→文件局部/筛选/全库网络，strict区分真实内容引用/手工关联/可选同图出现；不由多来源概念自动推断文件互相依赖。主画布主要来源大图、未确认引用草稿预览、选页目录缩略图、纸面内空区域检测、范围/失效恢复、无中心关系画布、同级插入、边缘自动平移等需求保留。

## 前阶段：实际PAGE来源与创建出口

- 5719b2f0：typed新建NOTE/CANVAS/MAP/Markdown及PDF导入，空目标发布/失败不delete provider URI；note复用admitHeld/PDF lease，不eager永久pin临时UUID。分叉Main prepare scope/intent→IO project固定正文→最终owner/path/identity/source/scope/PDF guard→adopt实际ID/PDF→才ForkLedger.record；C1保dirty，历史恢复页新scope；备用copy失败仅实际fork拒，1024深度普通save仍可用。提取双端稳定gate/固定metadata/原Page+revision票，晚改不删source；merge持源PDFlease并重建合成PDF元数据，提取/合并写后取消不删B。
- 286dacaa：导图两个生产文件picker→session-owned MapSourcePickerController；整文件/actual文字页目录、NeedsActivation独立确认、写后失败明确续固定B；最终选择等笔释放后重新读SHA/member，追加existing只merge最新sources，新ref草稿确认前不commit。source.PAGE+sourceVersion保存重开同ID，FILE only Canvas/xmap，状态UNVERIFIED，无缩略图/跳转。
- source UI 4必修已独审闭合：节点删除/草稿kind变退休ticket并cancelIO；系统picker pending占位rememberSaveable且关panel不提前清、Add禁用；registry.registerMapObserved事务拒同ID/位置跨kind和positive页契约；connected128→129拒当前追加保原draft/票，可取消后确认128。普通读取只persist READ，explicit activation/continue才WRITE。

最终54796终态0：337套3664项主机，失败/错误/跳过均0；Android测试源码编译通过。日志tmp/page-source-readable-location-final-full.log，计数tmp/page-source-readable-location-final-full-summary.json。所有生产/测试在最终全量前冻结，之后只文档/提交，无运行Gradle。此前65412全量337/3660绿后补真实merge2例，33781 fork/extract/merge8绿，再跑最终全量。Android新增MapPageSourceEditingHostTest 2真实Compose/a11y/ActivityResultRegistry例+1 JSON NULL/严格数值合同，PenTestRunner已登记；仅编译未运行。

正式新增EditorCreateNoteFileHostTest 5方法（12拒绝场景+4格式empty成功/SQL失败续写），EditorForkExtractHostTest 8（C1/Hx/final-adopt拒路由/提取晚改取消/Markdown预取消/1024普通flush/merge2），MapPageSourceConsumerTest 9（actualSource/Session/history/save-reopen、写后固定B、笔等待、失效票/跨kind/128限制）。两中文graph/native_creation一致，diff --check干净，未新增依赖或渲染器。

证据/修复：23233真实create红（非空竟成功覆盖、写后失败无pending）；43783是测试未移入的filter错，不计行为红。15426 PAGE API红。6096暴露测试browseRoot privateSetter，改公共updateBrowseRoot。33437 70相关69绿，first fork C1 dirty断言受idleFor快速推进virtual1200ms debounce干扰；52526诊断writes=1但可已接受下次write，21993仅pump当前post不推进未来计时后6全绿；未改产品或放宽断言。独审也修了extract双端gate、首checkActive普通WriteCancelled漏catch、备用fork深度限制误阻普通save。

本轮后续小片新增optional lastKnownPage显示hint，列表“上次核对第N页”/旧引用“位置待刷新”取代内部UUID。SourceAccess从actual目录填hint，FILE清空；定位仍pageId，备注/身份/SHA不变；v2缺省/NULL兼容且严格Int/Long/范围验证。新增Format2方法，Android额外JSON合同，窄审通过，已单独本地提交6b3f29f4。

证据文档docs/movinkpad/mind-map-page-source-flow-2026-11-09.md。未运行系统DocumentsUI/真实SAF恢复/Android仪器/PDFium实际PDF合成/MovinkPad Android15 ProPen3/GPU/掌触/大PDF性能。本片不改渲染器/前缓冲。不要把文字页选择当成缩略图或来源往返已完成。

## 已有基础（不重做）

930de041 MapStore.create/MapSession/MapFiles存盘JSON副本、canonical URI锁/已知alias旧日志/staging保护；6a69cd73 Main固定source picker+native存盘副本读唯一raw snapshot/PDF lease，不改live；3e6cda3c activatePages/completePageActivation actualNeedsActivation/原字节A→B/recordDigest+B SHA/旧入口purpose隔离/fresh目录；2f3becbf前阶段doc。334套3640主机和Android源码编译旧证据保留。

更早1fe1286c原字节启用验证器、929ab4df独立destination picker、47c4cc49实际分页SaveAs、f4b15c60证据；2081c753/267322cb/1f5e31e7 actual恢复读/独立副本/Backstage恢复界面。SaveAs先fixed+scope再发布，最终guard/adopt/C1保持/Hx/viewport/PDF实际指针；typed明确续写不偷偷采用，原source context在独立身份采用后退休。

## 下一条立即实现的用户路径

1. 从当前PDF/笔记页添加来源到图（选已有图/新图、已有node/新ref），等稳定保存后核actual模型页ID/目录，不能按第N页给旧模型补ID；region提示仅xmap，原PDF/批注context区分。文本/手绘node多来源正文不被覆盖。
2. 来源打开定位/轮廓/返回图与视口选择、保存失败保原workspace；双窗格整合（当前MapActivity独立，Map分屏仍不支持）及反向引用。按DocUUID和PageUUID，重排跟随、删页不改指。
3. 来源预览后续：主画布primary大图、未确认引用草稿预览、选页目录缩略图、纸面内区域空检测；复用本阶段服务，保持合批/可取消，不为每卡重复完整解码PDF。来源列表/最后PNG/检查与刷新现已verified，不重做。
4. 失效/移动/权限/身份恢复、回收站/永久删除分类、范围调整与最后预览、复制能力/备份语义。
5. 局部/筛选/全库文件网络与加入手工关系画布；strict区分content-reference/user-association/可选co-occurrence，多来源Concept不自动生成文件互相依赖。
6. A2无中心/分支同级插入/边缘自动平移等原剩余交互；MovinkPad/Android15/ProPen3/SAF/GPU真实验收单列，不可用主机结果代替。

正式finite参考docs/movinkpad/reference-page-ui-first-2026-11-09.md的创建/activation/PAGE片现已落；接着真用户路径，不重新展开完整native4作硬前置。完整NativeWorkingSession producer/cache/hand-off迁移仍独立规划且未全部生产启用，相关未完成的保存恢复要求保留。完全未知旧raw URI的hash日志不可反推，已知alias冲突保留，不猜测。

## 协作

Root统一全部Gradle/本地提交。source_loop_audit负责本阶段backend/共享CPU/catalog初始化与9测试；cancel_event_tests负责history/session/controller/列表UI及9消费者；review_input_plan独立计划/生产/修复审查。三位均completed/frozen，无作业；completed需followup_task，send_message不唤醒。下阶段先核当前Git/恢复点与原计划，继续实际用户路径，不重新用未启用native4作硬前置。

## 当前新阶段：Canvas 固定区域来源（planning，2026-11-10）

规格docs/movinkpad/canvas-region-sources-2026-11-10.md已写，独立计划审核中，尚未写功能源码。A/B/C均完成只读勘查。独立CANVAS_REGION scope＋固定world Rect，只在xmap中存；当前来源或lastPreview含新scope才写v5，其余保v3/v4；旧v1/2字段跳读也须raw gate拒新scope。

真实selection/capture菜单→共用PageToMap source adapter→已有/新图、已有/新概念→保存重开→严格region PNG/EMPTY/历史图→明确Canvas kind→真实attach稳定定位/对比轮廓/输入取消→同图返回。clean来源只读；dirty显式保存必须strict原版本原位发布，不走弱stamp/fork通用路径。正在核正常Canvas自动保存版本观察的最小衔接，避免routine autosaved B被误当未知并反复重开。

计划审阅已要求：误导文件名不决定Canvas类型；v1—4 exact source/lastPreview raw路径拒新scope；同Rect清空保旧PNG须明确历史图，不能只靠target不同。源码实现等保存方案结论处理完毕后开始；无本阶段运行作业。
