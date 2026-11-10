# 原生存储与导图协作细节归档（2026-11-10）

用户要求：B5 精简长期规则，把实施进度和协议细节移到对应文档，保留可追溯依据。
状态：细节已归档；下列是精简前的规则快照，其中历史进度不代表本轮状态。当前实现与验证见 mind-map-merge-fixes-2026-11-10.md 及各主题最新记录。
本轮边界：仅文档与本地提交；不打包、不安装、不推送、不发布。

长期约束以 AGENTS.md 为准。这里保留精简前的接口名称、流程及主题链接，供维护相关模块时查阅。后续实现不得以历史的“尚未接线”句子覆盖已有源码和最新验证。

## 文档打开与分屏

- 无限画布定位为画画与草稿，只能单独打开，不进入分屏：不提供分屏按钮与「在旁边打开」，分屏窗格内拒绝打开画布并提示。含画布的旧 `.xsplit` 在替换当前工作区前拒绝打开；上次会话的含画布分屏只恢复主窗格自身会话。偏笔记的结构化与关联需求交给思维导图模块。详见 `docs/movinkpad/canvas-sketch-only-2026-11-07.md`。
- 主菜单打开普通文档进入单文档模式；窗格内首页打开文件只替换所在窗格；只有显式打开或恢复分屏才恢复双窗格。
- 替换工作区前等待保存完成，避免异步关闭回调误关新文档；文档未打开成功时，不得因预检查失败丢弃当前工作区。
- 换模型后的输入 reset 只丢弃旧草稿、连击、预览和异步请求，不通过新模型恢复或补交旧手势。嵌套自动滚动、PDF 文字请求和颜色拖放任务也须退休；旧事务的确认／取消／保存由替换前的 owner 流程处理。见 `docs/movinkpad/reference-stable-capture-2026-11-09.md`。
- 原生笔记/画布完整同源准备后才能替换；采用前重新settle所有相关documentOwner，并重新核实实际来源版本，自己保存改变来源时须真正重读新模型。最后IO前后核runtime owner、所有相关model的revision/路径及两个native job身份，不能只凭dirty再次为false采用旧读；最终同步核工作区、在途写与笔接触。打开/诊断/保存续接/选择器回调绑定实际模型，迟到不作用于新模型；打开登记在同一存储锁内拒绝未知pending保存。见 `docs/movinkpad/reference-verified-open-2026-11-08.md`、`docs/movinkpad/reference-open-after-save-2026-11-09.md`。
- 分屏文档的关闭 / 返回先显示该窗格的文件选择首页，关闭首页才移除窗格。暂留文档由已有的保存 / 放弃保护处理替换与最终关闭；同文件共享视图关闭拥有者时，保留另一侧首页的物理位置。
- 文件树只为已确认存在子文件夹的节点显示展开箭头。
- 分页编辑器跨 AndroidView 宿主重挂时，必须通过 `Editor.surfacesForAttach()` 替换 GlWetPad / FrontInk：先归还暂存笔迹并断开旧回调，保留文档、普通画布、缓存及前缓冲开关。未经目标设备回归不要恢复复用旧湿墨 View。底层 RenderNode / crop 机制仍为推断，不把该修复扩大为全部场景或其他设备的验收。详见 `docs/movinkpad/split-wet-ink-2026-11-04.md`。
- 无限画布的跨宿主重挂候选通过 `InfiniteEditor.surfacesForAttach()` 在旧 GL 线程完成 detach 后替换主绘制 View 与湿墨 View；保留同一 viewport、scene、文档与撤销，归还暂存及正在绘制的笔段，不复用旧 renderer 的帧回调。首次挂载不替换。画布不再分屏后，主菜单往返仍会卸载并重挂宿主；稳定工具栏布局只减少不必要拆挂，不能取代真实重挂时的替换保护，也不据此宣称黑区已解决。见 `docs/movinkpad/canvas-split-black-2026-11-07.md`、`docs/movinkpad/stable-pane-layout-backport-2026-11-07.md`。

## 存储、PDF 与迁移

- 笔记完全本地离线保存，无账号、同步或应用后端。修改须持久化；涉及存储、导入、删除、迁移的变更，验证恢复路径与失败行为。
- 新native请求固定捕获item写入字段；NativeWorkingSession先以实际owner只读epoch observation预约，只有requiresBody才固定正文，view-only不持模型正文、不遍历item、不授保存权限；Stroke仅pin不可变Samples前缀，不复制/重编码采样。每页/画布单独映射胶合与图层，复制嵌套可变容器；画布不支持TextItem时拒绝而非略去。旧默认浅快照保持；Editor实际保存已接固定视图与稳定屏障，NativeWorkingSession生产迁移仍未完成。见 `docs/movinkpad/reference-frozen-writer-2026-11-08.md`、`docs/movinkpad/reference-owner-capture-2026-11-09.md`。
- 生产保存先等旧编码，再在Main完整dispatch之后核actual owner／模型／笔接触／内容事务，固定捕获后才交IO；不自动确认或取消笔、选择、样式或表格预览。显式保存按原blur语义提交文字与flow burst，等待输入时不弹阻断确认的modal。共享文档核两侧输入，关闭／替换最后重新核实；退役实例不开始新捕获，已接受IO不取消。旧会话目录FIFO及checked写入不授native权限，正文复用只凭同runtime模型／bodyRevision成功记录；未采用secondary与失效文档占位不清旧恢复资料。
- NativeWorkingSession写metadata schema2、兼容1；T与当前正文同head持久，须实际staged/private Main permit/owner/head代次证明，不能拿旧T背书新失败尝试。cold未认领T只可读资料、不可普通写/clear；native4受控ack与actual Receipt accepted-ID完成已实现，cold actualB恢复/认领已实现（实际采用/Read转交与首次新正文提交后才开保存）；活owner接管与生产未启用，不能切半套生产或混用native-only写入绕过T。见 `docs/movinkpad/reference-native-handoff-2026-11-08.md`。
- 原生普通保存可prepare固定候选后再commit：阶段证明绑定实际storage issuer、canonical journal、本次写入record摘要与A/B版本；仅实际成功commit签请求receipt，prepare不等于保存成功。协调v4 stage仅接受实际同head Publication的typed ack，重启及旧ensure均拒写；完整cache/producer接线仍待完成。验证/复制/读回逐块检查源epoch与取消；见 `docs/movinkpad/reference-native-handoff-2026-11-08.md`。
- 严格保留已写版本须有有效typed SAVE、实际目标=候选摘要及完整原生契约；事务登记重查URI/kind/页契约，最后大包hash后重读target、再有界核marker后清日志。返回实际读版本不冒充请求发布receipt；external PDF实际SHA在lease内核对。生产producer接线仍待完成，见 `docs/movinkpad/reference-keep-written-save-2026-11-08.md`。
- 保存已写入文件而后续步骤失败时，之后选择“放弃修改”保留已写入版本、只放弃之后的新修改，不回滚原文件。未核实的部分写入保留pending资料，不冒充完整版本；见 `docs/movinkpad/reference-discard-written-2026-11-08.md`。
- 原 PDF 与只读导入原件保持不变；批注与可清理显示缓存分开。大型扫描 PDF 与工具光标方案仅在 `tasks/plan.md`、`tasks/todo.md` 规划，未完成。
- 导入与格式升级时保留旧笔迹及原 PDF 负载的原始字节；这不构成修改普通笔画简化的授权。迁移须先暂存并校验再发布，不得借模型重新编码来升级元数据或迁移笔迹。
- 页面引用登记契约按文档 UUID 单调保存，位置丢失不撤销；保存预检须同时核对位置与观察 UUID 的期望，typed 契约失败不得降级为“不支持拼接”再覆盖。schema2与header预检组件已实现，生产接线仍待完成，见 `docs/movinkpad/reference-contract-registry-2026-11-08.md`。
- 文档 UUID 与资源指纹分开。ID 缺失或重复时需显式恢复，不随意选择。登记表 / SAF 恢复及基于 ID 的分屏恢复已接入源码，设备验收待执行。
- 分页首次保存／明确另存走`saveAsToThen`及NativeCreation，非空目标（含原URI）不得降级为普通覆盖；选择器固定原请求及运行时唯一键，不预先打开输出流。原owner先准备PageReferenceCopy，固定正文发布成功后才最终核模型／来源／scope／PDF指针并采用；采用前判断晚到修改并保dirty，不以延迟saving modal当输入冻结证明。PDF采用实际受管文件与身份，临时lease跨编码和发布；失败保持原模型，可独立续完保留副本。文件浏览器副本固定原来源请求，只读实际存盘内容，经空目标发布，不采用或保存当前草稿；共享导出保持身份，独立副本投影新身份。
- 原生新建/导入/提取/合并不使用通用SAF写流回调；typed固定正文先核空目标，再经NativeCreation发布，不在失败或写后取消时删除provider返回的未知目标。分叉在Main准备/采用页scope，最终核原owner/路径/身份/来源/PDF，采用成功才登记ForkLedger；晚到内容保dirty。提取双端等待输入稳定，移动前核捕获版本，源有新修改则只保留副本；合并持源PDF租约到合成与发布结束。
- 引用以页面为单位（2026-11-07 用户决定）：分页笔记 / PDF 只为页面保存永久引用编号，画布按文档 UUID 引用，不为对象分配编号；可选区域提示只存于 `.xmap`。页面删除后引用显示已删除、不改指；复制得到新页面编号。对象编号已从模型与笔记/画布编解码撤除，B2c 取消；原始整包复制/导出、分页另存与分叉已接通新页面身份；来源往返及完整引用闭环仍待接通。页启用使用独立ACTIVATE_PAGES事务，首发与续写均证明原字节A→B权限并绑定同一候选；旧保存/恢复不得自动消费，成功重新读取实际页目录后再供引用使用。详见 `docs/movinkpad/reference-page-level-2026-11-07.md`、`docs/movinkpad/page-reference-implementation-2026-11-08.md`。
- 页面引用身份独立于 Page.uid 和 GlueIds，ReferenceIdentity 一次性赋值／恢复。拥有者捕获 snapshot 前准备新页面 ID，writer 不补活模型 ID；snapshot 冻结页面身份，页面深复制取得独立身份。已有文件启用须走原字节升级候选，不借模型 writer 升级。页面身份缺失／重复须显式恢复；对象缺失不使页面身份失效。显式仅笔迹副本使用独立恢复入口，不覆盖原文件，可略去无法读取或层归属损坏的对象（新页面身份与原件不相交）；略去数量独立按对象出现次数统计并告知用户，幸存连接线不得指向略去对象。
- 单份 PDF：内部 `.xnote` 用身份元数据 v2 引用私有不可变 PDF 对象；分享 / 导出生成嵌入原 PDF 的便携包（身份 v1）。旧包在打开时按需迁移：先校验并持久保护原件，再逐条保留非 PDF 负载并安全发布。
- 恢复资料的只读准备与原件来源权限分开：`NativeWorkingSession.Recovery.Unbound` 持实际正文／解码资产／PDF租约，调用者须关闭；渲染与复制任务结束后再释放。缺失、清除和损坏不能混作同一种空状态；只有实际Absent可检查旧保存槽。独立副本按持有原始bundle复制到Creation指定新UUID，不通过可变预览模型重编码，不重新绑定或覆盖原件。Backstage实际窗格入口已接通历史预览与独立副本；所有界面动作和选择器结果绑定原请求，关闭先等待IO再释放。写后失败保同一typed事务，重启续完须明确选择原目标；不自动采用或清恢复head。详见 `docs/movinkpad/reference-recovery-preview-2026-11-09.md`；平台及真机验收待执行。
- 共享不可变 PDF 对象使用索引引用、活跃租约与延迟回收；不得被缩略图、合并或导出临时清理删除。内部笔记单独复制不构成备份，完整备份必须经应用导出。平台及目标设备验收待执行。详见 `docs/movinkpad/pdf-single-copy-2026-11-05.md`。
- 引用式笔记的私有原件缺失（其他安装身份迁移、重装、清除数据）时，恢复对话框提供重新关联（原 PDF 或仍内嵌原件的旧笔记 / 导出包，SHA-256 必须与记录一致，不得用不同 PDF 替换底稿），以及不写原文件的「仅笔迹」副本。受管原件只由引用与租约回收删除，任何临时文件清理（如 `adoptOpenPdf`）都必须跳过 `PdfResources.isOwned`。详见 `docs/movinkpad/pdf-relink-2026-11-05.md`、`docs/movinkpad/pdf-original-deletion-2026-11-06.md`。

- 工作会话的来源描述仅是待核实数据，不是保存凭据；恢复必须重新核实真实来源版本。NativeWorkingSession的便携正文与严格metadata共用原子head，坏/未来格式不得被新capture、clear或GC覆盖清除；视口复用只凭同owner/kind/UUID/bodyRevision的成功提交证明。生产会话接线仍待完成，见 `docs/movinkpad/reference-native-session-adapter-2026-11-08.md`。

## 图层

- 分页 / PDF 按页、无限画布按文档管理 DrawingLayers。图层 ID 与对象归属在删除、移动、跨页转移、橡皮碎片和撤销重做中保持一致；保存快照冻结成员关系，不读取后续编辑过的 layerId。
- 默认单层沿用历史序列化形式；原 PDF / 模板、PDF 文字标注和跨页排版正文仍是独立的背景或排版内容。
- 多层实时笔迹按层序合成，不能直接放在顶层前缓冲；保留用户的前缓冲设置与上述湿墨 View 重挂规则。源码验证不替代真机 GPU、视觉与性能验收。
- 主要入口是绘画软件式侧栏（参考 Krita），不恢复为图层列表弹窗：分页 / PDF 在文档侧栏的图层页签，无限画布使用同类常驻侧栏。本阶段仅侧栏与缩略图，不做分组、透明度或混合模式。
- 缩略图与名称区域按下后移动即可排序（保留系统移动阈值防误触），不要求长按；点击选层、双击名称重命名。落下形成一次撤销，取消不改模型；选择当前层不占用内容撤销记录。面板跟随当前页面 / 文档与撤销更新。
- 缩略图只显示单层自身内容，隐藏层仍有预览，不混入 PDF / 纸张背景。显示范围：分页笔记 / PDF 为不显示、已有笔记区域、整页；无限画布只有不显示和已有笔记区域。内容模式按单层内容边界裁切；不显示时不计算边界、不生成位图；显示设置不进入撤销栈。
- 异步渲染只读几何快照；翻页、关闭或版本变化须取消旧任务。预览缓存不写入笔记格式。

## 思维导图

续作 c97f58f9 合并时保留的协议细节快照；最新长期规则见 AGENTS.md，实施状态以实际记录为准。

- 混合安排使用独立 `.xsplit` v3 纯描述（恰好一 Map＋一 NOTE），v1—2 仍由旧 Native parser 读取；typed 损坏／未来版本不得降级。1 MiB／32 层预算必须在平台递归 JSON 解析前完成，不能让 Android 的注释／未引号 token 绕过深度限制。保存的 UUID／URI／视图不授来源读写或模型采用权限；两角色先按 UUID 解析唯一位置，重复／错类不回旧 hint，实际来源仍初末核实。保存必须取得本次实际 Map Written B SHA 与 Native 最新成功 publication B，stage 后 Native 最终实际重读、Map 最终同 B 读与共同 owner/model/jobs/input/域票、Main createLink 连续完成；不能用 clean、日志或后读外部 C 背书 B。私有新安排只用独占 stage＋fsync＋createLink 新建发布，不覆盖既有目标；实际 Main 与测试共用一次性 publication owner，IO→Main 取消清自己的 stage，发布后取消／清理／recent 失败保成品。Native/typed recent 共 canonical 串行 writer，Main reserve 仅废未 claim 旧票，已接受前缀完成且不越后发提交；迟到初读不覆盖新 state，普通 persist 不覆盖坏／未来 recent，明确 Save 才可更新。recent 与 cold last-presentation 分开；当前已接保存与显式重开，进程冷恢复仍待接线。详见 `docs/movinkpad/mixed-split-persistence-2026-11-10.md`。
- 原生 Kotlin 导图使用独立 `.xmap` JSON 格式（`xnotes-mind-map`，普通图写入版本 3、含无中心组织或文件卡片时写入版本 4、当前或历史预览目标含 Canvas 区域时写入版本 5，含独立原 PDF 投影时写入版本 6，兼容读取版本 1—6），不得交给 `.xnote`／`.xcanvas` ZIP 编解码器。文本与手绘节点使用稳定 ID；手绘保留节点局部坐标的压力和时间采样，布局与子树移动不重编码笔迹。
- 来源预览为派生数据，不增加内容撤销步骤或清空重做；IO 对捕获的所有历史帧逐帧验证预算后，Main 只采用同一历史代次的整体结果。来源存在性／目标的生命周期票与历史代次分开，目标往返及删除后撤销不复用旧请求。最后 PNG 绑定实际描绘的原目标／版本，失效或换范围不清图、不冒称最新；固定空区域的证明只用于同目标同版本。图片使用受限静态 PNG 工厂、摘要／CRC／有界解压及总量预算，不能向历史暴露可改字节数组。预览不是源文件备份，受管 PDF 不归图片清理所有。同批同文件共读／解码／PDF 会话；图文件只在既有规范 URI 锁内读完整字节，再到锁外绘制。缓存语义摘要只防 self／cycle 刷新自激，来源写入与冲突仍核对实际 SHA。详见 `docs/movinkpad/mind-map-source-preview-2026-11-09.md`。
- 内容历史使用不可变快照；节点编辑确认后形成一个文档操作，取消手势／取消编辑不提交；根节点不能删除或换父，禁止父子循环。恢复日志先原子写入，再写 SAF；失败不得冒充保存成功，外部修改先核对摘要，不覆盖冲突文件。冲突只经用户显式选择解除：「保留当前版本」以目标现状为基准覆盖，「改用文件版本」读取成功后才删除日志；另存副本使用新文档 UUID；无修改时不因切到后台而保存。独立主题保持原位，其分支随布局排列。新副本仅接受可读空目标且无既有恢复日志，先原子保留候选再发布；同实际文件的URI别名共用锁并兼顾已知旧日志。预存或已同步的staging保留，只有本次拥有而未完整同步的临时staging可在失败时清理。详见 `docs/movinkpad/mind-map-review-fixes-2026-11-07.md`。

- 普通概念的来源范围／换源只修改最新节点中的同sourceId目标，保正文、备注、顺序、主要来源与并发新增来源；文件卡不改canonical身份。换源清旧页／区域并重新选择，原件重新关联仍按原SHA，不借换源修底稿。实际来源只读准备与最终SHA／永久页／身份域重核，旧PNG仍绑定原目标。来源面板保纯Runtime owner，composition只持自己host token；配置卸载只detach，明确关闭与Entry退休才退owner，迟到旧宿主不关新稿。文件选择器在Workspace的entry key之外保槽到原结果消费；当前host即使read失效仍可结束busy，旧host无此权。页启用／原件保留／恢复分别明确授权，成功不因取消范围编辑回滚。详见`docs/movinkpad/source-target-edit-2026-11-10.md`。
- 空白落点以新卡片中心为锚，节点与无方向关联线先保留为Session草稿，确认按最新图一次提交；取消不产生节点/线。文件选择从launch绑定文档代次与独立请求ID，不在结果返回时改绑。替换文档期间禁止新事务，普通自动保存不废草稿；来源编辑只合来源字段，连续回调同步推进自己的已成功列表，外部变化须重试。
- 手绘节点采样使用原 node-local 原点，允许有限的框外坐标；显示裁切不得夹紧或删除采样。原位／放大使用同一输入事务、工具与文档历史，落笔锁定归属和变换，每笔抬起一次提交；进入退出放大不整理、不写内容历史。手绘属性只合并所编辑字段，不覆盖其间新增的来源或笔迹。
- 页面来源选择保留原图/节点/草稿ticket到最终选择；先等笔接触结束，再重读实际来源核SHA与页成员。节点删除、草稿类型改变和图退休须取消相应IO；系统picker旧占位保留至其结果消费，防迟到结果错绑。普通读取只保留读权限，页启用/续写经明确确认。来源登记核实际类型/单调页契约，JSON导图不能重分类已有原生身份；追加只合并最新来源字段且拒超限，不覆盖正文。位置显示可选的上次核对页码，不展示内部UUID、不用显示页码定位；实际身份仍是永久pageId，范围/来源更换须重新核对提示。
- 从阅读页发起引用固定实际窗格／owner／模型／页面；保存以实际读取或成功发布的正文版本为基线，最终页目录须匹配该版本。未知基线不强制保存，旧文件启用后实际重开再发起，不能按页序号给旧模型补编号。该模型的后续自动／暂停／关闭保存也保持严格基线，取消引用不解除保护；实际替换才退休。已接受的保存完成后仍记录真实 B，不能把之后的 C1 标为已保存或因取消请求漏掉 C1 自动保存。见 `docs/movinkpad/mind-map-current-page-reference-2026-11-09.md`。
- 同一规范 URI 的导图经 `MapDocuments` 共享唯一实际 `MapSession`，Activity／引用流程只释放自己的租约。追加使用原目标代次及节点寿命票，在最新节点上合并；新节点与来源一步撤销，文本编辑／视口恢复中不整理分支。保存确认必须使用本次 `saveAttempt` 的实际结果，日志保留不冒充写入成功，重试不重复追加；不在后台另开一份模型覆盖活会话。
- Main 图宿主由 ViewModel 保留 Entry／租约／运行时 presentation，配置重建不通过 URI 重开清历史；composition 只按宿主 token 解绑，旧打开／保存关闭回调不得影响新宿主，清除旧 native proof 以免保留旧 Editor。全窗显示图前等待全部实际 owner 和两个物理输入面及已接受任务，最终同步核只读停车观察；不强制保存或取消笔。全窗图不挂载 native SplitHost，混合模式只在另一真实槽挂分页 Surface；重挂沿既有 `surfacesForAttach()`，不能用 Compose 盖板遮住 on-top 湿墨 Surface。全局 native 键／四指／点击 latch 不作用于图，混合按实际槽路由，图触摸不经旧 Samsung 改写；每宿主相机仅 runtime、稳定尺寸／IME 后恢复，不写内容历史。见 `docs/movinkpad/mind-map-main-host-2026-11-09.md`，实际 Android 重建／Surface／GPU 待验。
- 导图＋NOTE／批注PDF混合布局只在ViewModel保纯槽／几何／焦点／Hint，MapScreen经单一movable content在全窗与真实槽间移动，保原picker登记；来源子图关闭先父，仅根关闭进Home。指定分页来源请求绑定实际Editor／owner／模型，保原固定读和最终同步采用；retained text／flow只等自然结束，不blur。换工作区先准备Map实际保存证明，不先退Entry；共享租约只免保存，不能免最终输入稳定，Native同步adopt后才退Map路由。混合焦点不复制工具／提交文字，DOWN归属保持到UP，KEY_UP交原接收者并保native间HOLD继承。暂停／关闭不把混合槽写成native-only `.xsplit`。Canvas只能独立显示，回来是Map＋实际Canvas槽的Home；只有明确Home权可保存后替换／关闭暂留Canvas。Original返回票只存原native纯运行时身份，不匹配回单图不复活旧布局；配置Hint在原逻辑槽实际会话采用前核UUID／类型／规范路径，不授保存权限、不清坏恢复资料。全局导入在混合期间保pending，快捷打开／设置进入本槽Home；Entry已空的MapHome仍属混合。显式纯Native两选／应用内saved split／recent统一双候选，先两初读、mandatory settle所有旧Map current／visited、再Native guard／保存／两源末读，最后同步双采用并退休本路由；失败不降Missing占位，startup恢复占位规则保留。retained owner集合保模型／历史／正式工具，shared新peer按actual documentOwner继承HOLD；fresh B/B不复用旧A/A peer。换模型slot及其Infinite有活动录音时拒绝，先退旧播放；成功用纯会话／视口／布局持久化，不调用会reset/copy的persist；pendingRestore只读入元数据，不挪UI。见 `docs/movinkpad/native-split-replacement-2026-11-10.md`。Map＋NOTE双选先只读原始JSON与身份域再acquire，fresh／加载中actor核实际loadedRawSHA，已有actor保同session／epoch，所有恢复checkpoint核UUID；Native实际source核knownkind／每个规范位置／永久页契约／Original域，final票跨最后Map IO。两候选最后同Main栈采用，不把一次性cap存Entry；retained输入不blur，退出owner已接受的blur／保存失败后保留。按键press绑定model token，repeat与新DOWN区分，交接只迁有效接收者；Map实际键盘焦点按attach／就绪／呈现代次交接。双文件详见 `docs/movinkpad/map-pair-open-2026-11-10.md`。详见 `docs/movinkpad/map-native-split-2026-11-10.md`。
- 导图整文件来源在主窗口共用 `MapDocuments` 实际会话；规范 URI 的 visited Entry 只持一份租约，逐跳保存不可变相机与来源书签，返回不强制保存或重开模型。最终原始 JSON UUID／SHA、登记身份代次与实际 actor 分别核验，规范 URI 锁覆盖最后 IO 与同步 Main 采用；登记结构变化的票须跨实际外层 SQL 提交／回滚。Map→Map 纯停车不提交 native text／flow，不额外持久化停放文档；每次 push／pop 均保护此策略，普通打开恢复原策略。Main 关闭先走 Entry／lease hook：子图返回，根图按实际 saveAttempt 与当前代次关闭，不置共享 Session.closing 或退休其他 owner 草稿；明确保留恢复仅释放本路由。配置解绑清整个暂留池的旧宿主闭包，迟到结果不得改绑。详见 `docs/movinkpad/map-source-navigation-2026-11-10.md`。
- 来源打开只读核文档 UUID／永久页 ID，不经 ensure 补身份或用页码改指；已开来源保 actual owner／原槽／修改和历史，已知重复位置拒绝选择。全窗口图跳来源不恢复停放分屏，其余 owner 复用原保存／放弃保护；最后 IO 用实际正文／任务版本核验，原生采用与图隐藏之间不挂起，迟到清理不回滚。Entry 只存纯图返回书签，显式返回才恢复选择／来源条目，范围或图世代变化不复活旧票。来源备注 IME 不覆盖普通相机，失效成员收尾仍须解除相机保护。定位轮廓等 actual attach／稳定 preDraw，按真实纸面范围变换；fresh DOWN 取消尚未应用的定位，不在笔画中移动相机、不改原事件。详见 `docs/movinkpad/mind-map-source-navigation-2026-11-09.md`；真实 Android／Surface／设备验收仍待。
- 分页区域入口冻结实际 Document／Page 和页局部 Rect；选区按实际对象唯一页归属，不信旧 index，截图正常 UP 时固定原页，跨页／gap／越界不静默裁切。外发菜单候选独立退票：新截图、清除／重选、删除后撤销及换模型不能复活；已签 PageReferenceRequest 不随成功收起菜单而退休，重排仍原永久页。区域明确属于当前笔记／PDF 批注上下文，不冒充独立原 PDF。NOTE 区域 preview／来源首读及晚变重读用实际绘制对象完整观察，已知丢项模型在自动 flush 前拒；默认宽容读保持，XML 明确失败可观察但不宣称未来扩展无损，实际 raw XML／PDF／自定义模板存在时保守判内容。固定预览 frame 不随纸边或内容收缩；首次宿主前可等，进入后卸载退待定位票。见 `docs/movinkpad/paged-region-sources-2026-11-10.md`；原 PDF 独立内容模式与真实平台／设备仍待。
- Canvas 整文件来源按实际核实的类型与文档 UUID 单文档打开，不接受页面或区域目标、不恢复分屏。请求冻结实际 kind 与对应模型；未知显式 URI 只读实际 manifest 识别，不按文件名猜。live 重复 UUID 跨 NOTE／CANVAS 检查；已打开 Canvas 保同模型／历史／视口／editSource，retained owner 等待 Canvas 输入屏障，不强制保存。参考 Canvas 首次准备和来源变化后的重新解码均用严格完整读取，未知项／未产对象／待处理资产无法物化时拒绝；采用前核实际模型与有序对象，失败不授新 editSource，旧默认宽容读取不因此变更。Canvas 来源返回只为本次图呈现跳过 Main 额外 persist，下一普通打开恢复原策略。见 `docs/movinkpad/canvas-source-navigation-2026-11-10.md`；FILE 目标继续不携带区域。
- Canvas 区域来源使用独立 CANVAS_REGION（文档 UUID＋固定世界 Rect），不接受 pageId、不跟随对象，FILE＋region 仍拒绝；旧 v1—4 在 current source 与 lastPreview.target 原始路径拒绝新 scope。只读准备绑定实际完整采用／成功发布版本，显式原位保存按 expected SHA，不走弱 stamp 或 fork；known incomplete 不授此权。无路径／未知基线以专用原请求选择器显式保存独立副本，typed 空目标发布成功后才同模型采用新身份／路径，保历史与晚到 C1 dirty，按原范围签新请求；取消只保已写副本，retained 只续同候选。预览严格完整解码、按原范围裁切，空区保历史图但明示历史；实际 GL／overlay 首次挂载稳定两帧才定位，进入宿主后卸载或新输入退票，定位不写 lastView／内容历史。见 `docs/movinkpad/canvas-region-sources-2026-11-10.md`；真实平台与 GPU 待验。
- 原 PDF 原文使用独立 PDF_ORIGINAL scope、catalog 随机文档／永久页 UUID，以及 SHA／页完整性描述／turn／可空 PDF user-space Rect 投影；普通 region 为空，不借 Note UUID 或 hash 当身份。v1—5 在旧路径跳读前拒新 scope／非空投影，current 与历史 PNG 目标均纳 v6。catalog 是权威资料，schema3 与单份 PDF 的 namespaced pin 同 SQLite 事务；mutation 起始退休票，坏／未来／非整数记录及异常 own pinset 保 pin 拒改。active pin 恰 {SHA}，tombstone 为空；显式恢复／严格同 SHA 重新关联保完整映射并增代，delete→restore 不复活旧票。原件库取消加图或删 Note 仍保留，完整引用备份仍待接。
- 原文网络事实只读打开既有DB，不自动创建／升级，不初始化PDF资源、不读正文／hash／engine／lease；先在SQL边界限制原始类型与字节，不截断伪有效，标题保原UTF16长度契约。类型与可用状态分开，删除／缺失保原文kind；逐目标派生状态不改源／PNG／历史／blocked，原件库目录独立。共享network epoch在catalog／完整relink及首次native跨域碰撞START退旧票、真正结束后END。卡片专用固定metadata票可跨目标actor自身重投影，但仍核原epoch／控制代次；引用与导航票维持原严格观察。详见 `docs/movinkpad/original-pdf-network-2026-11-10.md`。
- PDF 整页与区域菜单先签独立请求再清 UI 候选，选择原文不保存／补 Note 页 ID；区域只映射完整单 Rooms tile，按72/dpi和实际 PDF geometry，整页不套区域限制。原文 Reader 为无 Note 正文／路径的临时投影、专用只读输入，不采用 Editor；主窗口独占，配置换宿主同步退旧 Reader，停止并 join 所有栅格／文本／链接任务、确认真实 native close 后才放 PDF lease。创建后的首次开图与原文返回均传实际停车策略，不额外 persist 脏 Note；普通 Note 策略仍为原值。详见 `docs/movinkpad/original-pdf-sources-2026-11-10.md`，实际 Android／PDFium 像素／设备待验。
- 文本原位编辑复用 TextEditorOverlay／TextEditHost；组合输入、选择和草稿按事务 token 保留，不逐键写历史。确认核对初始正文并按最新节点宽度合并，保留并发来源；取消与迟到 IME 回调不写文档。光标避让仅改变相机，退出等待 IME／宿主尺寸稳定后恢复视口与选择。
- 节点选择与手绘套索为独立工具，选集／框选预览不写正文历史或导出。组移动按子树并集只平移一次，不隐式换父；批量删除先排除中心根再求子树，仅删图中节点与线。对齐仅支持至少两个自由顶级主题并携各自分支移动。轻点／框选／移动／删除／对齐使用实际文档、会话代次、正文与选择单调版本票据；签发核对视图原选集，旧按钮不能为新选区重新签票。取消后隔离余流，双指相机自身回调与外部取消分开。详见 `docs/movinkpad/mind-map-node-selection-2026-11-09.md`。

- 画布连线只把节点对送入同一普通线草稿，确认前不写文档。连接余流使用独立drain，在普通ink接管前拦截，不改旧笔优先规则；armed时禁quick-scale，笔接触冻结相机，取消/失焦/resize/disable归还busy。提示用图内覆盖层、工具栏标题限单行，避免重排取消模式；支持touch-mode焦点。详见 `docs/movinkpad/mind-map-canvas-connect-2026-11-08.md`。
- 普通关系编辑以initial edge和draft分开，经MapSession从latest文档提交；创建/修改/删除各一次commit，取消零写入。重复定位返回latest已有线，不携带另一线草稿说明；无向端点互换为语义no-op。端点/线失效不崩溃、不覆盖；英文“引用自”用“A references B”，不反转箭头语义。详见 `docs/movinkpad/mind-map-relation-editing-2026-11-08.md`。
- 普通线、箭翼与说明的绘制／命中共用MapRelationGeometry，48dp为屏幕目标全宽，按density/当前scale换算；节点、来源条、手绘正文及连接入口优先，父子线不混入普通关系命中。节点／线选择互斥且仅为会话状态，不进内容历史／导出；现有线必须以actual initial进入原草稿，全部关系列表保持可达。
- 导图native press/preview绑定内容与会话epoch；相同内容重载、禁用或内容更新也退休旧拖动，避免旧UP回退新来源／正文。结束POINTER_UP、失焦／卸载须清detector定时与适当余流，不阻碍下一次笔接管；正常同会话节点双击保留。见 `docs/movinkpad/mind-map-relation-hit-2026-11-09.md`。

- 来源侧反向查询只读实际授权资料与图恢复赢家，沿原实际来源保存基准；登记不是全库或写入授权。live 图会话覆盖存盘观察，原成员票失效不降级重签；自动网络不从同一概念的多来源推导文件互相依赖。
- 图位置／恢复范围拒写后的保留标记属于 MapDocuments Entry，跨全部 lease 生效；最后默认归还也不自动保存或清除脏会话。仅取消尚未接受的自动保存，不取消已接受写入。移除后 undo 再重试只保存当前内容，不能误报「已移除」。详见 `docs/movinkpad/mind-map-backlinks-2026-11-09.md`。

- 文件关系网络（局部／多文件中心／全库）是派生只读场景，不进入 MapSession 或 writer；纯查询／相机保存在 ViewModel，不保旧原生 Editor／Activity／View。无局部中心时的内部视图编号不得作为文件或原生来源身份。网络跳图核真实本层来源／所属关系及所选来源的原成员能力，最终核实际网络输入代次和新原生停车证明，不因展示而强制保存停放笔记。多中心先匹配真实来源／所属再计读取额度；孤立登记只占关系后的余量，实际同位置异 UUID 不冒充可用孤立文件。隐藏保 actor，关闭／替换／销毁须退休旧停车闭包；加载提示不改变 Canvas 几何或清稳定位置。详见 `docs/movinkpad/file-network-overview-2026-11-09.md`。

- 关系画布使用同一 `.xmap`／MapSession／MindMapView，可零节点且无受保护中心；普通导图仍保护唯一中心。显式文件卡片按文件 UUID 去重，限 REFERENCE 与单一整文件身份来源（FILE，或原件canonical首页锚PDF_ORIGINAL；后者代表整份原件，pageIndex=0／turn=0／无区域）；此来源用于打开／预览，不混入内容引用。普通概念和多个来源不推断文件互相依赖。已有图批量加入一次撤销，新图初始内容一次完整创建；失败只重试保存，旧历史／成员票不得因 undo/redo 恢复而复活。新建先固定候选、系统保存即确认，不在 CreateDocument 后再提供会留下空目标的额外取消步骤；已有图确认前不 acquire，取消不触发最后归还的自动保存。详见 `docs/movinkpad/network-to-relation-canvas-2026-11-10.md`。

- 显式文件关联只来自 `fileCardId` 两端的实际普通关系，同次只读图观察与 live overlay 投影为独立证据，不伪造 `MapSource`。网络按真实端点匹配、聚合和展开；关联所属画布不替代端点或生成所有权边。跨图同向／类型聚合保具体图和关系 ID，无向先规范端点；关联与内容引用共享最终预算，深层不能脱离已保留的前缀。打开原关系复核完整语义、两端寿命、位置／恢复和原生停车，临时揭示两端折叠祖先；移动和标题改动不变文件身份。存盘前缀在 actor 出现或退休后拒旧票，只有本次独占打开目标图才可接认其同图实际前缀；不额外打开前驱图。冲突状态纳入实际观察并立即退休旧关系票。详见 `docs/movinkpad/file-relation-exploration-2026-11-10.md`。

- 网络来源筛选只用同一真实来源的 UUID／格式／目录条件，匹配后保整概念；概念的 owner 只作上下文。孤立文件按自身身份筛选，不因此让所属概念匹配。格式是上次识别值，类型筛选不要求当前位置仍可用；目录事实未知不冒充不匹配。不得为筛选复制来源正文／PDF、创建缺失根或把筛选结果当保存权限；读取与系统选择回调须可取消并绑定原草稿／查询代次。
