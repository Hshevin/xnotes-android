# 导图与分页文档：双文件打开（2026-11-10）

状态：verified（源码／相关主机验证及 Android 测试源码编译；平台与设备未验）；独立实现及最终证据审核 PASS，无剩余 Required。独立计划审核 PASS（初读／blur边界、selected共享Map输入两项 Required 已闭合）；基线 0f2ac93244911be1a6c913fa3704cd7fa146fcdc。工作树 `/Users/kita/.codex/worktrees/mind-map-pair-open/xnotes`，分支 `codex/mind-map-pair-open`。从上一工作树的完整已提交成果新建，旧树保持不变。完整14组需求仍 active；本片只实现恰一 Map 与一 NOTE（含批注 PDF 笔记）的一次双文件打开。

## 可观察结果

首页现有双文件选择入口接受两种选择顺序。第一份物理上在前并获得焦点，初始比例一半；真实 Native save owner 决定逻辑槽，以 reversed 保选择顺序，不为排版复制模型。两份文件全部准备、核实与旧拥有者保存保护成功后，在同一个 Main 调用栈采用两侧与布局。两份初读失败不触发旧 owner 的文字 blur。之后进入退出保护时，非 retained owner 沿现有明确打开／保存语义可提交 text/flow；若最后 IO 失败，保留其已成功提交的文字、历史与保存结果，不回滚或描述为完全未变。模型、布局不先替换；选择／样式／表格／Map 草稿只观察等待，不自动确认或取消。retained owner 的所有视图文字与 flow 也只自然等待／拒绝，不走默认 blur guard。

从首页、单 Native、Native 双窗格、单 Map、混合布局及其槽内首页／停放布局都可进入同一流程。Canvas 明确拒绝；Map+Map、混合 .xsplit、已保存 Native split 替换混合、进程恢复在后续小片继续，不以本片完成替代完整双窗格。

## 准备、最终核验与采用

1. 调用者保留选择器请求、实际宿主 token、旧工作区代次；新选择或宿主解绑退休旧请求。选出的 URI/顺序固定，不在异步结果返回时改绑。所有失败入口给出可见反馈。
2. Map 使用 MapDocuments 的规范 URI 唯一 actor。取得 lease 前先只读初始原始 JSON 及身份域；核实后取得临时 lease 并等待实际加载，再开始 Native 准备。优先复用当前或 visited 的实际 Entry；同位置不能创建另一模型。新入口重建根导航，退休旧返回帧，目标 Entry 的历史／相机仍保留。
3. Map acquire 前只读实际 JSON 的 UUID／SHA，并先核 Original 保留身份、位置／类型／永久页契约和已知重复位置，防 actor 的派生发现先污染登记。已观察 actor 必须仍为同一 session／epoch；加载前后也固定实际 epoch。fresh 或当时仍在加载的 actor 必须以 MapStore.open 本次真实 remote SHA（不是恢复 writer expectedHash）证明实际载入的是初读版本；A→B→A 也拒绝。已有 loaded actor 保原模型／历史。之后核实际 UUID 与 actor ID、原始 SHA、所有实际恢复记录同 UUID（含不胜出的 nonpending checkpoint），并拒绝其他位置的 live 重复 UUID。最终在同一 Map URI 锁内重新读取原始 JSON／恢复记录，核 SHA 与身份，锁跨最后 IO 及同步 Main 采用。acquire/load 不在该锁内，避免自锁。恢复正文不冒充物理文件身份。已有身份域登记／页契约不得被 JSON 重分类。
4. Native staged 入口只公开真实 nativePane、current()、一次性同步 commit() 与幂等 close()；不向 Main 或 Map Entry 暴露候选模型或 PreparedNativeOpen。prepare 本身不替换旧模型／不关闭槽。
5. Fresh NOTE 先按实际 source 核 NOTE、已知 kind 只能为空／xnote、每个已知规范位置必须是目标、当前位置／UUID 的永久页契约及 Original 身份域，再完整读取和 PDF/资产保护；最后 source 再核且 identity/domain 纯票跨 Map 最后 IO 保留。随后沿现有固定链：等待所有将被替换的实际 owner 和 accepted jobs、最后实际来源版本检查。自身保存导致 A→B 时真正重新解码 B；最后模型／路径／revision／两个 job 身份与输入状态同步核验。不能只检查 dirty。仅在两份初读成功后进入退出保护，沿用非 retained owner 的正常文字 blur、保存／放弃语义；未确认选择／样式／表格预览不自动提交或取消，后续失败保已成功 blur/保存结果。
6. Retained NOTE 先按规范 URI 找实际 owner，不重读来替换其脏模型／历史／editSource。只读实际原件核 UUID、NOTE 类型及来源位置；完整输入屏障覆盖所有物理视图，保晚到内容。共享文档保实际 owner 与另一视图阅读位置，不调用会提交文字／清选区的 focusPane。其他旧 owner 仍经既有保存／放弃保护。
7. 选中的 incoming／reused／visited Map 在初准与最终双 commit 前都须固定实际 session/document/interactionEpoch 并核 canParkSourceNavigation；shared 也不免输入，别的 lease 正在 ink/text/connected/picker 时只自然等／拒绝，取消保草稿。两份准备均完成后，检查旧 Map 的实际 actor／document／revision／interactionEpoch／纯输入屏障与原宿主代次；退出的末份 Map 以实际 saveAttempt 结果确认，shared 只免保存、不免输入；选择中的 Map 不强制保存来换布局。其他 visited Entry 退出也不得丢最后 lease 的未确认操作。
8. 所有业务 veto 在第一次模型替换前完成。Main 最后同时检查 Map、Native 及调用者 proof，然后同步消费 Native，采用 Map Entry、混合布局、hint、焦点和返回帧，期间不挂起／不弹窗。Native finally 资源清理在双采用后进行。候选的 commit 不能存入 Entry.parkingCheck；保留字段只放纯可重验停车检查，且不得长期保留 Editor/Activity。
9. Native NOTE 的读取与最后版本核实沿存储锁和 owner/job 证明；Map 沿自身规范 URI 锁。不能承诺两个外部 SAF 文件拥有跨提供商原子快照；应用写入／输入在最终 Main proof 中复核，外部在最后只读观察之后的改写继续由各自严格保存冲突机制保护，不能据此放宽覆盖权限。
10. 候选与资源有明确所有者。未采用 Native 只清本次 decoder 资产与 handoff lease；已采用保模型资产。未采用 Map 使用 closeUnadoptedSource，只保自己最后 lease 的恢复，不取消别的 owner autosave。取消回调返回 dispatch 时也有 outer holder。采用后只做资源收尾，迟到失败不能回滚新布局或误退新请求。

## 输入交接与界面

保留 mixed 实际焦点、各自正式工具与 HOLD 状态。Map 原事件不经过旧 Native Samsung／四指／快捷 latch。共享 Native peer 退为 Map 时，按输入身份与有效 HOLD 顺序转交给保留 owner；Main 已接受的 KeyEvent release 仍抵达接管者，不依赖释放时的焦点。不能只为工具栏稳定而取消临时工具。

使用现有 SplitHost、movable MapScreen 与分屏栏；不新增遮住 on-top 湿墨 Surface 的盖板。新增用户文字在三套资源，中文两套完全相同，使用 token。配置重建旧回调失效，不恢复旧 Editor 闭包。

## 验收与证据

- 真实旧政策 RED：现有 openSplit 对 Map pair 明确 pending 的行为，与新成功断言形成失败；不是缺接口编译失败。
- Fresh 两种顺序、已有 Map／Native／mixed／Home 与 parked 入口；成功核实际两个模型、布局、焦点、读写/历史和新根返回语义。
- 选中 Map 另有 lease 且 ink/text 中，即使 Native ready 也不采用；取消保该草稿，结束输入后的新尝试成功。
- 真实两文件失败／无效JSON／错误 Native kind／未知格式／未来版本／重复UUID／损坏恢复；两份初读失败保持旧两侧、selection、revision与输入不变；退出保护后失败允许保留其已接受的文字 blur/保存，不能重置模型或布局。
- Retained dirty NOTE 与共享视图：同实际 owner/model/history/editSource；实际位置与 HOLD/KeyEvent UP 交接；两侧输入未稳定时延后或拒绝，不能清草稿。
- 补充三组：第二初读失败零 blur；退出 owner 已 blur 后最后 IO 失败保新文字及历史；retained text/flow 等待取消保原草稿与 caret。
- Native 读后旧 owner 保存 A→B、最终 IO 前后编辑/换模型/同UUID ABA、Map 最终 SHA／epoch变化、旧宿主/新选择/取消与迟到；所有失败只释放自己候选。最后成功双采用前没有任一旧侧被先替换。
- PDF handoff lease/未采用资产清理与 adopted 资产保留；failed Map lease 不取消其他 owner autosave。
- 相关单元／真实 Main Host 用例及 Android 事件测试源码编译，冻结输入与原始失败/成功 XML、日志分开保存；有效用例按suite/case去重，不把源码编译记成执行。
- 独立审核只新接口、业务失败边界与证据；必修项闭合后本地提交。真实 Android/SAF/DocumentsUI/PDFium/Surface/GPU/MovinkPad Pro14 Android15 ProPen3与性能未运行时明确待验，不以主机代替。

不打包 APK、不安装、不推送、不合并、不发布，不迁移未完成的 NativeWorkingSession producer 来扩大本片前置。

## 实现与验证记录

源码已接入首页双选的 Map＋NOTE（含批注 PDF）两种顺序及槽内首页，选择时固定 BrowseEntry 顺序与提示名称；实际类型和身份仍由原始内容核实。Native staged 持有自己的候选与资源，Map 最后规范 URI 锁跨 IO／Main 双采用；成功才改布局／菜单／焦点。实际模型、历史和正式工具保留，HOLD 及 accepted key 的原接收代次分别交接；新 repeat=0 建新 press，repeat>0 不改绑旧 press，legacy 松键沿原路线，旧 Native/native inherited HOLD 广播保留。

Map 的实际 View 焦点等待 attached／稳定尺寸／相机准备，在逻辑焦点或呈现代次改变时交接；不把每次重组当焦点请求。节点文字事务不抢焦点。Android 两场景含实际 Main／View、Native 先有焦点、Map-first 未触摸普通 DPAD 的断言，但本轮只编译这些源码，未执行 Android 事件；首页双选按钮、窗口焦点／IME和 Surface 实际效果仍待平台验收。

验证全部在本树独立 build/cache 下运行，没有 APK／安装／推送／合并／发布：

- 13810：RED 3 套 8 项，5 断言失败。B selector 是真实旧筛选政策；Native 新接口 null 和 Map 新接口 false 是可编译脚手架 RED，不称旧 Main 双文件流程已执行。
- 23348：仅暂退 pair 的 foreign journal 身份检查，真实 1 项失败，恢复完整修复；1175 scoped 输入前后同 SHA。
- 81576：仅暂去 fresh loadedRawSHA 证明，A→B→A 真实 1 项失败；恢复后保新字段只作读观察，不授写权；1175 scoped 输入同 SHA。
- 9429：38 套 270 项，269 通过、1 Native same-UUID 夹具失败；1242 输入同 SHA，Android SOURCE compile 成功。夹具原用异步 newNote 后立刻误取旧模型，改为同步实际 replaceDocument，同 UUID／新对象／候选失效断言保留；生产不因该夹具改动。
- 65470：暂去 acquire 前身份预检，真实原件保留 ID 的失败打开污染登记，新增 1 项实际失败；恢复后拒绝且记录／租约／原件不变，1175 输入同 SHA。
- 4728：旧 Native cap 允许 fresh Map 与 NOTE 同 UUID，新增 1 项实际双采用失败；随后加 Native 只读身份域／位置／kind 与最终票。1175 输入同 SHA。
- 32954：最后定向 7 整套 59 项，0 失败／错误／跳过，Android SOURCE compile 成功，1242 输入前后同 SHA且当前逐项相同。整套替换旧同 7 套 57 项，不叠加重跑，最终有效 **38 套 272 唯一项，0 失败／错误／跳过**。新增 33 项 Host／纯单元及 2 项 Android 源码。未变有效相关用例不为安心重复跑。

原始失败／成功 XML、日志、精确请求与输入摘要保存在忽略的 `tmp/map-pair-{red,recovery-red,load-red,related,domain-red,native-domain-red,final}-*` 及对应 `.log`。合并结果 `tmp/map-pair-verified-summary.json`、最终输入 `tmp/map-pair-final-inputs-verified.json`。输入 scope 为 app/src 的 Kotlin／Java／XML 与 5 个构建配置，不包含 C/C++、assets、PNG 或 CSV。三套 mixed_split.xml 的 10 个键一致，两个中文副本逐值相同。

真实 Android／DocumentsUI／SAF／配置重建及杀进程／PDFium像素／Surface／GPU／MovinkPad Pro14 Android15 ProPen3／性能均未运行。Native saved split 替换 mixed、typed mixed .xsplit 与进程恢复、Map+Map／同图两视图、全局网络／原件库 mixed 停车返回等后续仍属完整必需范围；完整14组 active，overall complete=false。

本地保存：`cfa5aa7c`（Native 分步候选与测试）、`55c3e0b2`（Map 来源核验／双采用与测试）、`15a9e971`（真实双选入口／输入焦点／资源与 Android 源码）。工作树迁移与计划先存为 `a5965470`。最终独立从原始 XML 重建有效 272 项并核全部 1242 输入 SHA，无遗漏或剩余 Required；文档检查点随后单独本地提交。完整项目保持 active。
