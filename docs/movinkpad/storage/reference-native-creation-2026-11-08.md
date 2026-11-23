# 原生新建目标与另存发布（2026-11-08）

用户要求：持续实现思维导图与文件关系网络，按新渲染接口复用原生编辑能力；另存副本使用新的文件/页面身份，不覆盖未知来源，不打包 APK。
状态：R2b2c0 存储创建接口已实现；293 套件 3191 项主机测试通过，Android 测试源码编译通过，独立复核通过；实际 Save-As 仍未改造，平台/目标设备未运行。
本轮边界：源码、测试、文档、本地提交；不打包、安装、推送、合并或发布。

## 已检查的实际调用

MainActivity 的 CreateDocument 回调先 openOutputStream(wt)，再调用 Editor.save；Editor.save 内部吞异常，外层onSuccess仍可能执行关窗/替换动作。Picker只记Editor窗格，不绑定当时模型；晚到结果可保存错误文档。save/fork/merge活模型改UUID不换页ID的问题，已具备PageReferenceCopy，但尚未接生产。创建必须有独立语义，不能把异常空文件当普通保存的“新文件”例外。

## R2b2c0：先建立可验收的新建存储上下文

新接口分两步：明确的新建/另存动作开始时签发一次性NativeCreationIntent（NOTE/CANVAS、新文档UUID、存储实例签发域），然后在创建回调收到URI时prepareNewTarget，校验同签发域/未消费、位置无已登记身份或pending、可读且确实空白。仅接受此种明确创建上下文；API文档要求在用户CreateDocument或应用自身createDocument动作中使用，绝不能从已有文件保存失败推断创建。实际UI后续还要绑定model owner/文档种类并拒绝迟到结果，存储层不代替UI证明用户动作。

准备后返回不可改写的目标permit：规范位置、种类、新UUID和空目标预期摘要。完成私有candidate编码后才publishCreated；再次核对空目标、无已有身份/pending、candidate完整格式/UUID/页契约，所有检查在target.write前。已知身份的空文件、非空文件、位置被替换或跨实例permit全部拒绝。首片不支持用“另存”替换已有非空文档；选择其他新文件即可，不自动覆盖。

发布仍使用MigrationPublication durable original/candidate：新增CREATE_NOTE/CREATE_CANVAS用途，与SAVE_*明确分开。original必须是本次明确创建的空目标；current只能是记录中的空原件或同份已验证candidate，目标出现未知变化不覆盖。PDF先保护candidate依赖，成功读回→note pin/登记→清journal→解除临时pin，返回实际staged candidate的NativeSourceVersion。写入/DB/取消失败保留恢复资料，不把model重绑定或SourceSnapshot当作已成功。

已有ordinary-save/ensure/restore/keep不得把CREATE记录当作SAVE或空ZIP“修好”。本片ensure遇CREATE继续提示恢复待处理；不自动清空、恢复空目标或采用不同model。提供显式完成已记录创建的存储重试（只读原journal的同一candidate SHA/UUID/kind，不接受新candidate参数；只在current匹配空原件或candidate时），仍不处理部分写入或foreign current；失败保留全部数据。部分写后“放弃”的产品语义尚待用户答复，不借此片决定回退或清除。

通用无用途legacy迁移API继续兼容历史记录；类型化v3记录不应通过旧三File/无purpose回调绕过Native约束（publish/restore/keep已知typed记录先拒绝，保留generic legacy兼容范围）。新purpose对旧版本读者应失败保留，不降级为空用途。generic keep对可识别数值版本>=3的未知/损坏record同样拒绝，不能由readRecord异常降成legacy；v1/v2及无法识别为typed的历史坏marker仍保留显式keep兼容。

intent在同签发域的首次prepare调用消费，permit在同签发域的首次publish调用消费（失败也不复用）；跨签发域拒绝不消费原令牌。prepare/publish均核对新UUID没有既存文档、单调页契约或任何位置证据。创建写后登记在同一SQLite事务中重新检查并登记。重试允许本candidate UUID已经恰在此规范URI登记，但拒绝他UUID、异处同UUID、kind或页契约不一致；不能套用首次“无登记”判定。

创建及重启完成创建均先通过有界Header/readWithPdfProtection取得临时PDF租约，再完整验证candidate、建立durable pending pin，最后释放租约。用fallback扫描中触发GC验证，不假定调用者持有model lease。marker版本分类与后续验证必须绑定同一份有界raw bytes/摘要，超大数值版本同样拒绝；不另读一遍推断用途。空目标证明仅覆盖规范位置和所见空字节，不能区分同URI下内容完全相同的另一空文件，不承诺这种物理替换检测。

### 验收

- intent/permit一次性及跨实例/URI别名、新UUID、支持类型；准备不打开目标写流。
- 非空/已登记但空白/既有pending/错误UUID-kind-marker/candidate损坏拒绝，目标字节与原来源不变，无新journal。
- 正常新建note/canvas保存与重开、正页契约登记、真实staged receipt；candidate调用者文件变化不影响回执。
- prepare后目标出现内容、stage后foreign变化、部分SAF失败、DB失败/显式无重复写重试，保护candidate/PDF与失败证据。
- CREATE不能被普通SAVE/ensure/restore/keep/旧空hook消费；版本未知目的拒绝且不删数据。
- 与PageReferenceCopy组合的新文档/页ID分离；本片不提前采用live模型或宣称主界面已安全。

## 接线前必须保留的会话约束

- 不能继续先开目标流；UI必须以model token+kind保存request，并仅在实际成功且仍属于该模型时执行pendingAfterSave。
- 原生保存与cache原本并发；cache若捕获旧base而native随后成功，正常暂停也会形成冲突。后续从native成功receipt更新源基线，再在主线程捕获当前live模型/视口并reserve，IO只编码快照；后续成功写也要使旧cache请求过时。失败则保留原base/dirty。
- 原生writer snapshot仍按现有契约共享item状态，不能用capture revision猜两份已序列化文档谁更新，不能据此自动合并/覆盖。来源SHA不匹配就保留两份并显式处理。
- Save-As B完成后，旧cache A不得恢复并自动写回原文件A；需要持久的文档选择/复制完成证据或等效绑定。只在内存更新URI不足以通过异常退出验收。安全记录必须不把A正文直接配上B身份，保留未应用工作副本。
- cache view-only不能每次复制PDF。初片采用portable正文时仅真实正文变化编码；若改内部引用式cache，必须复用现有资源pin/lease协议，并覆盖head切换与reader lease到PDF lease的交接，不先去掉嵌入原件。

以上接线、SourceBinding、旧cache恢复/冲突、fork/merge与页面来源闭环仍保留；c0只交付新建存储上下文，不虚称全部完成。

## 实现与验证结果

- NativeCreationIntent/Permit 为非 data class、私有签发域、一次性使用；prepare 只读空目标，publish 不提前打开输出流。DocumentRegistry.verifyCreation 包含不可用位置和单调页契约；registerCreated 在同一事务内复检并登记。
- MigrationPublication 增加 CREATE_NOTE/CREATE_CANVAS。stage 的 expectedCandidateHash 在复制到保留文件后、写 pending 前校验；publishCreated 传入首次完整校验的 SHA，防止校验后换候选在重启时获得新 UUID 的创建权。
- completeCreation 只消费记录中的同一候选，当前目标必须匹配空 original 或完整 candidate。DB 成功后读回失败可同身份重试，不重复写；异处身份、kind、页契约、部分写入或外来目标均拒绝并保留记录。没有恢复空原件或放弃语义的隐式决定。
- 发布器内部 protectCandidate 钩子在 retained 散列前取得仅用于依赖保护的租约，最终仍核验 record SHA/UUID/kind/PDF 依赖；租约持至发布完成。初次扫描也先取 Header hint 租约。取消/契约失败的清理错误保留为 suppressed；已确认提交后的租约清理失败记日志、保留保护，不反转成功。
- 严格 marker 入口最多读取 257 字节。generic publish/restore/keep 拒绝 typed 记录；unknown/损坏数值版本>=3（含溢出、超长前导零）不能被降成 legacy keep。仅显式非 typed 的历史坏记录可以继续同流散列；旧测试中“3\nfuture 可清除”的期望随 CREATE 引入明确收窄。

反例证据：tmp/native-creation-red.log（缺 API）；tmp/native-creation-review-red.log（PDF 保护时序、取消异常覆盖两项真实失败）；tmp/native-creation-stage-red.log（摘要与读取预算接口缺失）。修复后相关回归通过，最终 tmp/native-creation-final-full.log：293 套件 / 3191 项 / 0 failures/errors/skipped；:app:compileDebugAndroidTestKotlin 成功。

新增 NativeCreationTest 23 项、PublicationPurposeTest 5 项，保留既有普通保存/恢复回归。另有 NativeCreationAndroidTest 3 项真实 provider/原子目标/PFD/SQLite 合同用例，注册到 PenTestRunner；本轮仅编译，未执行。校验后 A→B 换候选反例直接测试 stage 的 SHA 合同，生产 publishCreated 传入该 SHA 已源码复核；没有通过 UI 时序注入来验证此边界。

不含 UI 模型请求绑定、Save-As 成功后的会话持久切换、旧缓存保护、fork/merge、页面引用入口、来源跳转/反向引用/文件关系网络。没有打包 APK、安装、推送、合并或发布。真实 SAF 的跨进程写竞争仍不能当作原子 CAS 保证；同 URI 内容完全相同的空文件物理替换无法分辨。
