# 核实并保留已写入的原生版本（2026-11-08）

用户要求：保存已写入文件但后续报错，再选放弃时，保留已写入版本，只放弃之后新修改；继续思维导图保存/恢复，不打包APK。
状态：已实现；299套件3284项JVM全过、Android测试源码编译/独立代码复核通过；真实平台/目标设备未运行，尚未接生产Editor的放弃/会话流程。
本轮边界：源码、测试、文档、本地提交；不打包、安装、推送、合并或发布。

## 现状和切片

NotebookStorage.keepCurrent已有只读目标、保护/登记/清pending的通路，允许显式保留同来源的当前版本，包括某些历史legacy恢复。publishExpected/OpenSource/PreparedNativeOpen又分别保留了实际源版本和同源读取/解码。新的“已完整写入”的判断不能从普通keepCurrent的Unit返回或FileStamp推断，更不能把来源读取版本伪装成SaveRequest.Receipt。

本片复用keepCurrent流程，补内部keepWrittenSave(uri,kind,checkActive)的严格只读目标核实与实际NativeSourceVersion返回，供后续producer/放弃流程使用；不提前接Editor旧SessionStore，不清未采用草稿，不改变默认恢复/另存UI。保留producer/cache/head/UI采用、new/copy pending等剩余要求。

## 合同

- kind仅NOTE/CANVAS。只接受有效typed SAVE_NOTE/SAVE_CANVAS pending，purpose与请求kind一致。CREATE、legacy、未知/坏版本不可降级接受，原件/候选资料保持。
- pending的record与保留original/candidate均须实际摘要匹配且原生契约完整；实际目标快照必须完整、摘要等于record.candidateHash（byte-identical no-op可同时等于originalHash），再核对kind/UUID、页面、原PDF和登记期望。严格入口的最终登记事务内重查URI身份、UUID单调页契约及已知kind，不能让pin/IO期间的新foreign登记被无条件register覆盖；普通keepCurrent兼容语义不扩大。原始A、部分写入、外部C均不被此接口当成已写候选B；不写源，不替换不同PDF。
- 复用原子keepCurrentValidated，在登记前后重复核对目标摘要/保留文件证据，在最后retained/record大包复核之后、clear紧前增加beforeClear目标摘要核对（NotebookStorage普通keepCurrent也沿用该保护），不把大包hash夹在最后target核对与clear之间。beforeClear返回后再按先前record的typed属性有界复核marker摘要/字节，防target.read期间新future/purpose record被清；严格typed不降为大legacy hash。历史显式legacy keep保留既有垃圾record预算/兼容路径。SAF外部变化仍有最后读取→clear的不可原子小窗口，不能宣称跨provider原子性。登记/pin/清journal失败保留恢复资料，不自动restore旧原件。失败/取消不报告成功，不借失去权限或目标缺失猜“尚未写入”。
- 真实NativeSourceVersion来自实际目标snapshot的完整inspect/SHA配对，不能只使用候选SHA拼构造。该返回值只是已读版本，不是与旧SaveRequest配对的发布receipt；不允许直接accept旧请求来签模型写权限。后续实际解码/采用仍用同源流程，新的producer须与当前owner/缓存/UI采用协调。
- 普通keepCurrent保留现有legacy/允许当前C的明确操作语义，不因新接口改变或放宽旧规则。只有新严格入口要求“确已写成候选”。手动“恢复备份”仍是单独显式操作，不把它当作放弃默认行为。
- 大PDF先按实际header持租约/保护，再做完整载荷/原生inspect；沿用现PdfResources gate和pending/note pins，检查取消，不为此复制/解码原PDF。现acquireCreationProtection同属原生header依赖保护，可合理抽为共用helper但不重写资源引擎。
- 已确认登记/日志提交后的lease清理失败不逆转成功，保留lease并记录；主失败优先，close错误suppressed。clear只有当前目标仍为核实候选时才执行；未知pending/corrupt资料不得被新capture或自动重试清掉。

## 验收

JVM真实NotebookStorage/SQLite/原codec及故障注入：目标已复制B但target.write之后抛错，或后续登记/日志失败后重试，keepWrittenSave只做read/登记/清日志，write次数不增加且返回实际目标SHA；原页UUID/原PDF字节保持。NOTE/CANVAS及no-op均覆盖。

目标仍A、部分写入、同UUID外部C、foreign UUID/类型、原PDF/页契约不符、坏/未来record、损坏original/candidate、CREATE/legacy、权限丢失/取消/登记前foreign位置/页契约/kind改变、beforeClear期间marker替换、登记期/最后retained hash期间目标变更均拒绝且留资料，不写任何目标。保护期间GC不能删external PDF，取消/失败释放临时lease，已成功cleanup失败不变成保存失败。

旧keepCurrent/restore/publishExpected/creation回归继续；Android实际provider/JSONObject/SQLite用例保留，源码编译与实际运行分开。MovinkPad/Android15/ProPen3未验收。

后续统一producer根据用户决定处理放弃：仅丢后续新编辑，来源B/C不自动回滚；必须安全等待在途写、核实owner、可靠保留/切换工作会话，再替换UI/清旧缓存。页面激活、多来源/预览、跳转返回、backlinks和文件关系网络仍保留，overall complete=false。

## 实现与独立复核

新增NotebookStorage.keepWrittenSave返回NativeSourceVersion；普通keepCurrent仍走兼容分支，不改legacy/explicit restore的产品语义。共用keepCurrentVersion严格分支核对typed SAVE、record/两份stage摘要、实际目标==候选以及完整原生保存契约，再registerObserved和清日志。登记事务内重查known kind、URI身份及UUID单调页契约；不会用无条件register抹掉准备期间的新foreign登记。

MigrationPublication.keepCurrentValidated的beforeClear在最后retained大包hash之后核对目标，再按原始raw.typed对marker做有限读取/摘要比对。严格typed即使被换成超大legacy垃圾也不降级全读；原有显式legacy keep的大垃圾record预算与兼容仍保留。两个core反例分别在postDB最后candidate hash中把target改C、以及beforeClear中把marker改purpose/1MiB future，均保留恢复资料。

取消/PDF复核修复：严格分支复用acquireCreationProtection的已持lease ids建立保护，不再二次调用无取消的旧protectCurrentHeader fallback。journal retained hash、完整inspect、目标摘要与actual PDF SHA均传checkActive。external PDF实际字节在lease内核对，不能靠resolve文件stamp缓存；同大小/mtime坏原件的新增反例先红（旧代码会误成功清日志），修后通过，保留坏PDF字节和所有恢复文件，不擅自“修复”或替换原件。普通旧helper行为未扩改。

## 验证证据

`tmp/native-keep-written-final-full.log`：JDK21/SDK36/Gradle8.13离线、独立build/project cache，`:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`；299套件3284项，0失败/错误/跳过。新增15个NativeWrittenSave方法和2个core边界方法，覆盖真实codec/SQLite/目标读写故障、NOTE/CANVAS、no-op、post-copy/index失败、原件/部分写入/外部C拒绝、purpose/record/stage损坏、权限/取消、PDF GC/lease清理、foreign登记/known kind/页契约及旧keep兼容。`git diff --check`通过。

实际Android provider/PFD/SQLite登记失败后keepWritten用例已加进现类/runner，只编译未执行；MovinkPad/Android15/ProPen3及真实SAF故障未实测。计划/代码独立审核完成，唯一代码复核取消发现和新增PDF实际SHA反例均已修并窄复核通过。没有打包、安装、推送、合并或发布。

此返回值仍不是旧SaveRequest.Receipt，不由它重新贴任意活模型。生产producer/缓存/UI采用和新建另存持久切换尚未接入，不能宣称用户层放弃/重启恢复闭环全部完成。最后读取与SAF外部改动之间仍有不可原子的短窗口；未声称provider级事务。
