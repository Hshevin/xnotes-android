# 思维导图与文件关系网络：当前恢复点

目标：按原需求继续实现，active，overall complete=false。反向引用阶段已verified（3812全量）；本轮D1局部网络基础层verified（3834全量与本地组件提交），产品入口／往返仍in_progress，整体未完成。
工作树：/Users/kita/.codex/worktrees/mind-map/xnotes，分支codex/mind-map。
授权：代码、编译／测试、文档、本地提交。master54f05b0f既有同步已完成；不打包APK、安装、推送、合并或发布。
规则：page-level引用（分页永久页UUID，画布仅文档UUID，可选区域只存xmap），不恢复对象ID；原字节升级与PDF SHA校验／资源租约不变；失败已写版本保留，不回滚原件。遵守本仓AGENTS/MOVINKPAD/README/project-brief。

## 当前阶段：D1 局部网络（in_progress，基础层verified）

基线20fdf40e clean。基础层本地提交946664af（Model/Assembler/Scene/Domain4）、af66ffa5（Library/Controller/Data5）；只读View/13host回归/8Android源码及本记录在随后本地组件提交。本轮progress：已完成纯NetworkQuery／完整概念Source投影、全局250nodes/750实际边/5000成员预算、稳定只读Scene adapter、Library共享同锁observe pipeline、app-only Controller C1 overlay、MindMapView显式readonly导航及输入/取消/Back隔离。实际产品入口／双成员Access-Focus／Workspace-VM／Main纯停车与前台raw gate／网络返回尚未接，D1及完整D均未完成，不宣称可在UI使用。正式规格docs/movinkpad/file-local-network-2026-11-09.md，精确计划/API在tmp/file-network-stage-spec.txt（含独审A–C）、network-data-api.txt、network-readonly-view-api.txt。

基础层最终84411终态0：360套3834项，fail/error/skip全0，Android源码compile绿；tmp/file-network-foundation-final-full.log/-summary.json。15 frozen app/src输入摘要在-final-inputs.json，运行后全部一致。新22方法=Domain4/Data5/ReadonlyHost13（8shared+2timeout+3directActivityBack），Android同构8只compile已登记。4037是缺API预期编译红；69608相关318/317，唯一pinch是ScaleDetector首次跨阈仅初始化跨度，补真实后续MOVE保1.1断言无产品改动。42042真实10/3 Cancel/Escape红，47490真实13/3 Back红，产品最窄修后full覆盖。DirectActivity Back不替代现代Android15系统返回。review_network_plan独立代码/证据审核闭合，基础层无Required；所有源码/tests正式freeze，当前无活Gradle/exec。

下一条继续D1真实闭环，不另起native4：先消费Source代理已准备tmp/network-access-api.txt及MapNetworkAccessTest.kt（3真实双成员proof/ABA/Scope草稿，仅tmp未formal）；Root将Source代理授权正式Shared Access单acquire+MapSession networkAnchor ticket，旧Backlink API不放宽。Root实现纯FileNetworkWorkspace/VM与composition Coordinator、LocalNetworkPresentation/InputTicket及native readonly Canvas宿主、Sidebar入口/解释UI、Main图形前台gate、MapWorkspace.flushNativeOnPresent=false网络展示副作用隔离、源T返回图保原flag、返回网络相机/选择。初始NativeContext仅入场guard，成功后Query/Controller独立中心S，不持旧Editor/View/Activity；网络→图可focus co-source T，必须原anchorS+targetT双票，IO中新readonly DOWN永久退本次动作，SourceStatus只是最后记录。

D1独审3Required已在计划A–C定稿：1网络跳图避免Main maps.visible effect无条件editor.persist强制flush，普通Map打开默认原行为；2final核实际View token+monotonic inputSerial+idle而非只native parking；3精确计Content+Belongs边，不以成员数代替，100concept×7引用=800edges只能保93完整concept/744edges。实现Main/Host部分后仍需独审与行为/Android源码、full验证。

协作：Root统一全部Gradle/提交；source_loop_audit数据5tests/Library/Controller正式freeze，双Access/Focus仅tmp草稿；cancel_event_tests View/helper/shared/host/device wrapper正式freeze；review_network_plan新fresh-context独审（旧review_input_plan已completed），不派生agent、不build。没有用户待答或外部阻塞。未打包/安装/推送/合并/发布；目标设备/真实Android/SAF/IME/PDFium/Surface/GPU未运行。原完整D筛选/全库/手工关联/可选同图/逐层展开/检查失效/加入关系画布，以及C/A2/恢复/备份剩余全保留。

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
