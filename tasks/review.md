
## B1a 原位／放大共享笔输入（2026-11-06）

独立审核代理：review_input_plan，只读审核，无子代理派生。计划与实现均经独立审核；全部必修问题已处理并回归，最后复核未发现仍需先处理的确定性错误。独立审核不代表目标设备验收。

- 计划：输入仲裁在 Detector 前，捕获节点／变换／工具／笔宽，保留旧 node-local 原点，专用节点笔迹提交保留并发元数据；取消、套索、越框、共享历史与返回语义均纳入。
- 发现并修复：选中手绘后工具栏增高取消首笔（固定工具行）；满采样容量无法擦除（内容预算与工具轨迹预算分开）；连续套索移动丢选区（提交后同步采用已接受内容，覆盖 Compose 延后回写）；逐帧全图几何重建（按不可变笔迹身份保留稳定缓存，仅淘汰临时预览）。
- 复核修复：放大层内独立错误覆盖，不改变输入区尺寸；手绘属性不自动整理；底图键盘／触摸与图工具在放大期间禁用；属性草稿只合并 text/kind/width/height，不覆盖后来来源、笔迹和位置。
- 证据：先行回归日志 tmp/inline-red.log、tmp/ink-review-red.log、tmp/deferred-ink-red.log 重现问题；最终 tmp/ink-stage-verified.log 全量 2757 项通过、Android 仪器测试源码编译通过。2 万采样背景的 8 帧临时预览只增加 8 次临时几何构造，原稳定笔迹复用；这不是硬件帧率保证。
- 未执行：真实 Compose 宿主仪器测试（已新增 MapInkEditingHostTest 并接入 PenTestRunner，仅编译）、真实设备与 SAF、GPU／延迟、完整无障碍和软键盘验收。文本原位与完整引用闭环仍未完成。

## B1b 文本原位计划审核（2026-11-06）

独立代理 review_input_plan 只读审阅计划和既有 TextEditorOverlay / TextEditHost / EditingField。计划通过，以下约束全部接受，尚未实施：优先复用现有组件而非第二条 BasicTextField；明确 viewport px 与未缩放内容 px 的单位，不重复 Density 转换；独立编辑事务 ID 和稳定 key，草稿与选择跟随事务保存；提交核对初始文本／类型且按当前宽度测高，只合并文本字段；输入超限在共享字段接受前明确拒绝，默认策略不影响笔记／画布；光标回报含 rotation 的宿主局部几何，宿主只在遮挡时平移相机，保留 NoBringIntoView；进入前取消原图输入，退出统一屏蔽迟到 IME 回调，待 IME/宿主尺寸稳定后恢复视口／选择；编辑期间工具栏压缩，避免键盘挤空图区域。

需要验证：中文组合输入、emoji、多行、空文本、超限、光标／手动拖动、键盘与横竖屏、多 density/fontScale/zoom、并发来源、旧文本冲突、取消零历史、完成一步撤销，以及旧笔记／画布默认不自动平移。真实 UI 与设备验证不能用模型测试替代。


## B1b 原位文本实现审核（2026-11-06）

独立代理 review_input_plan 只读审核实现与计划。确认的三项必修均修复：文本原位替代对话框后保留所有节点的属性入口；共享光标 effect 新增默认 0 的 caretGeometryRevision，图相机／宿主变化显式刷新；每个新 (View,token) 宿主只准备一次可读编辑比例，保持事务和最初返回快照。

主机回归新增 8 项事务与 5 项几何测试；最终 tmp/text-stage-verified.log 全量 2770 项通过，Android 仪器源码编译通过。新增 MapTextEditingHostTest 编译覆盖实际输入连接、resize 与 Activity 重建，但未在设备／模拟器执行。最终独立复核进行中，不将主机几何结果等同平台 IME 验收。

复核建议已补入仪器源码：resize 前等待相机稳定，验证实际字段回报的光标在新可视区；返回同时断言相机内容中心、比例和选择。新增内部几何诊断只保留字段最近的宿主像素回报。最后 tmp/text-host-compile-final.log 再次编译通过；这些仪器断言仍未运行。

## B2a 原始字节保真锚点计划审核（2026-11-07）

独立代理 review_input_plan 只读审核。已接受全部必修：持久版本标记与可信expectedAnchored状态（包括空画布）；拒绝重复反转义结构键／未来版本／错误容器；严格词法、token／总字节／深度／对象容量与取消；同一份预先校验的不可变快照做所有扫描；连续REPORT UTF8编解码与已消费字符Long计数。改用流式重封装，严禁只在中心目录替代manifest留下旧local记录；同时校验ZipFile与ZipInputStream的唯一条目及负载摘要。详见tasks/plan.md完整B2a计划。B2b模型/编解码/快照/存储接入与B2c后继另审；本步骤暂不触发实际打开文件升级。

B2a 实现独立审核发现并修复：前置ZIP摘要先于reader限制导致超大条目完整解压；改为中央目录和顺序视图均检查声明尺寸及实际字节，清单512MiB、marker/BundleIdentity各4096。重复键HashSet可持有大量字符串；增加短键及活动祖先累计预算，补嵌套超限和兄弟对象释放测试。超限拒绝与取消清理不放宽。孤立local测试首次因有ZIP注释而不能用ZipTail构造，保留其他带注释夹具，仅该反例用无注释容器，并先独立断言中心目录与顺序清单确有不同。tmp/anchor-bundle-green.log保留该夹具失败证据；tmp/anchor-bundle-fixed.log和tmp/anchor-budget-focused.log均通过。首次全量tmp/anchor-stage-full.log 2788项通过，最后身份条目限流补充后继续全量验证，不把旧结果冒充最后结果。

B2a 最终复核完成，身份条目同样前置限流的漏项已修复；独立代理未发现剩余确定性必修。tmp/anchor-stage-verified.log 全量2789项通过、Android仪器源码编译成功，diff检查通过。未运行仪器／设备／真实SAF；内部staging尚未接入口。

## B2b1 模型／编解码计划审核（2026-11-07）

独立代理review_input_plan只读审核，计划可行，所有契约建议接受并落tasks/plan.md：一次性身份原子与可见；按实际成员出现次数拒绝重复归属；snapshot冻结、deepCopy不继承／未知类型拒绝；writer显式参数且不分配身份；raw目标与全部物化对象对账、marker末尾追认、unknown/缺资产/空白自动补页不冒充完整anchored可写模型；失败只清理本read创建的临时文件，不碰受管／借入PDF。无marker有ID必须先原字节补标记；完全无ID旧文件继续兼容。B2b2打开／登记／SAF和B2c后继另审。


B2b1 实现复核完成：模型基本契约无必修；补竞争与线程异常收集及再次快照回归，修复旧 snapshot 重读实时图层。codec审核发现PDF原件未加载仍返回完整模型，以及Double近1版本舍入；均有RED重现并修复。新增独立 readInkCopy 先原ID映射对账再新身份，保留既有恢复入口与legacy模式，借入PDF不删。最后补引用式身份夹带另一PDF的真实反例，普通anchored read拒绝歧义包；独立针对复核认可。tmp/anchor-codec-final.log全量2808项通过、仪器源码编译成功。真实PNG／平台身份仪器测试已新增仅编译。没有未处理的确定性必修，B2b2与B2c仍未实现。

## B2b2a 存储计划审核（2026-11-07）

独立代理review_input_plan只读审核，全部要求接受并落tasks/plan.md：旧位置UUID变更不能静默重登记；覆盖BundlePdf/portable/newIdentity所有入口及同一快照；恢复在写入／清journal前检查契约；文件→DB→清pending可恢复顺序；in-place拒绝不能降级覆盖；初次保存外层截断前检查，空目标必须显式新建。Header需有界压缩、尺寸／CRC、absent/unsupported区分和从头回退。整包复制保留成员ID而改变doc UUID的作用域定义经认可。实施分a1格式层与a2登记／恢复／UI保存保护，所有必修继续保留；活模型绑定另审。

B2b2a1 helper局部实现复核：修复File/Channel容器校验差异、CD加密位、普通DD、per-entry ZIP64回退与暂存文件落盘摘要。tmp/bundle-header-review-red.log三项真实失败已修，tmp/bundle-header-boundaries.log 12项定向通过。独立局部复核未发现剩余确定性必修；未涉及writer，最新helper未做全量／Android验证。用户现已暂停，后续先按新渲染架构重新核对接口再续。

## 2026-11-08 R0 新渲染基线同步

独立review_input_plan只读复核：B1b文本、snapshot冻结、ink-only计数及分屏拒绝契约保留。唯一必修CanvasScene.WET_KEY缺旧ReferenceIdentity接口，主代理补空容器（不assign）后完整编译/JVM重跑通过2964项。页面级计划接受其意见：计数独立于身份、整包副本换页面ID、对象数不占页面限额、来源范围同步及坏对象多页/pending测试。实际Android/真机未运行。

## 2026-11-08 R1a 页面级身份

review_input_plan只读复核：发现抢救未知layer仍整份失败及提前过滤即时对象破坏pending索引两项必修。分别新增红灯回归tmp/page-bad-layer-red.log与tmp/page-pending-order-red.log；修为抢救时略去坏层对象、pending完成后统一过滤、Glue限定最终成员。最终复核确认解决，无新确定性阻断。主代理执行全量274套件2971项、Android源码编译通过（tmp/page-reference-full.log）。真实Android/SAF/设备未运行，整包副本新页面ID未纳入本片。

## 2026-11-08 R1c 来源范围

计划与代码独立复核均完成。接受意见：严格识别旧字段真实类型、补PAGE hint及两字段各自缺失组合、canonicalUUID/FILE越界、完整多来源与平台NULL。生产diff与测试无阻断；tmp/page-source-format-full.log全量2977项及Android源码编译通过，仪器未实际执行。页面归属/存在性及导航不纳入此格式层结论。

## 2026-11-08 R1b0 原字节页面ID重铸

独立计划及源码复核完成，无阻断；接受完整字面量位置、输出ID集合、新旧集合不相交、编辑范围预检、skip中取消/所有权和生成阶段取消建议。新增7项行为/字节回归，红灯日志page-copy-literals-red与page-copy-generation-red，最后全量2984项与Android源码编译通过。仅格式工具，整包writer/marker/Snapshot接入未完成。

R1b0尾部生成取消guard经独立复核确认正确。R1b1整包writer计划复核的sameUUID完整页验证、PDF prepare原身份核对、全链路取消与File/Stream证据边界意见全部接受，已写入继续规格，生产接线未开始。

## 2026-11-08 R1b1 整包复制/导出

按已有审核规格实施，review_input_plan独立只读复核发现Header回退限流过晚、锚定external+embedded歧义及legacy大前缀取消，全部写反例后修正。主代理又补源记录指纹不可随target参数/文件替换的反例并修正。最终复核无新确定性阻断，JVM2999项/Android源码编译通过；未执行SAF/实际仪器/设备。活模型UUID切换与R2保护尚未交付，原始整包路径的完成不扩大为这些范围已完成。

## R2a登记与header契约

review_input_plan计划意见：必须有按UUID的独立契约查询，verifyWrite必须先验证current，均接受。最终schema/事务/查询/guard/三语言复核无先修项；主代理full 280套件3011项和Android源码编译通过。实际SQLite仪器未运行，生产保护接线未完成。

## R2b0 发布与恢复事务

review_input_plan 独立复核发现候选损坏阻止恢复、读取失败混淆缺失、已解析记录与摘要竞争、瞬时候选IO误判和provider根回退，均补反例修正。最终无新阻断；全量282套件3034项和Android源码编译通过。实际SAF/仪器/设备未运行。生产ReferenceContract、登记、PDF持久pin仍需R2b1接线。

## R2b1a 来源身份读取

独立计划复核要求pending先拒绝自动发布、快读/snapshot变化拒绝、本地化错误；实施复核发现仪器Runner未注册、真实协程取消遗漏、外层catch吞取消及guard后取消窗口，均修复并补反例。最终复核无剩余阻断，284套件3051项/Android源码编译通过；仪器/SAF/目标设备未运行。来源读取不等于保存保护或正页契约激活完成。

## R2b1b 普通保存与配套恢复

计划/代码独立复核要求真实target拼接先保护原件（本片撤除）、kind绑定、已有anchored正登记、move前重核、migration pin时序及所有权唯一、同record目的、当前B保护、legacy容量不退化、Editor取消dirty/finally、历史stage SHA可信证明及PDF恢复例外。反例逐项补齐，旧v1恢复candidate仅call-time证明明确；native keep无可靠SHA故意保留证据。最终修复available PDF仍先pin与在途保存dirty=false不早关画布，窄复核无新阻断。288套件3086项与Android源码编译通过，实际仪器/SAF/目标设备未运行。完整边界reference-native-save-2026-11-08.md；pending下当前模型协调/另存/激活/来源闭环未完成。

## R2b2a 模型复制上下文

计划独立审核通过，精确父scope/owner、先全量校验再采用、已显式scope的UUID错配拒绝全部采纳。源码复核发现空ReferenceIdentity容器共享会使第二次restore失败并留下部分采用，两个反例先失败后修，增加容器IdentityHashMap预检；最终窄复核无未处理点。新增20项，289套件3106项全通过/Android源码编译成功。旧生产SaveAs/fork/merge/session不在此片完成范围，平台/真机未运行。

## R2b2b0 共用会话事务组件

独立计划审核通过，要求已观察head证据与回调票据明确。源码复核发现headTemp未重验、合法foreign head被当基准、陌生head误授权GC及UTF8替换，均红灯证明后修复；主代理另补current body损坏时不清理旧read body。最终窄复核确认GC/lease/短锁修复无剩余阻断。27项新增，290套件3133项全量/Android源码编译成功，2项新真实平台测试只编译未运行。b1生产适配器/Editor、b2native来源凭据仍未完成。

## R2b2b1a 正文版本

独立计划/源码复核通过；保留owner标dirty契约、同值identity/marker不计数，快照最后复制版本/归属，画布lastView后续放cache metadata。9项新增，291套件3142项/Android源码编译成功，未实际运行平台测试；未接SessionStore，不宣称恢复闭环完成。

## R2b2b2a 原生来源版本

计划/源码独立复核完成：受控签发/Inspected私有证明对、retained candidate receipt、早期资源保护、admit/handoff同gate。发现codec回调关闭ZIP、Header fallback长校验未lease，均红灯后修。主代理再补实际模型PDF SHA、v1 unused字段、hint后失败类型化/释放。最终292套件3163项/Android源码编译成功（3新平台测试仅编译），独立窄复核无剩余阻断；UI/Session尚未接。详细reference-native-source-version-2026-11-08.md。

## 原生交接 v4 拒写保护（2026-11-08）

review_input_plan 独立计划与源码复核通过：仅canonical UUID+SAVE+预检A/B摘要可stage v4；任何callback/target读取前默认拒绝publish，重启/别名不绕过；typed restore/keep仅暴露ID数据，坏record不降级。完整300套件3293项与Android源码编译通过，新增owned provider场景只编译未执行。无ack/生产切换/渲染改动；后续prepare/commit与活动内容事务屏障另审。

## 原生两阶段保存证明（2026-11-08）

review_input_plan独立计划/源码复核：绑定storage issuer、canonical journal、实际A/B与本次record；取消/epoch贯穿写前、读回、登记，已消费stage不补发Receipt。发现stage后读取替换marker签错证明、retained散列漏core取消，两个反例先红后绿。窄复核确认stage直接返回写入字节SHA、callback前CAS、v4 handle额外拒写、逐块检查与PDF pin间隙闭合。最终301套件3312项和Android源码编译全过；真实provider新增例只编译，目标设备未运行。完整cache/ack/稳定内容capture仍后续。

## 固定writer视图（2026-11-08）

review_input_plan独立审核确认Samples前缀可共享，要求复制嵌套容器、按页scope胶合、Canvas六类拒绝Text，均落实。源码复核发现FlowTable widths/minHeights别名，真实ZIP flow反例先红后绿，固定路径按table identity脱离且cell共享保留；默认flow不改。全部serialized字段、层/页ID/PDF及NOTE/CANVAS native/cache反例复核无新必修。最终302套件3326项与Android源码编译全过。只读视口capture额外O(items)分配可留下一片但生产激活前必须解决，活动事务稳定屏障及真机性能仍未验收。

## 轻量来源观察与按需正文（2026-11-08）

review_input_plan独立计划/源码复核通过：actual source/model observation私有工厂核对epoch，IO不读活模型、不颁native写权；NOTE准备身份再冻结key，reserve后仅requiresBody复制固定模型并复核key/current，view-only null body由encoder守卫与cache复用合同保证。错owner/path、head失败保留、同SHA accept废票等覆盖准确。最终302套件3329项与Android源码编译全过；未做仪器/真机性能，活动内容事务屏障与生产仍后续。
