# 思维导图与文件关系网络：当前恢复点

目标：按原需求继续实现，active，overall complete=false。前goal turn为progress（核实70492真实红并派发修复），本轮为progress（修复、3726全量、本地提交）；用户问“完工了吗”不是暂停/缩小范围。
工作树：/Users/kita/.codex/worktrees/mind-map/xnotes，分支codex/mind-map。
授权：代码、编译／测试、文档、本地提交。master54f05b0f既有同步已完成；不打包APK、安装、推送、合并或发布。
规则：page-level引用（分页永久页UUID，画布仅文档UUID，可选区域只存xmap），不恢复对象ID；原字节升级与PDF SHA校验／资源租约不变；失败已写版本保留，不回滚原件。遵守本仓AGENTS/MOVINKPAD/README/project-brief。

## 最新已验证阶段：从当前页添加来源到图

基线9691a442干净。本片verified（源码／主机与Android源码编译），整体active/complete=false。已本地提交：510d0abd（严格页来源基线与Editor消费者）、576a35eb（共享MapSession/lease/typed导入保存）、a9fb4573（真实当前页入口/UI/31新增主机/2Android场景/文档）。本恢复点为后续docs-only检查点；不需重复最终测试。

真实用户路径：PDF/分页笔记页缩略图菜单→原实际pane/owner/Document/Page请求→严格源准备或明确旧页启用/实际重开→选择已有/新图→已有概念或Newbranch/free引用→一次history→精确文件保存/失败只重试→保阅读/显式查看图。新doc docs/movinkpad/mind-map-current-page-reference-2026-11-09.md，README/AGENTS/plan/todo同步；不代表region/来源往返/Map双窗格/反向引用/网络已完成。

最终95689终态0：343套件3726项，失败/错误/跳过均0，Android测试源码编译绿；tmp/current-page-to-map-final-full.log及同名-summary.json。全部生产/测试在full前冻结，后仅docs/提交。相关42894终态0：33套326项（源12/目标12/Root7均绿）与Androidcompile，tmp/current-page-to-map-native-map-related.log及-summary.json。新增PageToMapEditingHostTest2已登记，仅编译未运行。无运行Gradle/exec作业。

关键源端：private actual model/revision/path/identity/scope+NativeSourceVersion从真实采用/发布前进，B+C1保B捕获revision，不伪造EditSource/写权。source-only flush串原native job、固定正文、publishExpected，未知base拒写。请求后同model/canonical path的startNoteWrite/saveToThen/flushAutosave/persist/close持续strict，取消modal不能退弱stamps；clean-only重开不先保存脏冲突。保护/请求实际retire清理。旧页原字节启用后实际重开再新请求，不按ordinal补旧模型。已接受B在原owner/model/path/id/scope/旧observation仍对应时推进真实V_B，取消原graph request不丢B证明；最外guard结束对同脏模型恢复原schedule，C1自动写且仍核expected B。

目标：MapDocuments按canonical URI唯一private VMStore/actual MapSession，Activity和reader只持lease；0lease安全保存/无draft才clear，失败保actor，generation CAS防重acquire误清。typed create空目标/Retained不重复创建；per-attempt Written/journal/Conflict不混，C1不误清。MapSourceImportTicket绑定issuer/epoch/doc/node/parent寿命；final源IO后Main latest merge，existing保正文/primary(null)，newnode+source一步undo，textDraft/textRestore时Newbranch Busy不整理，existing/free仍合并且不抢原文本选择。Root选择/IME/确认冻结context/token，reload后旧选择不能重签；branch继承刚选parent并显示标题。

独立计划/API/实际代码复核全部闭合，无剩余确定必修。真实红及修复：28566源dirty A覆盖外部sameUUID/PID C且无stamp、未知legacy被force-save→strict拒；70492中29/5红（源自家晚登记encoder2、primary/layout2、父节点1）→24286中29全绿；26647源12/1红（取消期间真MotionEvent C1，实际只B1写、未自动debounce）→42894新增31与相关326全绿。生命周期断言原生产已清protection且绿。早期harness Snapshot未pump的等待超时、fixture过早SQL失败不算产品行为红；保tmp/current-page-*.log原证据。

所有作者均completed/frozen，Root统一Gradle/提交。source_loop_audit源production/tests，cancel_event_tests目标production/tests，review_input_plan独审；completed需followup_task唤醒。不要重做已验证页/预览阶段，不把完整native4生产迁移当来源UI硬前置。

## 下一条立即执行：来源定位／返回图

只读接口审计已完成，tmp/source-navigation-next-audit.txt；下一片实施方案尚待定稿/独审，不可把审计建议冒充已批准接口。首先检查实际Main宿主与Map入口，把单文档图→来源→返回接上，然后真Map双pane/反向引用/网络。现MainActivity是singleTask，直接从MapActivity start Main会清上层图，不能靠采用后UUID检查保护失败；在MapActivity另建Editor也会重复native owner。审计建议Main内复用MindMapScreen+MapDocuments lease呈现单文档graph host，旧native owner留存，成功定位后才收图、失败图保持；需先核输入路由/GL宿主拆挂/关闭保存与后续pane接法，再独审，不盲加Intent flags。

来源按DocUUID先找已开actual owner/pane；活页按PageID查，不借磁盘复活live已删页；未开才registry.resolve+actual read-only catalog。旧预览SHA不要求等于当前内容，但实际UUID/页ID/类型必须在首读、晚重读、最终采用前证明。Editor.openNative已有严格prepare/settle/reread/adopt，但缺expected UUID/page约束和导航结果；不能goToTocEntry ordinal回退、不能ensure随机ID“修复”。新导航票绑定graph epoch/node+source寿命/target，不因备注/排序/check cache C1失效；保存原viewport/selection/具体来源条目。source未打开不提前隐藏图、替换工作区先保存，打开失败保当前图。

临时定位轮廓应是独立runtime overlay，读CanvasState.palette，按实际页变换定位，不改选择/批注/dirty/历史；旧model/Page/token退休timer。返回仍同MapSession，尺寸/IME稳定后还原viewport，不fit/arrange；target/条目仍同寿命才定位。真正Map双pane需要统一pane文档类型/ToolbarLayout分类/PaneChrome/保存关闭/Surface重挂，不能2Activity冒充分屏，原需求保留。目标textDraft期间引用不抢选择，编辑后定位追加节点待此片整合。

原范围仍保留：region/Canvas入口与范围调整、目录缩略/主画布primary大预览、权限/身份/文件移动与回收站恢复、独立副本/备份语义、反向引用、本地/筛选/全库网络、无中心关系画布/同级插入/边缘自动平移。网络strict区分真实内容引用/手工关联/可选同图出现，不从多来源概念自动推文件互相依赖。真实Android/DocsUI/SAF/PDFium成功渲染/MovinkPad Android15 ProPen3/掌触/GPU/大文档性能未运行；无APK/安装/推送/合并/发布。

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
