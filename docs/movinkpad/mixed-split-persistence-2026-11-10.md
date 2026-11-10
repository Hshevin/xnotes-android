# 导图与笔记混合分屏的持久化

状态：in_progress；完整思维导图目标 active，overall complete=false。

## 工作树与范围

2026-11-10 用户明确要求基于当前工作树新建后继续。从干净提交
`d706ebd1104e34005bcb45f444243429f8551557` 创建
`<工作树 mind-map-mixed-persistence>/xnotes`，分支
`codex/mind-map-mixed-persistence`。原 pair-open 工作树保留。
仅实现、相关编译与测试、文档及本地提交；不打包、安装、推送、合并或发布。

## S1：typed 安排文件与存储边界（verified，独立复核 PASS，已本地提交）

输入：现有 v1—2 NativeSplitOpenParser、私有 splits 目录与 saved-split.json。
输出：可独立验证的纯数据契约及受限存储组件；本片不改变现有用户入口，
不宣称已经支持混合安排保存、打开或进程恢复。后续 S2 才接实际 owner 保存、
两个候选同步采用与 Main 入口，S3 接冷启动工作副本恢复。

- 统一请求按版本显式分派：v1—2 委托原 Native parser；v3 必须有
  `format=xnotes-split`，明确恰好一个 `map` 和一个 `note`，每份来源包含
  实际文档 UUID、URI 和可选标题。未知版本、类型、重复角色、损坏字段不得降级。
- v3 仅存纯描述：逻辑 mapPane/focusedPane、物理第一份比例、方向和反转，
  Native 阅读位置与 overrides、可选 Map viewport/尺寸/density。
  ID、URI、视口或描述本身均不授予读写来源、清恢复资料或采用模型的权限。
  不存 Editor/View/owner/运行时票，也不将保存的 SHA 冒充正文基线。
- 严格 UTF-8、有界读取（与旧 parser 同为 1 MiB）、有限数与合法整数、
  canonical UUID、布尔不接受字符串；v3 view overrides 拒非法类型/范围。
  必需几何与位置字段须完整，不静默把坏字段恢复为默认；不可保存 nativeHome。
  JSON 容器不向请求暴露，构造后的数据不随原 JSON 改动。
- 私有显式文件仅接受 `files/splits/*.xsplit` 的 canonical 直接子文件；
  不读取任意 content/file URI、不跟随逃出目录的符号链接；recent 只读固定
  saved-split.json，只有该兼容入口接受旧无 format v1。
- 新 mixed 文件不复用按路径“已保存”早退，不覆盖任意既有文件。暂存使用
  同目录独占唯一名称，写完并 fd.sync 后，以 Files.createLink 原子 create-new
  发布完整文件，不先暴露空目标，也不降级普通复制/覆盖。link 成功是提交点，
  此后清暂存失败不能删除 final 或报告未发布，只保留本次清理问题。
  并发 store 实例不互相清理暂存。失败仅清本次持有的文件；损坏/未来版本
  的既有文件原样保留。名称只作为显示提示，最终目标保持目录边界。
- S1 的 recent 只读，不更新它。S2 在与现有 Native writer 统一协调后再接
  checked recent 更新，并分别报告显式文件成功与 recent 失败，后者不得把已
  保存显式文件报成丢失。此阶段不写 cold last-presentation，也不把 recent 当最后进程布局。
  发布/读取/删除均只处理安排 JSON，不访问 Map/NOTE/PDF/Session 正文。

验收：先对新契约运行 RED，再实现；检查 v1/v2 回归、v3 两种逻辑槽及所有
focus/反转组合 roundtrip、完整 overrides 与 camera、损坏/未来/超限/UTF-8、
冻结数据、目录穿越/符号链接/名称、并发保存、失败保留原件与 recent 只读。
typed 构造/编码亦核预算和几何；坏 v3 即使带齐旧字段也不降级；link前失败
保既有文件、link后清理失败仍已发布。相关 JVM 测试和 Android 测试源码编译，保存原始日志/XML/前后输入 SHA。
独立审源码与证据后本地提交。真实 Android/SAF/原子移动平台/目标设备未执行。

### 实际实现与验证

`ui/SplitOpenRequest.kt` 实现纯请求及按版本分派，`SplitArrangementStore.kt`
仅访问私有安排文件；没有改变 Main、Editor、来源或 Session 的生产行为。
独立 store 实例用原子 create-new 处理碰撞，不共用暂存文件；成功只清自己的
stage，清理失败保留成品与 stage。recent 不写，旧 Native writer 尚未统一。

- session11301：新 API scaffold 的首轮 RED，3 套26例14失败，1184项
  main/unit/resources/build 输入前后同 SHA；不是旧界面真实回归复现。
- session97062：首轮实现3套32例全过，Android 测试源码实际编译通过，1253
  项 Kotlin/Java/XML 及5份构建配置前后同 SHA。session11794 消除4处新测试的 nullable
  warning 后重跑存储整套13例通过，生产未变。
- 独审发现 Android JSONTokener 接受注释及未引号 token，旧深度 scanner 会被
  其中的引号蒙蔽。session25045 将检查函数仅暴露给测试，未改行为，直接预检
  1例真实 RED；首个注释 payload 命中失败，后两个 payload 在该次因首断言失败
  未执行。修复为标准字符串转义／literal 边界及数字词法检查后才交平台 parser，
  不 catch VM 错误兜底。Android 源码另保三 payload 的直接预检与解析拒绝测试。
- session24013：最终3整套33个唯一例全过（codec15、store13、legacy5），
  0失败／错误／跳过；Android 测试源码重新实际编译通过（新增5个方法未运行）。
  1253项输入前后同 SHA。最终结果整套覆盖前两轮，不叠加重复用例。

原始证据为 `tmp/mixed-persistence-{red,final,recheck,boundary-red,guard-final}`
各自 `.log`、`-results/`、`-inputs.json`、`-summary.json`；最终以 guard-final
为准。runner/init、实际命令、JDK21/SDK、隔离构建与项目缓存见 summary 及
`tmp/run-mixed-persistence-check.py`、`tmp/build-mixed-persistence.gradle`。
最终独审核对原始 XML 的33项集合、1253项当前 SHA 及独立库存、runner/init
与隔离命令全部一致，Required 已闭合；审计记录 `tmp/mixed-persistence-final-review.json`。
Android org.json、Android 私有文件系统硬链接、系统重建、SAF/PDFium、Surface/GPU
与 MovinkPad Android15 ProPen3 均未实际运行；无 APK／安装／推送／合并／发布。
JVM 临时目录的硬链接／并发／故障注入已实际运行，不等同 Android 验收。

代码与测试本地提交：`6726c8ec`（格式）、`8da30d1e`（存储）、
`19c17096`（Android 回归源码）；文档检查点随后提交。整体目标仍未完成。

## S2 与后续阶段

### S2：实际保存与显式打开（verified，独立源码与证据复核 PASS，已本地提交）

基线为 S1 干净提交 `a07fe380607d7c3b1cf7ee5174f8e65ffc29b5fa`。本片接
完整可见 Map+NOTE 的保存按钮，以及应用内 saved/recent 的实际打开；不改
loader、不写 cold last marker、不将配置 Hint 变成文件重开或原件写权限。
独立计划审核提出的两组要求（双来源成功 B 的最终实际证明、按 UUID 解析
移动后的唯一位置）与 recent claim 接受点、取消清理验收均已补齐并复审 PASS。
session44834 完成统一 RED：8 filters、59个唯一用例、34失败，1193项
main/unit/resources/build 输入前后同 SHA。新接口占位和默认旧路径适配器尚未
满足新行为，不将其冒称旧 Main UI 的真实错误。所有临时 stub 已替换，实际
保存与重开已接；下面记录验证与未验边界。证据 `tmp/mixed-save-red-*`。

1. Main 的四个安排前门只读一次，得到 `Absent/Invalid/Ready` 和冻结请求；
   Native 交现 coordinator 消费请求，不重新读安排文件。Mixed 交 openPair。
   坏 typed 不降 Native/普通 NOTE，不先退 Entry、不先 guard/save 旧文档。
2. Native 单候选新增 `NativeSplitSource(expectedId)` overload；初读实际 UUID
   在 decode/guard 前拒错，末读再核。Map 的保存 UUID 在初读、actor load 与
   最终 held.finish 均核，不按标题或 URI 推断。原双候选原子采用不变。
   Saved/recent 两角色先按 UUID 只读解析登记的唯一已知位置；无位置才沿用
   保存的 URI hint，再核 actual ID。重复位置/错 kind/Original 身份域不吞错
   回旧路径；本次解析后冻结目标，后续实际来源与登记票重核，不晚改绑。
3. 保存的 nativePosition 同 commit 应用；Root 按 cap.nativePane 映射 mapPane、
   Map/Native 角色焦点、reversed，物理第一份 ratio 不变。Map 普通相机只登记
   到 presentation 待稳定宿主恢复，关闭旧来源面板/旧 focus 票，不写内容历史。
   非 saved 请求继续旧默认布局与定位，不改变旧调用。
4. Native 保存准备固定 actual pane/owner/model/path/identity，沿显式保存语义
   提交该 owner 的文字/flow，笔/选择/样式/表格预览自然等待。必须已有实际
   NoteSourceObservation，同源严格写入后返回最新成功 publication 的 version
   与 pending-aware position、最终 current()；clean Session 无 observation 也
   拒，不能补读原件来给缓存背书。不取可能仍为 A 的 lastReadSourceVersion。
   已接受写 B 在取消后仍归原模型记录，晚到 C1 保 dirty 且不签成功票。
5. Root 仅接受当前 active 完整 mixed（非 Home/parked/Canvas/Original/network），
   等 Native 准备与 Map 本次 saveAttempt Written(latest)；共享 Map 也必须实际
   保存，不能沿退休逻辑免保存。绑定当前 Entry/lease/epoch/revision/布局代次与
   host；Map 最终 actual UUID/SHA/actor 票与 Native 模型/两 native job/Session
   job/两物理输入稳定票跨最后 IO。外部来源冲突拒发布，不以日志保留算成功。
   特别是 NoteSourceObservation 的 runtime matches 不能察觉同 UUID 的外部 C；
   Map 保存和 descriptor stage IO 后，Native cap 须再于实际 URI 锁内只读核
   实际 UUID/kind/canonical/SHA 与本次成功 B 完全相同，复用 checkedPagedSource
   的 registry/永久页契约/Original 身份域票。这些票跨随后 Map 最后 IO 到
   同步提交；外部 C 拒发布但不抹掉已接受 B 的 publication 记录。
   Map 的 Written 也须携带本次实际写入/读回成功的不可变 B SHA；在已有 URI
   锁内从实际 MapStore 保存结果取得，不在之后重编码模型猜 B。最终 Map
   held 的 actual SHA 必须等于该 B，不能在外部 C 到达后重新取 initial(C)
   来给仍为 B 的 actor 背书。
6. Store 增 prepared publication：IO 创建/写完/sync 独占 stage，返回只持文件的
   对象；Root 最后 Main 共同核票，再同栈 create-new commit。cleanup 后置。
   取消前不发布，成功后不回滚文件。S1 saveNamed 保兼容包装，原故障测试保留。
   顺序固定：stage 写完 → Native 最终实际重读 → Map held.finish 最终实际
   重读 → Main 共同 runtime/来源票 → createLink；最后 Main 栈不挂起。
   各 provider 在最后只读观察之后仍可能自行变化，沿既有外部修改边界处理，
   不宣称跨两 provider 的分布式原子快照。
7. recent 的 Native/typed 共用按 canonical filesDir 的串行 checked writer，
   操作在 Main 纯内存预约代次，IO 准备后通过同一原子代次门 claim 一次性
   commit permit，作为不可撤回的接受点。新预约废除尚未 claim 的旧票；已接受
   的旧提交按串行前缀完成，不能排到后发提交之后，也不在 claim 后因取消回滚。
   不要求 Main 为 fsync/move 等 IO 等锁，不声称预约可撤回已接受 syscall。
   迟到初读同样核代次，不把 hasSaved/state 覆盖回旧结果。替换原 Editor 的
   saveSplitWorkspace/saveCurrentSplit 两个 recent writer 与 hasSavedSplit 初读；
   不让两个 fixed .pending 文件竞争。坏/未来 recent 在显式更新前保留，读取
   不清除。普通布局 persist 不获覆盖坏/未来 recent 的权限，只有明确“保存
   分屏”发出的 explicit ticket 可更新；IO 最后核实际 recent，不能仅信旧内存
   state。不新增 cold 状态；可重复 recent 与最后进程呈现仍分开。
8. Main 保存结果区分：未发布；named+recent 都成功；named 已发布但 recent
   更新失败。第三种保文件并提示可从 Saved 打开。Backstage 采用统一 recent
   可用状态；两种成功打开只在同步采用后清首页/菜单并交接 key owner/focus。
   Native 仍走纯 persistNativeSplitPresentation；Mixed 新窄入口仅记阅读/工作
   副本，不用会 reset/copy 的旧 persist，不宣称工作副本 cold 已可用。

验收使用真实 MapDocuments/MapSession、Editor、provider 和协调器：坏 typed
与坏第二源在旧 save/guard 前拒；expected ID 初/末读错、模型 ABA、late host；
实际 Native 槽和保存槽相反的 ratio/focus/reversed/两阅读位置；retained 历史与
HOLD/正式工具；Native B 实际 version、C1 dirty/写失败/取消后 B 记录、未知
Session 基线拒；输入预览不自动确认；source 冲突、Map 实际 saveAttempt 结果；
prepared stage 与最后 proof 失效、recent 代次交错/失败保 named；Home/parked
保存拒；同 UUID 的外部 Native C 在 Map save/stage barrier 到达后被最后实际
重读拒绝（runtime 模型/dirty/jobs 故意不变），保 B 成功记录与原 C 文件；
同 UUID Map C 在本次 B publication 与 stage 之间到达，必须拒而不重绑 C；
两来源改名/移动走登记的唯一新位置，重复位置拒且不回旧 hint；prepared 结果
跨 IO→Main 取消须由 outer holder 释放未发布 stage，link 成功后取消/recent失败
保已发布事实；recent late init/claim前废票/claim后accepted-prefix均有测试；
Main Saved/recent 点击与配置保 actor 的 Android 测试源码保留。
先新增可观察 RED，再实施；相关 JVM/编译通过后独审和本地提交。未运行的
Android/SAF/系统/目标设备边界仍明确保留，不打包或安装。

#### S2 实际结果

Main 的完整混合保存、四类 saved/recent 前门、实际来源与位置恢复已接通。
Native 期待 UUID 初末核对、两侧按登记唯一位置解析、retained 工具/HOLD、
Map 实际本次保存 B 摘要与 Native 本次实际 publication、最终两源重核及
prepared 文件发布均有真实 Host 回归。clean 的本次验证可 read-back 相同 B
而不物理重写正文；无来源 observation 的 clean Session 仍拒签证明。
配置保留现有 Entry/actor 的路径未改；没有冷启动 marker 或新 Session producer。

- 81710：Backstage 参数作用域接线编译失败，0测试，1263项输入不变；
  将可用状态沿 ExplorerCalls 传入后修复，独立源审 Required 闭合。
- 22542：50整套385唯一例，382过、3失败；Android 测试源码实际编译通过，
  1263项 KT/Java/XML 及5份构建配置前后同 SHA。三处为夹具比较：独立读取的
  NativeSourceVersion 对象无值相等定义，改逐 kind/UUID/location/SHA 核对；
  macOS 的 `/var` 与 `/private/var` 同物理文件用 canonicalFile 比较。保留后续
  完整发布／重开、实际 B、输入及取消断言，未修改生产逻辑来规避这三项。
- 独立审核指出缺少可控的 IO→Main 取消交错验证。提取实际 Main 复用的
  SplitPublicationAttempt：IO 内先持 stage、同步 commit/publishedFile、finally
  NonCancellable 清自己的 stage。受控 caller dispatcher 在 IO 已完成但未恢复
  caller 时取消，运行同一生产 helper，证明无 named、本次 stage 清除、foreign
  stage 不动；另测一次性／关闭／已发布事实，不复制 Main 逻辑自证。
- 92280：最后5整套41例全过，Android 测试源码重新实际编译通过，1265项
  输入前后及当前 SHA 同。相对22542仅 Main、两处夹具与新 helper/test 变化；
  用最终4套39例替换旧39例，再加新 helper2例，有效 **51套387个唯一例，
  0失败／错误／跳过**。不叠加重复测试轮次。

原始 `tmp/mixed-save-{red,related,green,final}` 的日志/XML/输入/summary 全保留；
`related` 是0测试编译失败，`green` 是385/3，最终以整套替换汇总
`tmp/mixed-save-verified-summary.json`、`tmp/mixed-save-final-inputs-verified.json`
为准。runner/init 与构建/cache仍使用本工作树独立目录。新 Native Host15、
Mixed Host13、publication3、recent8、owner2、Map receipt3和正常相机回归都已
实际运行；Android 新增4个 Main 前门场景只源码编译，未运行仪器。
Android org.json／私有文件系统、真实 SAF／PDFium／系统重建／Surface/GPU／
MovinkPad Android15 ProPen3／性能均未验；JVM 本机 FS／硬链接／并发／受控
取消已实际执行。未打包、安装、推送、合并或发布。整体 active/complete=false。

独立复核从原始 XML 重建整套替换、逐项比对387例与1265项当前库存/SHA，
并核 runner/init、隔离命令、三个实际 compile 任务及三套资源：均一致；
新4键齐全、两份简中全文件键值相同。原源码 Required 与验证缺口全部闭合，
报告 `tmp/mixed-save-final-review.json`。本地提交分组保存，不推送或合并。

本地代码／测试提交：`1dc4a776`（publication owner）、`39ad1ced`（shared recent）、
`ca5eb88d`（Native 证明与位置）、`87a422db`（Map 实际 B receipt）、
`7e4d927d`（混合协调／身份／相机）、`2bb37d38`（Main／Backstage／资源与
Android 前门源码）；文档检查点随后提交。

S3：cold descriptor 与可重复 recent 分开；Native 缓存先 prepare，Map actor
就绪后再采用。旧 Session 无已核来源版本，不得自动拿当前原件 SHA 给缓存
正文背书，也不能打开原件替掉未保存缓存；必须保 sticky 未核源保护和独立
另存恢复路径，不启用半套 NativeWorkingSession producer。配置重建继续保留
现有 Entry/actor，不重开图文件。坏/错配恢复资料不清除。

Map+Map/同图双视图、混合全局网络/原件库返回及其余 14 组需求持续必需。
