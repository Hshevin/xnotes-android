# 会话正文与身份配对（2026-11-08）

用户要求：在新渲染架构下继续思维导图与文件网络；副本页身份、异常退出恢复、保存失败与旧笔记兼容必须可靠，不打包 APK。
状态：R2b2b0 组件已实现；290 套件 / 3133 项 JVM 测试通过，Android 测试源码编译成功；独立代码复核完成。真实 Android/目标设备未运行。
本轮边界：源码、测试、文档、本地提交；不打包、安装、推送、合并或发布。

## 已检查的风险

现有 SessionStore 和 CanvasSessionStore 分别写正文和 metadata，正文替换失败还会尝试删除旧文件，缺少可检查结果；旧正文能被配上新的 URI/dirty/视口。Editor 在 IO 完成前推进 lastSessionContentVersion，失败后可能只刷新 metadata；多个后台请求无代次。画布还同步写活模型。现有 cache 读取没有 native 来源摘要，内部配对正确也不证明它可以覆盖外部同 UUID 的新版文件。

## R2b2b0：先建立两种文档共用的事务组件

增加内部 WorkingSessionCache，仅管理应用私有目录中的会话快照；本片不半接现有两种会话适配器/Editor，不把原子配对当作来源冲突已解决。b1 接实际入口与旧缓存，b2 接 native 来源凭据与恢复决策，三者均未取消。

组件为每个窗格目录提供同一协调器。模型拥有者先 reserve，得到不可变的请求票据（种类 note/canvas、文档 UUID、owner token、内容版本与单调代次），再冻结模型并排入 IO。reserve 不做正文 IO。只有最新票据可发布；清除也先 reserve，持久写 tombstone，迟到的旧正文不能复活缓存。一个窗格的当前模式由调用方明确提交一次，不让两个旧适配器分别猜测清除对方。

正文写成独立的不可变唯一文件；完整编码、fsync、调用方核验格式/UUID/页契约并计算 SHA-256 后，以一次原子替换发布小型 head。head 同时存格式版本、种类、文档 UUID、正文文件名/摘要与 metadata JSON；metadata 不再独立拥有身份。正文失败或 head 替换失败保留上次完整 head/body。只在最后确认票据、head 未被未知写入改变、候选仍是已校验内容后提交。不得删除旧 head 再尝试 rename。

view-only 复用仅在同一运行时 owner、同 kind/文档 UUID/内容版本的已提交正文上允许；否则票据要求新正文，包括上次写入失败的相同版本。重启后先写完整正文建立运行时证据，不按调用方一句 writeDocument=false 推断。复用也核验原正文摘要，损坏拒绝。并发旧任务即使编码完成，也不能覆盖最新请求。

load 返回已核验的 head 和正文读租约。正文按需读取，读租约释放前不能清理；提交后只清理本目录严格命名且不被 head/活动 writer/读租约拥有的旧 cache 文件。清理失败保留文件，不把已经提交的 head 报作失败。head 损坏/未来版本/正文缺失或摘要不符返回明确类型错误并保留证据，不 fallback 为无会话。head metadata 限 64 KiB；PDF/正文不套小文件上限。路径名仅接受组件生成的 UUID 文件名，不能借 head 指向别处。

本片保留调用方 body 校验回调，是编码器适配边界；事务组件本身不能凭 metadata 声称正文属于某 UUID。适配器在 b1 必须从候选完整 BundleReferenceInfo 核对类型/UUID/页契约。运行时页 scope 不需要写进原笔记格式；重启后没有旧撤销栈/排队快照，完整正文的当前页 UUID 建立新 root。b1/b2 必须阻止旧请求把祖先正文重新配到新文档身份。

### 验收

- 首次写/重开/廉价 metadata 更新；换 owner、UUID、类型、内容版本必须重新写正文。
- 正文/校验/head 替换/取消失败都保留旧 head/body；失败版本下一次仍要求正文。
- reserve 顺序而非 IO 完成顺序决定可提交任务；跨实例同目录共用代次；clear 后旧任务不能复活。
- 大正文 view-only 不重新编码/复制；只需校验摘要，不重复解码 PDF。
- 损坏/未知版本/缺失/路径越界明确报错，保留原文件；已有 head 不能被未知改动静默覆盖。
- 加载旧正文时新提交/清除不得删租约文件；释放后允许清理；清理失败不误报提交失败。
- 本片 JVM 文件系统与并发失败注入；实际 Android 私有存储原子性和进程中断仍需仪器验证。不得以 JVM 代替真机。

## 接续生产接线

b1 修改 SessionStore/CanvasSessionStore 与 Editor：主线程 reserve+冻结、IO publish、checked结果与正确 dirty、模式切换/关闭先使旧请求失效；旧固定文件缓存显式验证和保留，不能凭空 sidecar 默认值冒充有效身份。保留 view-only 场景但用票据与原正文 metadata，不从无关 dummy model 取得 URI/dirty。更新 SplitEditorTest 的实际 cache 探针，保留真正 Android 行为测试。

b2 给 cache 与 native 保存/打开提供同源摘要凭据和在途提交结果，避免同 UUID 的旧缓存覆盖较新文件；无法确认时保留未保存模型并走显式恢复或独立副本。恢复 UI/部分保存后放弃仍需用户答复的语义不在本片猜定。完成后接 R2b2c 的另存/fork/merge 与页来源激活。

## R2b2b0 实现与证据

`WorkingSessionCache` 已实现上述独立事务。reserve/clear 票据不碰磁盘，metadata 在 reserve 时冻结；返回 Publication 携带原票据，调用方须在回写“已缓存版本”前再检查 isCurrent。按规范目录复用弱持有协调器，票据/租约保活状态，不让同目录不同实例各自覆盖。

head 缺失与 tombstone 才视为空；已观察 head 的摘要独立于正文复用证明保存，读取/提交/清理遇到未知变动全部拒绝。首次进程观察只能检查当前 cache 内部配对，不构成 native 来源未变化的证据。损坏或未来格式不被普通保存或清除顺手覆盖，恢复/迁移 UI 由 b1/b2 后续接入。

head 候选在全部回调后、move 前重新核对 NOFOLLOW/64 KiB 上限/完整 bytes。正文校验、SHA 和目录扫描在锁外，小 head 复核与单次 unlink 在锁内；reserve 不等待正文编码。正文编码器关闭 ZIP 输出流不会提前关闭本组件拥有的 channel，完成后仍 fsync。当前正文出现损坏时也停止清理，以保留可能最后完好的旧租约正文。所有失败保留上次完整 head/body；清理失败不回滚已经提交结果。组件只采用文件 fsync 与平台 ATOMIC_MOVE，不声称已完成断电或 Android 文件系统验收。

新增 27 项 JVM 行为/失败/并发测试，覆盖两模式、跨实例/规范目录、不可变 metadata、失败版本重新编码、旧 IO 晚完成、validator 内新 reservation、独立线程 reserve 不等阻塞 validator、clear 失败、head/正文损坏/缺失/未知格式/非法 UTF-8/越界/符号链接、发布前和期间合法 foreign head、head 候选改写、GC 授权、读租约交错、2 MiB 正文只更新 metadata 和编码器关闭输出。

独立复核发现 headTemp 未绑定、发布前合法改动被当新基准、冲突清理误信陌生 head、非法 UTF-8 被替换；全部补红灯再修。主代理另补“新正文损坏后旧读租约不能被清理”的反例。红灯证据分别为 `tmp/session-cache-head-red.log`、`session-cache-baseline-red.log`（2 项失败）、`session-cache-gc-red.log`、`session-cache-corruption-red.log`（2 项失败），首次缺组件红灯为 `session-cache-red.log`。

最终完整验证 `tmp/session-cache-full.log`：290 套件 / 3133 项，失败/错误/跳过均 0；`:app:compileDebugAndroidTestKotlin` 成功。新增 2 项 Android 私有文件系统/平台 JSON NULL/原生 codec 测试并登记固定 runner，尚未实际运行。同时修正已有 anchored PNG 仪器 fixture，显式启用页编号后再断言 anchored，避免先前只编译未暴露的错误前置。未打包/安装，未执行真实仪器/SAF/MovinkPad/Pro Pen 3/GPU 验收。

本片尚未替换原 SessionStore/CanvasSessionStore/Editor；b1/b2 接线、legacy 缓存恢复、来源版本冲突和保存部分成功后放弃的用户决策均保留，整体功能未完成。

最终窄复核确认此前四项均已覆盖，未发现新的 GC/租约阻断；完整回归与仪器源码编译已完成。组件阶段验收通过，接续 b1 的实际入口改造。
