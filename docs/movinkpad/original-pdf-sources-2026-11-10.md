# 原 PDF 独立原文来源（2026-11-10）

状态：verified（源码／主机验证及Android测试源码编译），计划、实现与逐项证据已独立复核；整体 active 未完成。
基线579e5f54，新工作树mind-map-original-pdf从该提交创建；原树两份未提交计划复制后保留，迁移清单tmp/original-pdf-worktree-copy.json；上一goal turn为progress（分页区域完整流程、281有效测试与本地提交）。旧构建全部终态。
范围：本地原生实现、相关编译／测试、文档及本地提交；不打包APK、安装、推送、合并或发布。

## 用户结果

PDF现有整页“引用此页”与实际页区域菜单都先签固定的actual Doc/Page/可空Rect独立请求，再提供“此笔记页面（含批注）”及“保留并引用原PDF原文”。原文选项明确：原文独立保留在本机原件库，取消加图或删除批注笔记仍保留。含批注仅有实际Note上下文时提供；空白插页不提供原文。整页原文使用userRegion=null，直接引用实际PDF页，不套区域单tile限制；无Note页锚或含Rooms的PDF页也可使用。跨插入空白等不能无损映射成单一原文矩形时明确调整范围，不静默合并／裁切。

原文复用已有／新图与已有／新概念、一次导入undo、保存失败只重存；重开图保原文目标与最后PNG。预览和实际只读reader只含原PDF内容，不含Note笔迹、文字批注、排版、模板、滤镜、插空。reader以原PDF的永久页编号定位／临时范围轮廓，关闭返回原MapEntry／相机／选择／来源。原native两窗格／Canvas保持原owner/model/history/dirty/path及权限，原文往返不保存它们。

主菜单原件库可列出、打开及明确删除／恢复独立原文。删除提示已有引用将无法打开，但保节点／PNG，其他批注笔记不变；只释放本catalog owner引用，不直接删共享文件。迁移／完整引用备份仍须包含原件及catalog身份资料，不把它当可重建缓存；本片不冒称完整备份已支持。

## 独立身份与事务

- 新OriginalPdfCatalog持独立随机document UUID和每个实际PDF页的随机永久page UUID。按完整实际SHA去重资源，SHA不是documentId，不借某Note UUID。原Note只用于本次用户选择与坐标观察，不参与持久原文目标，Note删除／重排不改原文身份。
- DocumentRegistry schema2→3，原1→3兼容先补原page contracts；新增original_pdf_documents及original_pdf_pages。记录含row_schema、resource SHA、title、actual pageCount、generation、deleted；完整ordinal/pageUUID映射唯一且有界。unknown/future/corrupt拒修改并保已有pin，不重签／补页。
- 保留前持实际PDF租约，完整hash及真实pageCount／几何验证。catalog行、全部页映射及namespaced resource_refs在同一SQLite事务提交；事务内不反向获取PdfResources gate。故障原子回滚，不存在已发布记录却无pin。资源文件先持有／按既有admit去重，未发布记录的无引用对象仍沿既有延迟GC。
- 删除同一事务写tombstone、generation++和释放仅本owner pin；活reader lease仍保护文件。保留tombstone不删身份映射。再次保留同SHA返回RestoreRequired，用户明确恢复后保同UUID／页映射、增加generation并原子复pin；不静默复活或分配新身份。
- runtime canonical-DB共享epoch在mutation开始就退休旧票（回滚可保守退休），busy时不签新采用证明；DB事务结束才可取新观察。最终票核epoch／actual完整record generation，delete→restore不复活旧IO。恢复再次核SHA、actual pageCount和完整原有映射，不能只凭SHA。
- 原件库恢复可用仍在的同SHA对象；缺失时允许显式选原PDF重新关联，严格相同SHA／pageCount，不用不同PDF覆盖底稿。catalog缺失／未来格式不凭SHA/index重建旧UUID。

## 目标与兼容

- 新SourceScope.PDF_ORIGINAL，MapSource.documentId/pageId为catalog永久身份，普通region=null，originalPdf typed projection必填：sha256、pageIndex（仅完整性描述，实际索引按catalog pageUUID解析）、viewTurns、PDF user-space points矩形（null为整页）。其他scope禁止originalPdf。
- 当前目标或lastPreview.target含原文投影才写xmap v6；其他图保持v3/v4/v5。v1—5在实际跳读前从exact raw source/lastPreview.target路径拒新scope／非空投影，旧includeAnnotations规则不变。目标相等、source编辑语义、derived历史、预览digest包含完整投影，不把旧图冒称新原文。
- 原文preview rawVersion使用PDF SHA、renderedVersion使用明确原文语义digest（catalog身份／页／SHA／turn／frame）；批注Note变化不使原文PNG过时。catalog删除显示TRASHED，缺失/坏资料不可用，缺页或映射不符不能猜别页；旧PNG保留并标历史。

## 原文捕获与平台边界

- 只读准备actual Doc/Page/原pdfFile、pdfKey、dpi、Rooms快照和固定region。捕获DTO成功签独立SourceCreationRequest后菜单才清理，之后不持UI截图current；模式选择或picker旧回调不能重绑新请求。
- 映射复用真实链：region完整包含在唯一Rooms.Tile.display且PDF可见区域内，撤该tile平移后乘72/dpi，再用实际PdfSource.pageGeometry(pdfKey).toUser。该几何包含原Rotate/CropBox和pdfTurn；不再次撤视口旋转，不用sourceWidth比例，也不取Rooms.sourceOf的union猜范围。
- 原文分支不flush／保存Note，不需给Note补页ID；仅原件保留动作产生catalog身份。准备前后核原owner/model/page/pdf路径／映射，失败保原图／选区，无内容undo。已成功保留是独立成果；后续取消加图不删除它。
- OriginalPdfEngine接口仅暴露pageCount/pageInfo/original renderRegion和closeAndAwait；pageInfo须包含实际PDF的sizePts/displayBounds与可逆geometry，不能取Note尺寸代替；生产适配PdfSource(filter NONE, empty markups)。内部可注入受控engine测契约，不能把JVM fake渲染称实际PDFium通过。
- OriginalPdfAccess/candidate/read持PdfResources已验证lease。关闭先停generation、停止／join所有render/cache/text任务，再等PDFium实际native close完成，最后释放lease。PdfSource.close/PdfiumDocument.close旧非阻塞行为保持，新增等待接口须幂等。

## 独立阅读宿主

- 复用CanvasView／CanvasState/PdfSource绘制，专用只读pan/scale手势；不装可编辑InteractionController、IME、笔工具、GlWetPad或FrontInk。临时projection只有原PDF页／geometry，items/markups/flow/template/rooms为空，path=null，不进Editor/codec/autosave/Recent。
- 原文呈现是exclusive host（原文→map→native），不让原native Surface挂在下面再盖层。Entry/VM只保纯目标／原件holder／runtime camera，不保Activity/View；composition绑独立host token，旧view cleanup/完成不得关新host。
- 复用source ticket/bookmark／generation和native fresh停车证明；失败保map。打开/返回final同Main核实际entry/host/票/lease，不能伪造NativeReferenceResult.Opened(oldPane)。返回同map不触发停放Note persist，Back优先返回图。原件库直接打开也须同独占原文宿主，返回原列表／原native工作区，不丢原Map返回链。

## 交付与验证

真正纵向流程：模式选择→实际保留与同SHA去重→已有／新图追加→保存重开／PNG→只读原文→原图；独立原件库list/open/delete/explicit restore。验证跨两Note同SHA同原文身份、取消加图／删除两Note／重启仍打开、事务各故障点回滚／升级保旧数据、delete/restore ABA、wrongSHA/pageCount/corrupt/future拒绝、四旋转和非零CropBox／DPI／Rooms边界、原件零写、脏native往返零保存、长render/close期间GC保护、迟到callback及配置换host清理。

root：规格/审核、Main独占分派／原件库管理UI、格式label消费者和真实串联Host、统一Gradle／证据／本地提交。
A：DocumentRegistry/catalog/access/engine接口、xmap v6模型与preview/digest、交易／格式／preview测试（1→3逐表保留、真实2→3及future4拒绝）。
B：原文reader/Workspace与Navigator、PdfSource/Pdfium关闭等待、停车与lease/输入Host测试。
C：actual映射、模式选择SourceCreationRequest／PageToMapFlow和Editor绑定、坐标／源创建与Android菜单事件测试。
文件/API先对齐、作者不各自构建或提交。独立reviewer只读审核，不派生代理。真实Android/PDFium像素/SAF/Compose重建/MovinkPad Android15 ProPen3/Surface/GPU/性能未运行必须单列；旧有效未变证据不重跑。

## 实现与验证

源码已接：独立catalog/严格pin事务、原文v6投影与派生预览、actual整页/区域选择、纯原文reader/返回、原件库管理及显式同SHA重新关联。原文创建后打开导图使用本次request显式停车策略，不额外persist停放Note；普通NOTE默认策略不变。完整引用备份与网络筛选中的原文类型整合仍属后续完整需求，不能把原文当成某份Note或从SHA重建永久身份。

49373初步源码编译通过，后续源码改变不将其作最终证据。45983临时只恢复旧IO外取得ownership，两取消case均在IO成功、lease=1后等不到实际close而真实失败；shell退出逐字节恢复fixed。85330首轮180项178过／2新夹具失败，1157输入全同、Android源码compile通过。两夹具仅改对照：摘要保同图/节点/来源ID；delete→undo后不复活旧票，不要求重开来源条目，保当前用户结果并核旧票失效/新票有效。首轮日志及XML保留。

最终34437为 **31套185项，0失败/错误/跳过**，Android测试源码编译通过；1158源码/资源/构建输入逐一SHA相同。完整纵向case覆盖actual选区与整页mode→已有/新图/概念→失败重存不重复→实际原文backend固定PNG→保存冷重开→真实专用Reader→同MapEntry/相机/选集/来源返回，并调用Main实际persist消费者核脏Note零写。数据库测试包括真实1→3/2→3、raw SQL非整数/巨大值拒绝、active额外pin与tombstone残留pin全保护；取消反例fixed同名2项最终绿。另新增surface实际返回后取消回收、配置宿主间隙旧reader退休、双指同时平移缩放回归。

独立计划/实现/Required修复及证据复核完成：取消归属、SQL截断、坏首条frame整批污染、取消像素回收、own pinset、旧宿主与双指锚点、首次开图隐式flush均已处理。忽略证据：`tmp/original-pdf-{ownership-red,first,final}-summary.json`、相应XML/log和final-inputs；最新状态`tmp/original-pdf-runtime.json`。所有构建已终态，无运行作业。

主机PDF解析/像素使用明确注入的受控engine，实际SQLite、资源/租约、Editor、MapSession、IO、PNG编解码、Reader与消费者按生产流程验证；不据此宣称实际PDFium像素通过。Android原JSON空值/v6、双指MotionEvent及schema1/2迁移保留真实仪器测试源码，但本轮只编译。真实Android/SAF/Compose重建与进程死亡、MovinkPad Pro14/Android15/ProPen3、Surface/GPU/性能未运行；未打包APK、安装、推送、合并或发布。
