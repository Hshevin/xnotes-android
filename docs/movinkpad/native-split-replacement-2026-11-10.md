# 原生分屏替换工作区（2026-11-10）

状态：verified（源码／相关主机验证及 Android 测试源码编译）；独立计划、实现与最终证据审核 PASS，无剩余 Required。基线 `327463b96756eb1e31a68e8b0b4750315ca2a51d`，当前工作树／分支沿用 mind-map-pair-open。本轮上阶段为 progress（已实现、验证并提交 Map＋NOTE 双选），不是等待或无进展；全部已知编译句柄已终态，Git 干净。完整14组仍 active，overall complete=false。

## 行为与边界

已保存的 Native `.xsplit`、恢复最近保存的分屏、显式双选两个 NOTE／批注 PDF，可替换单图、Map＋NOTE、MapHome、停放混合布局，也可从普通 Native／首页进入。两份实际来源和所有将退出的拥有者准备成功后，同一 Main 调用栈采用两侧 Native、布局、焦点并退休旧 Map 路由／租约，成功后才清菜单／槽首页。读第二份失败、来源身份变化、输入未稳定、保存失败、取消或旧宿主结果均保原工作区。退出 owner 已接受的文字 blur／保存保留，不回滚；两份初读前不触发 blur。

显式打开遇缺件／坏文件不再以先清旧文档再放 Missing 占位当成功；保原工作区并提示。启动恢复 `restoreSplitWorkspace`／原 Session 的 Missing 占位、身份恢复和 Canvas 仅主会话规则不变。Canvas 始终拒绝进入分屏；typed Map／mixed `.xsplit`、Map＋Map／同图两视图、进程恢复和网络／原件库 mixed 返回继续后续实现，不以本片代替。

## 纯请求与 Native 候选

- 读取 `.xsplit` 保留现应用私有 `files/splits/` 边界及 1 MiB 有界读取；不按后缀把不支持位置降为 NOTE。v1—2 字段解析为不可变请求：两个来源的 URI／可选 expected UUID、比例／方向／反转／焦点和各自阅读位置／view overrides。有限值与 ID／格式／版本先校验，含 Canvas／未来 typed 模式在任何退出保护前拒绝。最近保存布局的旧 v1 缺 format 只保既有明确兼容，不让未来格式冒充。
- 请求绑定原选择、操作 ID、实际 host。已保存的安排按 ID 优先解析位置；已知重复不能吞异常后任选旧 path，expected UUID 不得由名称／页码替代。读取形成的安排是本次冻结请求；之后删改该安排文件不重新绑定正在执行的选择，也不写回它。两份实际文档最后仍重新核实。
- 新 `prepareNativePairOpen`／`prepareNativeSplitFile` 返回一个 `PreparedNativeSplitOpen`，公开两请求对应的实际 Pane、实际焦点、`keyHandoffs`、`current()`、一次性同步 `commit()` 和幂等挂起 `close()`。模型、候选资产和 source proof 私有，不交 Main／Map Entry。不能串联两个单候选 commit。
- 从上一片提取复用 NOTE 只读 source 检查／完整解码／资产保护和 A→B 真重读。initial／final 都核实际 NOTE、UUID／SHA、known kind、每个规范位置、URI／UUID 单调页契约、Original 保留域及当前结构票。不 register/ensure 来补身份；最终纯票覆盖后续调用者 IO，不授写权。两个 fresh 原始候选之间也直接核 UUID：同 UUID 不同规范位置拒绝；同规范 URI 只读／解码一份，建立实际 owner＋两个视图。
- 保留所有被选中的 actual owner 集合：模型、history、editSource、dirty 与正式工具不换。其全部物理视图文字／flow 只自然等／拒绝；只 guard 将退出且不在集合内的 owner。两份初读成功后必调用 guard（即使退出集合为空），供 Map 退出证明与 UI 原生保护接续。所有实际输入面、accepted session/note/canvas jobs、model／path／revision／输入代次在最后 IO 前后核；不能只凭 dirty 又 false。
- 共享附着不能原样调用 `openSharedView` 的 blur／清选区／copy 正式工具；只在已稳定的最终采用阶段连接相同模型/history/PDF 借用/session。已有 shared 两视图保原 owner；new peer 使用稳定内部接线。两个 distinct retained owner 不为逻辑前缀交换模型。
- saved 的 primary／second 是请求序号，映射到实际 owner 所在逻辑槽；按映射调整 reversed 和 focusedPane，ratio 始终物理第一份额不盲目取反。saved 各自 zoom／scroll／view overrides 成功后作用到对应视图，不进内容历史；explicit pair 没指定阅读位置就保已有位置。正式工具不为布局统一而覆盖。
- 每个资源有 outer holder，取消返回 dispatch 不泄漏；未采用只清自己 decoder 资产／PDF handoff，已采用保模型资产。cleanup 在双采用后，迟到异常不回滚已发布布局，不清新请求的状态。

## Map 退休与 Main 接线

Root 的 `MapWorkspace.replaceWithNativeSplit` 保持旧 current／visited／layout 到 Native cap 最后准备完成。Native guard 在两份初读后调用 Root 的 `settleMaps()`：所有将退出的实际 Map actor 先纯输入检查，末份 lease 按实际 `saveAttempt` 结果保存，shared 只免保存不免输入；记录实际 session/document/revision/epoch/lease 及原 Map generation／layout 证明。之后再执行原生 guard、保存和最后两源 IO，最终 Main 同时重核 Map 和 Native。没有调用 Map settle 的候选不得采用。

所有业务 veto 在第一次 Native 替换前完成。成功同步采用 Native 两视图／布局后，Root 清本路由 Map pool／frames／layout／current 并释放自己的租约，不能设共享 Session.closing 或取消别的 owner 草稿／autosave；最后调用 Main 的成功回调。Entry 不保存 cap、Editor、Activity 或一次性 commit。采用与退休之间不挂起。

Main 所有文件／恢复／双选／槽首页入口统一类型分派；不预先 guardedAll 或 goHomeAll。复用 operation／guardTag 与允许自身 modal 的 host proof；取消／新选择／配置卸载退休旧工作。成功回调只做自己已被最终证明的菜单／homes／savedMapUri／导航清理、actual Native focus 和 key handoff，不再核旧模型而自废。按键在 cap 返回时捕获原 model token，成功后迁有效接收者；retained HOLD 按实际输入身份／有效顺序，不重放 SELECT／command，fresh 模型不接旧 press。Native/native 的继承后 UP 广播继续有效。

## 验收与执行

先写可运行 RED，明确区分旧行为真实失败与新 API 桩；之后实现、相关回归／Android 事件测试源码编译、独立审核与本地提交。

1. 两 fresh NOTE（含实际批注 PDF）、两种顺序、单／双 Native、单 Map／mixed／MapHome／parked，以及保存布局恢复方向／ratio／reverse／focused／各阅读位置。成功 callback 同栈能观察完整两个模型与 Map 已退休。
2. 第二 raw read／未来版本／坏 ZIP／缺件／wrong kind／expected UUID 不符／重复位置／两 fresh 同 UUID／Original 保留 ID／未知恢复，均不先清两侧、blur 或资产；非空来源不删除／覆盖。
3. one/two retained dirty actual owner、同源 shared owner／peer；history/editSource/内容／正式工具不变，explicit pair 原阅读位置，saved 指定视图准确；未结束的笔／文字／flow／选择事务不被取消或代提交。
4. old current/visited Map 最后 lease 的实际 Written/failed/Retained/shared 输入；失败保路由和恢复，其他 lease autosave 不被取消。两初读失败不保存旧 Map；guard Cancel 后保已接受 blur/save。
5. Native 保存 A→B 实际重解码、最后 IO 中 model 同 UUID ABA、revision/path/two jobs／registry/domain 变化、host／操作 ABA、新选择和旧 cleanup；不能混搭两份不同尝试。
6. 共享焦点／实际窗口键盘／HOLD、旧 token UP／repeat／新 DOWN、选择器固定身份及所有 Main 入口；instrumentation 只编译时明确未执行。

仅相关编译／测试／文档／本地提交；不打包 APK、安装、推送、合并或发布。旧有效证据不为安心重跑；新输入改变影响的验证才重跑，raw XML／日志／输入 SHA 保留，整 suite/case 合并去重。真实 Android／SAF／PDFium／Surface/GPU／MovinkPad Pro14 Android15 ProPen3／性能未运行时仍为待验。

## 实现与验证记录

所有显式 Native 两选、应用内 `.xsplit` 文件、最近保存分屏已统一先准备后采用；Main 根／槽首页均显式分派，不把 `.xsplit` 当 NOTE，不再经过旧逐侧 `openInto` 或先清空工作区的路径。MapHome／parked／visited pool 的退出、other owner leases 和取消收尾由 MapWorkspace 统一协调。普通启动恢复的 `restoreSplitWorkspace` 未改变。

Root 复用同一 Map 退休证明，Native 两份初读后才 mandatory settle。新请求先等待本工作区所有尚未完成的旧打开 cleanup（包括已经 cancel、work 字段为空的旧 job），再准备新候选。普通未采用 Map 打开只归还自己的 unadopted lease，保最后恢复记录且不取消其他 owner autosave。状态提示使用分屏专用文字。

两个原生来源按实际 canonical key 去重；同 URI 共用一次初读／末读与单模型，跨 canonical 同 UUID 直接拒绝。共享 peer 的附着使用已稳定内部接线，fresh B/B 必须重挂两侧，不能沿用旧 A/A 的共享关系。保留 owner／既有 peer 的正式工具、历史和选择；只在实际输入身份下继承有效 HOLD，普通原松键仍广播到 Native 两侧。新换模型的 receiver 若有活动录音（含 Infinite owner）则拒绝采用并保原会话，避免 Discard 后迟到录音插入新文档；换模型前关闭旧播放，不干预 retained owner。

成功收尾使用 `persistNativeSplitPresentation()`：只写阅读位置／旧工作会话及分屏布局，不 blur／flush／reset 按键／复制工具，不启用未完成的新 producer。未挂载时的 pending Restore 只作为元数据／布局快照来源，不移动 UI 相机或写内容历史。公开旧入口与 Main 均使用该纯收尾。

- 2563：旧真实 public `openSplitFile` RED，1 项断言失败，坏第二份竟返回 true 并先退出旧模型；1176 scoped 输入前后同 SHA。不是新 API 缺失编译或桩。
- 33343：41 套 298 项全部通过，Android SOURCE compile 实际执行成功；1248 输入前后同 SHA。
- 独立实现审核发现 shared peer 的 HOLD、录音归属、公开 wrapper 的副作用 persist；Root 另核 A/A→fresh B/B 误 keepPeer。94778 用未修生产实跑 5 项，4 项分别精确命中以上缺陷；两侧静态选择回归直接通过，原 reset 后段已清理选区／UI，因此该怀疑按误报闭合，没有增加清选区副作用。1248 输入同 SHA，生产与33343完全同，只有2个测试文件变。
- 25112：最后9整套85项全部通过，Android SOURCE compile 实际执行成功，1248输入前后且当前逐项同SHA。按整suite替换原同9套80项，最终有效 **41套303唯一主机项，0失败／错误／跳过**，不叠加重跑或RED。新增31项主机方法、2项Android场景源码，并更新已有Main保存／恢复入口场景；Android均未实际执行。

最后相对33343只变MainActivity、Editor两个生产文件及MainPairKeyOwnerHostTest、PreparedNativeSplitOpenHostTest两个测试文件，资源／Android源码／构建配置未变。独立从原始XML重建有效303项并核完整1248文件库存与SHA，结果一致。三套mixed_split.xml各12键，中英键一致，两份中文内容相同。

证据在忽略目录：`tmp/native-split-{red,related,boundary-red,final}-inputs.json`、`-summary.json`、`-results/`、各同前缀`.log`；合并 `tmp/native-split-verified-summary.json` 与 `tmp/native-split-final-inputs-verified.json`，恢复状态 `tmp/native-split-replacement-runtime.json`。输入范围是app/src Kotlin／Java／XML与5构建配置，不包含C/C++、assets、PNG或CSV。构建与项目缓存为独立 `/private/tmp/xnotes-mind-map-native-split-replacement-build`／`-cache`。

真实Android／DocumentsUI／SAF／配置重建及杀进程／PDFium像素／录音设备／Surface／GPU／MovinkPad Pro14 Android15 ProPen3／性能均未运行。本片没有APK／安装／推送／合并／发布。下一 typed mixed `.xsplit`／进程恢复与 Map+Map／同图两视图、网络／原件库 mixed 返回继续实施；其他完整14组必需项不缩减，不标整体 complete。

本地提交：`06589e1b`（Native 双候选／有界布局解析／共享与纯持久化及测试）、`56855a89`（Map pool 退出证明／同步替换及测试）、`62ff28bd`（Main／槽首页／恢复入口、按键与 Android 源码）。文档检查点随后单独提交；完整项目继续 active。
