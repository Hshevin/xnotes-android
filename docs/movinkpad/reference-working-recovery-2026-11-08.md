# 已绑定工作副本的来源核实恢复（2026-11-08）

用户要求：继续思维导图与文件关系网络，复用原生保存/引用身份，可靠恢复而不误覆盖来源；允许编译测试、不打包APK。
状态：R2b2b3b1已实现；297套件3248项JVM全通过、Android测试源码编译/独立代码复核通过；真实仪器/目标设备未运行，未切换生产恢复。
本轮边界：源码、验证、文档、本地提交；不打包、安装、推送、合并或发布。

## 本片目标与边界

b3b0已具备实际head/body/metadata配对读取，但来源描述只是数据。本片为**已绑定**工作副本建立真实来源核实与实际解码/采用流程，仍不切换生产Editor的旧会话恢复。无来源的新草稿/旧legacy sidecar、不匹配来源的显式草稿恢复、应用内移动后的重新定位、producer及持久copy切换继续保留。源找不到/变更/权限丢失/有pending/契约不符均保留head/body，不降级签普通保存权。

- 唯一入口从NativeWorkingSession.prepareRecovery自己load/use的读租约开始，不接受外来活模型+描述来创建EditSource。Read补私有issuer并由adapter在working factory入口/结束核对该issuer及未关闭状态，跨adapter或外部拼装Read不能签ctx，关闭的lease即使body/SHA仍存在也拒绝。无head返回无会话；unbound以明确类型结果返回需要草稿恢复，不签来源权。未知/坏metadata仍抛Invalid并保留。
- 对bound描述按原URI通过NotebookStorage.openSource重新读取实际来源；匹配实际kind、documentId、规范locationKey、SHA全部字段。不能只靠文件名、URI、stamp或同UUID。同UUID内容变更也拒绝；不在本片自动重新登记、移动或重授权。
- 复用PreparedNativeOpen的资产/保护/实际模型采用生命周期，增加只给此解码入口使用的working factory。由factory自己解码cache，不允许把任意model重新贴source。返回的PreparedNativeOpen.version来自真实source，绝不来自cache ZIP摘要；额外携带原note/canvas视口数据供下一片采用。
- cache读租约持至解码和最终body SHA核对完成。body在解码前后对read.sha256重新散列，完整inspect kind/UUID/PDF/page契约，反复核对取消；guarded input检查取消。解码产生的模型页集合与**cache自己的**pageIds一致；不拿source原页集合要求工作副本完全相同，因为工作副本允许新增/删页/重排。
- cache与source要求相同kind/UUID、页锚点启用状态及实际原PDF摘要。cache必须portable，其PDF用实际embedded hash进行admitHeld；原PDF不同不可被普通恢复当成可覆盖来源，保留cache等待显式处理。Canvas按document UUID，无页面/对象身份。
- 恢复来源的包装模式取实际source（可能external PDF），不是portable cache；snapshot解码核实后只在新待采用模型上恢复实际source的完整identity（含v1无resource或未用旧字段）和包装模式，不重编码或修改来源原件。页面内容/顺序保留cache编辑结果，原页身份不改。
- decoded metadata dirty/name与note overrides/viewport/canvas lastView只应用到新模型/结果；canvas可无view，不能沿用旧body视口。准备不改当前Editor，不清缓存，不写源，不修改历史。
- cache embedded原件admit→lease以及source外部原件lease均遵守现资源gate与交接；cache admission lease直接转交Prepared作为独立adoption lease（不依赖尚未解码的embedded source.ids）；protectForAdoption后才允许owner线程adopt。取消、核实失败、close失败不泄漏decoder资产；清理失败suppressed不掩盖主因。不借临时清理删除受管原PDF。
- prepare成功不意味着source此后永不变化。采用后的SaveRequest仍以真实base做publishExpected，来源再次变化必须阻止覆盖；prepared source上途变更不靠“已检查过”绕过后续保存核验。

## 验收

1. 实际bound NOTE缓存有新增/删除/重排页、bookmark与dirty/name/view变化：重启新adapter后核实、解码、保护、采用，原页身份与编辑保持；实际请求发布恢复内容到来源，receipt和ctx按真实source递进。
2. CANVAS实际bound上下文、最新view metadata覆盖旧body lastView，空view也清除；实际native发布不改变文档身份。
3. cache portable PDF + source external/embedded两种包装（含embedded v1原identity无resource而portable cache带resource）都保持原PDF字节、原source包装；GC在decode/交接期间不删原件，取消/未采用关闭释放lease/资产。
4. 来源外部同UUID改内容、身份/模式/原PDF变化、源缺失、pending或权限错误：拒绝签权且cache与来源字节不变。不靠同名文件恢复，不把失败当成没有缓存。
5. 跨adapter/外部拼装Read拒绝，cache正文解码中被改/读关闭/取消，无法签ctx；失败保留cache。旧metadata/NULL边界仍由b3b0严格处理，不增加宽松legacy回退。
6. Android平台实际JSONObject/codec/私有文件资产生命周期保留仪器用例，本轮编译不等于已运行；MovinkPad/Android15/ProPen3未验收。

## 后续依赖不取消

完成本片后继续producer保存回执/排队快照/会话提交时序、unbound与旧缓存保留、另存copy pending与新Doc/page scope；再接页面激活、多来源入口、预览、跳转/返回、反向引用和文件关系网络。部分保存后放弃的回退语义仍等用户答复，本片无源写入前回滚/清理。overall complete=false。

计划独立复核已通过；落实Read issuer/open校验、完整source.identity及embedded cache lease交接。Prepared构造发生在source完整identity恢复之后，因此沿用其现有包装标记捕获，不另造基于portable模式的上下文。

## 实现与证据

`NativeWorkingSession.prepareRecovery`内部持load租约，明确返回Empty/Unbound/Bound；Unbound仅数据、不尝试匹配文件。`PreparedNativeOpen.readWorking`同时要求adapter签发且未关闭的Read，自行解码并核对缓存前后SHA/真实来源版本，采用后才可得到EditSource。缓存自身页集合校验允许工作副本正常增删/重排，实际source包装/完整identity与原PDF保持。cache admission lease直接随Prepared交接。

新增11个JVM方法覆盖真实codec与原生发布：新/删/重排页面、书签/dirty/name/视口、画布最新及空view、embedded v1无resource及external PDF、handoff GC、同UUID外部修改/缺失/权限/pending、原PDF替换拒绝、准备后再修改来源、伪造/跨adapter/关闭的Read、decode途中body改变及取消。pending用例先改变正文强制非no-op，再断言生产既有RecoveryRequired(MIGRATION_WRITE_INCOMPLETE,cause=IOException)与日志保留，未放宽生产合同。

`tmp/native-working-recovery-final-full.log`：JDK21/SDK36/Gradle8.13离线独立build/project cache执行 `:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`；297套件3248项，0失败/错误/跳过。新增实际Android provider→缓存→真实准备/采用→原生发布用例已加入现runner，只编译未执行。独立计划/代码复核通过，`git diff --check`通过。

生产旧SessionStore仍未切换，不把该组件的成功说成实际会话/自动保存已全迁移。后续producer须协调head/UI采用及未采用草稿保留，不能丢掉此边界。

产品决定更新（2026-11-08）：用户已确认保存后“放弃修改”保留已写入版本，只放弃之后修改，不回滚原件；详见reference-discard-written-2026-11-08.md。上文等待答复是本阶段实施时状态，该产品决定阻塞现已解除，producer/pending接线仍需验证。
