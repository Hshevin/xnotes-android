# 来源范围调整与更换（2026-11-10）

用户要求：按完整14组思维导图需求继续实现，从已完成的原文文件网络接入来源范围调整／换源，交付可操作的创建选择、保存重开、预览及实际打开返回。
状态：已实现并通过34套252项有效相关主机测试、Android测试源码编译及独立逐XML／输入复核；实际平台与目标设备待验。
本轮边界：仅实现、相关编译／测试、文档及本地提交；不打包APK、安装、推送、合并或发布。2026-11-10 按用户再次明确要求，从 mind-map-original-network 的8254f3b7新建 mind-map-source-target 工作树／codex/mind-map-source-target分支，迁移28个未提交文件逐字节一致，旧树完整保留；独立build/cache均使用source-target专用路径。

## 已核事实与本阶段结果

现SourceListEditor只有备注／主要来源／排序／移除，MapSourcePickerController只有Native整文件和文字页目录；没有来源行的范围或换源操作。模型已持久化全部目标及旧PNG，source target变化会退休旧preview/navigation票；editSources却按整列表semantic比较，不适合跨IO逐条目标修改。

来源行增加「调整范围」「换为另一来源…」，只用于普通概念来源，文件身份卡仍维护canonical整文件契约。调整范围只读当前同文档／同身份域；换源先明确告知，再选择新文件／原件和新范围，不继承旧页码或矩形。重新定位同UUID／重新授权及同SHA原PDF修复继续走各自恢复流程，不借换源改底稿。

- Note／PDF批注：整文件、实际永久页、该页固定区域；页列表显示上次核对页码，不显示内部UUID。
- Canvas：整文件或固定世界矩形，不能带pageId，不能跟随对象。
- 原PDF普通来源：实际永久原文页或其user-space区域，不把首页标为整份PDF。文件卡的首页整原件赋义不用于普通来源。
- 导图文件：当前仅整文件，不能假造页或区域；其实际打开能力仍属后续Map来源阶段，换源不得宣称已实现该跳转。

范围选择在独占只读画面内完成：平移／缩放、选实际页、拖框／拖边角、显式确认／取消；能选择整文件或整页。使用实际已保存内容，说明未保存草稿不在画面中，不保存停放native Note／Canvas。旧范围超出当前内容仍显示原轮廓，不自动收缩／clamp；切换页或文件明确清旧区域，必须重新选。更换目标后返回同来源行和原图相机，不整理节点或改选择。

## 单条目标事务

新增SourceTargetEditMode(RANGE/REPLACE)、MapSourceTargetEditTicket和Result(Applied(changed),Changed,Busy,Invalid)。

MapSession.captureSourceTargetEdit(nodeId,sourceId,displayedTarget,mode)在可开始时绑定实际session／图UUID／interaction epoch、现source membership寿命和初始exact target；复用MapSourcePreviewTicket，不以旧UI回调重新签票。sourceTargetEditCurrent只核固定票，applySourceTargetEdit在Main最终同步核原票、准备结果current及ink/content状态。

只在最新node.sources中原位patch同sourceId的target、实际核过的文件名／lastKnownPage／新sourceVersion，保最新正文、手绘、备注、顺序、primarySource和其他来源。target已被改／移除后undo／换图，旧票拒绝；单独正文、备注、追加其他来源或派生状态变化不废票。文件卡拒绝该接口。一次确认一步内容历史；相同exact target为成功no-op，不新增历史。候选只可来自本次实际目标读取，不授native保存权限。

保previewKey／lastPreview及资产原样，旧PNG继续绑定原target／版本；新target置STALE(有图)或UNVERIFIED，observedVersion／acceptedPreviewVersion清空，新sourceVersion仅来自实际新来源读取。AUTO只核当前来源，不把旧PNG冒称新目标；显式刷新才替换。确认消费本次ticket，重复回调不重做或重复追加；保存失败重存已接受的同一MapSession，不重新选择目标。未确认UI选择为runtime草稿，不阻断普通自动保存，不因自动保存丢失；图关闭／退休取消草稿，已接受保存不取消。

## 读取、候选与格式边界

新MapSourceTargetAccess/SourceRangeRead是只读准备，不创建Editor／InteractionController／新文档编辑引擎。使用app context，不保Activity/View。接口须在作者间锁定后实施：

- openCurrent(ticket,check)按实际UUID和已核类型找唯一位置，缺失／重复／回收站明确失败；原文只走catalog。
- inspectFile(uri,check)只读实际内容识别Native ZIP的manifest、xmap JSON或PDF，不按文件名／后缀／MIME猜实际类型。Native可新增NotebookStorage内部actual-kind读取入口复用observedSource(uri,null)，不改原默认读取策略；Map读取用现canonical URI锁。
- NativeOffer复用MapSourceAccess页启用与选择后的实际重读规则，只有单独明确同意才ACTIVATE_PAGES；失败续完沿原typed事务，保留“已打开旧编辑器可能需重开”提示；完成的页启用不因取消目标编辑而回滚。JSON导图不得重分类既有Native UUID。
- 原件库选择用独立有界分页显示资料（仅标题／ID／删除／row状态供选择，不物化所有原文页数组）。每次最多100行及一个probe，固定catalog epoch；按rowid内部游标推进，坏ID不送大原TEXT到CursorWindow，坏行保留并提示。选择具体原件后才实际observe完整页目录，坏／未来资料及native同UUID拒绝，不修pin。
- 从文件选择原PDF前先明确说明「加入原件库，取消换源后仍保留」并单独确认。受控临时原字节复制→已有OriginalPdfAccess.candidate/retain；相同SHA复用原身份，tombstone须另行明确恢复，不能自动恢复或替换底稿。取消加图不删已保留原件，candidate/engine/lease始终正确归还。

SourceRangeRead固定实际kind／UUID／URI／SHA、页目录及domain票。Note／Canvas沿NativeSourceSnapshot→PreparedNativeOpen，区域使用完整读取：未知或未物化内容／资产拒绝；原PDF沿OriginalPdfAccess.Read与实际pageInfo。文件名只作显示，不作身份。domain API在读前后及Main采用时核same票；不从原PDF失败退Native。确认前最终IO重读实际来源，核版本／kind／UUID／页成员／原文SHA和ordinal一致，来源变了提示重新加载而不把旧选择重新签到新版本。最终IO后到Main采用不再挂起；ink若在IO中开始则等抬笔后重新核，不强制取消。

## 临时图像与视觉坐标

复用NativeDocumentPreview及其CPU画图、CanvasViewport数学，新增SourceRegionVisualView只维护camera和Rect草稿。Note frame是实际页局部footprint；Canvas取完整内容边界与旧范围并集（空画布有可用初始视野），边界只为取景，不限制世界区域。原PDF按实际PdfKey(index,turn)/pageInfo.displayBounds画，选中Rect通过geometry四角逆变换到user-space；不能借Note DPI／Rooms换算独立原文。切页用实际record.pages[pageIndex]签新永久页，不复用entry.target原页。

单帧长边最多2048、串行一个render、最多一个待采用结果。首次概览后相机稳定再重画可见frame，拖框不重画。图像是临时画面，不进入.xmap previewAssets／历史。非PDF区域允许空白／原范围外空间，Rect校验MindMap MAX_COORD；原PDF必须明确包含于actual displayBounds，越界拒绝而非悄悄裁切。

不直接复用CropOverlay的clamp／最小5%／空白点击自动确认。新输入按原始MotionEvent区分pen/touch/cancel，48dp手柄命中，拖动超过系统阈值；第二指接管／失焦／卸载／页面切换／scope切换恢复本次未确认拖动，余流隔离，camera手势不提交内容。纸面overlay黑白双描边，大小/间距/touch均用Tokens。

IO返回前先在holder登记read／bitmap，覆盖withContext成功返回前prompt取消；未发布bitmap可立即回收，已发布画面沿SourcePreviewImage规则只解除controller／View引用交GC，不手工recycle，不能以View.detach推断RenderThread已停止使用。关闭：同步退输入与代次→取消并join串行render/读任务→实际PDF closeAndAwait→Prepared资产／SourceSnapshot lease释放。NativeDocumentPreview旧close仅异步PdfSource.close，新增范围专用可等待关闭，不改旧默认close语义。确认用prepare的固定可验证候选，不把渲染出的PNG当来源证明。

## Controller与宿主

MapSession持有SourceTargetEditController，保存当前ticket、原始requestId、选择模式／页／Rect和读取代次，不保宿主View。系统OpenDocument slot从launch到结果消费保原票，配置变化／隐藏不能再绑定到新source。一次只一个source target事务，与现添加来源picker互斥；旧占位消费前不发新picker。Controller state含失败／变化／需页启用／原文保留确认／可视选择及busy；所有慢IO可取消，旧worker只清自己的状态。

GraphSourcesDialog绑定可见row的displayedTarget与membership，再发起；来源行关闭、图退休、source删除或目标变化退本请求。普通自动保存与并发备注/正文不废稿。新视觉宿主只在map区域，未确认的native工作区始终停放、不额外保存。返同一SourceListEditor后sync最新来源，旧composition不可把成功结果退回。输入/bitmap跨配置重建按host token退旧View，保稳定draft，不让旧UP/回调确认新页。

## 验收与分工

A：读取／严格渲染／候选证明、原文分页选择资料、实际kind入口／PDF等待关闭与资源测试。
B：单来源target-edit票与Main合并、SourceListEditor入口适配及事务/历史/旧preview/open票回归。
C：Session-owned Controller／picker槽位、最终重核/确认/cancel及慢IO/配置/已接受保存续接测试。
root：计划与独审、visual View／相机/框选数学、GraphSources/Screen/三语UI、完整Host与平台事件源码、统一构建/证据/文档/本地提交。作者不各自构建、提交或派生。

必须证明：

1. PAGE整页/区域、Canvas整文件/区域、原PDF翻页/旋转/区域和真正跨文件/跨域换源可选择；原源缺失/重复/不同类型/坏内容/DPI/Rooms/裁剪/旧区域空时有明确行为，不静默换页或降级；fileCard拒绝。
2. 并发正文/手绘/其他sources/同row note和primary保留，target冲突/删除undo/reload/旧UI/旧picker/旧IO拒绝；一次undo/redo保原sourceId与新旧target/PNG，取消/no-op零历史，保存失败不重复改目标。
3. visual View真实MotionEvent拖框/手柄/平移缩放、pen锁变换/二指回滚/卸载/越界/切页，geometry逆变换含CropBox/rotation/DPI/Rooms；不以纯数值roundtrip冒称像素一致，CPU参考画面与实际native坐标差异须核。
4. 真实UI/Controller→目标选择→confirm→同图保存冷重开→旧图历史/显式新预览→实际SourceNavigator/OriginalReader→同图相机/选择/来源行；保脏native双Note/Canvas同模型/历史/字节、普通只读选择零额外写，原PDF原始字节不变。另行明确确认的ACTIVATE_PAGES／原件retain／restore写入分别记录与验收，取消目标不回滚已完成授权写。
5. 慢render/confirm重读、prompt取消、配置旧View、native/domain/页目录/版本变化与目录mutation ABA；实际engine关闭完成后才放lease，bitmap未发布/已发表双端安全。坏/未来/大字段只读保资料。

Android JSON和实际范围输入事件保仪器源码并编译；实际Android/SAF/Compose重建杀进程/PDFium像素/MovinkPad Pro14 Android15 ProPen3/Surface/GPU/性能未运行就明确标记，不用主机测试代替。原14组其余Map来源/真正双窗格/Canvas反向/精确恢复/完整引用备份/主预览/页缩略和剩余图交互继续保留。

独立计划审核source_target_plan_review已通过；两建议已采纳：页启用旧Editor重开／完成不回滚提示，普通只读零写与各自明确授权写分开。非交互自主闭环仅用环境内独立审核，不启动外部模型CLI。


## 已实现与验证

主机Host经SourceListEditor／Controller→逐条范围／换源→只读画面和MotionEvent API→最终重读确认→保存冷开→旧PNG保留／Native显式刷新→Native或OriginalPdfReader定位→同导图相机／选择／来源条目已跑通。Note／Canvas保原模型、内容历史和原字节；双脏笔记停放时的原文往返零额外保存。原PDF像素引擎在主机测试中受控替代，不据此宣称PDFium像素或GPU已验。Map整文件可以作为新来源，但其实际打开仍属下一阶段。

来源列表由MapPresentationState保留纯SourceListEditor.Runtime，每次composition只绑定自己的facade token；旧回调失活，重建保同请求／frame／区域／相机，明确关闭或Entry最终退役才取消。独立Activity由ViewModel保presentation。系统picker由Workspace稳定宿主在entry key之外注册，跨图保原占位。已发布Bitmap不手工回收；关闭等待实际engine结束后才放资产与lease。

| 作业 | 实际结果与用途 |
| --- | --- |
| 54658 | 新树生产／unit源码编译成功；28项中5个真实缺陷失败，固定1123个main/unit/资源/构建输入前后同SHA：取消pen的UP／pointerUP错误提交、已完成pan的余掌取消／失焦漏结算、失效read确认无CHANGED提示。原始XML及修复前View保留。 |
| 74511 | 33套250项，10失败，1200个完整输入前后同SHA；前述5项修复已过。Android新夹具常量位置写错，SOURCE编译失败，未当成平台已验。 |
| 2200 | 6项中仅新增“手势中来源失效仍可收尾busy”真实失败；修复前XML保留。1201输入同SHA，Android SOURCE编译实际通过。 |
| 18032 | 6套33项，29通过／4个Host取景断言失败，1201输入同SHA。前述busy修复、读取层、配置与Controller全部通过；Android SOURCE继续有效。 |
| 64506 | Host4中Canvas换源／区域／刷新／返回及cancel/no-op/最终版本拒绝已通过；另2项为Float事件ROI精度和普通ViewSnapshot引用相等的夹具错误，原始XML保留。 |
| 69825 | Host4全部通过，1201输入前后同SHA；Android SOURCE保持有效。 |

夹具修改有实际调用依据，未删业务断言：页启用的OwnedRecoveryTarget原子写绕过provider计数，改核磁盘SHA／实际字节变化与永久页冷读；finalIO测试改实际外部authority使读取屏障真正命中；Robolectric图片等待推进Main Handler的120ms虚时；待实际窗口稳定后执行真实Fit并采用新图，再发原worldRect输入，保屏内断言；相机逐字段delta0及fitFlags严格比，ROI沿既有0.001数值比较MotionEvent Float误差，不改生产几何或原笔迹。

最终按整suite替换受影响结果，保未变化的旧有效suite，合并为34套252个唯一case，0失败／错误／跳过；不累加重跑。完整逐XML、固定输入和结果在`tmp/source-target-{related,final,navigation-recheck}-*`，合并清单`tmp/source-target-verified-summary.json`，最终1201输入`tmp/source-target-final-inputs-verified.json`。所有已知构建均终态，运行状态`tmp/source-target-edit-runtime.json`。

计划、读取层、事务、输入与picker／配置／busy修复均已独立源码复核；最终逐XML与1201输入的独立复核通过，无剩余Required。真实Compose按钮链路保留在仪器测试源码中，未在主机Host冒充执行。新增6项Android仪器测试仅编译，未实际执行。真实Android／SAF／DocumentsUI、Activity实际旋转和杀进程、PDFium像素、MovinkPad Pro14 Android15 ProPen3／掌触／Surface／GPU／性能仍待验；未打包、安装、推送、合并或发布。

本地提交：f288afec（实际只读来源准备）、8ab29cbb（读取验证）、2b8c3cb1（单来源事务／Controller）、6e96fd2c（事务／取消竞态验证）、f0e22215（可视编辑／稳定宿主）、a1628462（冷开与往返）、5da33301（Android仪器源码）。迁移与计划6052ab36。整体目标仍active，下一实际阶段为Map来源整文件打开与返回；真正双窗格及原14组其余需求不缩减。
