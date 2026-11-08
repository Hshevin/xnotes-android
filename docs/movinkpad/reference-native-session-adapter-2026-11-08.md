# 原生工作会话适配（2026-11-08）

用户要求：持续实现思维导图/文件关系网络，可靠保存、重开和失败恢复；复用原生Kotlin、保存与PDF资源，不打包APK。
状态：R2b2b3b0已实现；完整JVM回归296套件3237项全部通过、Android测试源码编译成功、独立代码复核通过；实际平台/目标设备未运行。
本轮边界：源码、验证、文档、本地提交；不打包、安装、推送、合并或发布。

## 依赖与切片

c2dd1ab5已建立actual模型来源、原生快照请求和请求配对实际发布回执。WorkingSessionCache已有原子head/body、请求代次和读租约，但SessionStore/CanvasSessionStore仍是旧实现。此片为它们增加统一的原生codec/来源/视口适配组件，不立即替换Editor恢复入口；下一片严格来源恢复grant与producer协调齐备后再一起接线，避免旧cache冒充来源基准。

## R2b2b3b0合同

- 内部NativeWorkingSession（命名可调整）在每窗格专用私有子目录使用同一个WorkingSessionCache，NOTE/CANVAS由同一head明确表示，不让两个适配器互相猜测clear。
- owner主线程capture actual模型、视口与当前来源：有path时必须有该模型的EditSource，由它签发snapshot+真实base proof；无path的新草稿可无来源。不能从URI/FileStamp/dirty或任意JSON拼SourceVersion。另一个同UUID模型的context拒绝。
- 用现有Document.snapshot/InfiniteDocument.snapshotForWrite，冻结成员和字段按原契约，不声称整文档深冻结；内容版本使用bodyRevision+snapshotOwnerToken。metadata在reserve时冻结：版本、模式、dirty、显示名、note视口/overrides或canvas lastView，以及可选来源描述。overrides先严格校验实际类型、数值范围、enum/rotation/hex颜色，再复用现ViewOverridesJson，不利用其默认/钳制掩盖坏metadata。
- 来源描述只有已签发base的kind/UUID/URI/规范key/SHA等数据；读出的描述是待核实数据，不是NativeEditSource、NativeSourceVersion或保存权限。此片没有从缓存model+描述构造可写来源的入口。
- NativeWorkingSession保存请求自己编码其捕获snapshot；bodyChanged用原DocumentCodec/CanvasCodec，分页cache先用完整便携PDF包。仅真实正文变化编码/复制PDF；view-only同owner/kind/UUID/bodyRevision可复用旧正文，验证仍按现组件。画布lastView独立存metadata，复用正文时仍更新位置，不从body旧lastView恢复。
- WorkingSessionCache增加可选metadata验证回调（保留现有构造兼容），在现有head与候选的verify路径统一执行，覆盖load/publish/publishClear/GC；bad/future metadata不能被新capture/clear覆盖，也不能在失败load的Read.close时授权清理旧资料。验证仍在reservation锁外，不把慢IO放到reserve临界区。
- 完整body验证固定在组件：BundleReferenceInfo.inspect核验实际kind/UUID/页身份，拒绝外部PDF引用式cache（本片没有durable cache PDF pin，不能先移除内嵌原件）。正文写入前通过现有PdfResources.admitHeld取得受管原件/租约。未受管pdfsrc草稿使用受管不可变副本，只更换缓存snapshot的pdfFile/pdfResource，不改live或删除原始temp；补File重载时在同gate完成admit/resolve/hold，并核对实际文件SHA，不能只凭stamp缓存错用旧PDF。受管原件不重复复制；view-only不调用admit/copy。缓存只持编码需要的lease，不替代caller对live PDF的资产所有权；不改live模型，不把缓存当用户导出备份。
- source proof在publish.checkActive全路径检查，包含requiresBody=false/view-only，不仅在writeBody闭包检查；IO只检查运行时上下文/代次，不读取live编辑器字段。来源已retire/基准前进的旧capture失效，不作为新source基准。native成功后producer仍须在主线程reserve新缓存请求；不能把组件内部配对说成跨native/cache的原子事务。现最后checkActive位于head锁外，之后ctx仍可能前进；本片只保证发布前可观察的失效，check→rename窗口由下一步producer立即reserve与来源恢复SHA核对协调，不宣称无窗口。
- schema1明确判别bound/unbound：bound需要完整source且URI按共享locationKey算法匹配key、kind/UUID同head/body；unbound不允许path/source。未知binding（含未来copy pending）不能降成普通草稿。
- source metadata严格读实际JSON类型、版本、UUID、kind、规范位置和SHA，缺失/NULL/错类型不默认为干净或有效来源。任何非法/未来版本/错模式保留文件并报明确错误，不能回退为无缓存或旧legacy。
- load只返回已验证body、类型/UUID、严格解析的metadata与读租约，尚不decode成活模型、不签可写来源。WorkingSessionCache.Read补只读body SHA，直接来自已验证head中的Body.sha，供后续解码核对同一读版本；读租约持至调用方完成。
- clear先reserve再持久tombstone，旧任务不能复活；不删父目录legacy固定文件，不做未核实的旧cache自动迁移/自动清除。副本进行中的持久保护不属于普通unbound/bound描述，本片不得用现capture把A正文贴成B身份。

## 验收

- NOTE/CANVAS真实codec保存、head/body/metadata重开、按模式切换及clear；pageUUID/笔采样/图层由现原生codec保持。
- 同owner正文复用，bookmark等仅推进bodyRevision的修改需要新正文；失败后相同版本仍重写；保存失败/取消/head失败保留旧head/body。
- metadata冻结、另模型同UUID拒绝、path无context拒绝、ctx前进/retire使旧请求失效；不改live dirty/viewport/history。
- 未受管PDF草稿首次正文/失败清理/重复view-only分支均覆盖，live temp原件保持；多MB PDF view-only不重新编码或复制整个PDF；资源GC在编码期间不删原件；便携body保持原PDF字节，source字段/metadata不被当作完整备份声明。
- canvas view-only回到最新lastView metadata，body可以保留旧lastView；note overrides真实roundtrip；严格JSON NULL/string-number/缺dirty/未来版本/源ID或kind与正文不符等拒绝并保留。
- native来源外部同UUID变化不会由此片自动写入，也不会无条件“修复”缓存来源；下一片来源核实前，reader只是数据和lease。
- JVM为行为/失败注入；Android codec/JSONObject.NULL/私有文件系统保留仪器用例，本轮只编译时明确未执行。

## producer / 恢复 / copy接线前仍需完成

- native receipt→更新actual context→捕获当前live与视口/reserve，所有替换/关闭等待正确保存；queued native写等旧任务后再冻结，不拼过时base。
- 原子cache恢复只在真实来源kind/UUID/原始SHA/规范位置及原PDF/页契约均核实后签发恢复grant；工作副本可有增删页，不能拿source.verify完整页集合硬套工作副本。原始cache包是portable模式，不代表原生来源也应切成embedded；应使用真实来源的包装模式。
- 旧body+sidecar不能建立来源因果，先保留为不自动写原文件的恢复草稿；单/双窗格未采用的草稿不能因普通clear/新mode被静默清掉。
- Safe-As在写B之前必须持久copy pending，B成功后才投影/采用新Doc/page scope；A旧缓存不得自动写回A。失败后的实际已写版本/放弃语义仍等用户答复，不在本片猜定。
- 工作区私有head与UI采用不可能靠两个独立异步动作假装一个事务；实际接线须定义明确的提交边界/忙状态，保证取消或后来编辑不会让未采用的B覆盖A恢复位置，必要时保留准备中的两份资料。
- 显式PDF插入/合并背景保留原受管对象不改字节，需独立来源变更事务；不能为兼容旧调用放松普通原PDF核验。

页激活、页面多来源界面、来源跳转/返回/反向引用、文件网络和A2仍保留，overall complete=false。

## 实现与复核补充

NativeWorkingSession已实现上述capture/publish/load数据适配；Editor/SessionStore恢复尚未切换。新增严格head envelope扫描（复用JsonPull）：全层级拒绝重复解码键、尾随根、超64层，active/cleared字段集合精确匹配，防止未来metadata伪装clear触发清理。metadata验证在现有head、候选、load/clear/GC所有路径生效。

独立复核发现PDF admission的两个边界并已修复：未受管源的stamp缓存可能命中同stamp损坏的受管副本，现对第二份文件也核对实际SHA；新File重载的大PDF实际复制使用逐块取消检查，而非只在复制前后检查。原admit默认行为不改，view-only不调用PDF admission。新增回归覆盖这两种反例；取消后检查无临时resource/租约泄漏、不删除live原件。资源register刷新grace期，GC测试显式重新age后才检查回收。

两个新增Android仪器场景覆盖平台JSONObject.NULL/转义重复键、便携PDF与view-only、跨NOTE/CANVAS及canvas空视口，只编译未实际执行；不作为MovinkPad验收。

验证证据：`tmp/native-working-session-final-full.log`，JDK21/SDK36/Gradle8.13离线执行 `:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`（独立build/project cache）；3237项全过，0失败/错误/跳过，`git diff --check`通过。新增19个adapter回归、2个generic envelope回归；两个Android仪器用例仅编译。代码独立复核的两处PDF发现已修并最终复核通过。
