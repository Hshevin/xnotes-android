# 思维导图与文件关系网络：当前恢复点

目标：按原需求继续实现，active，overall complete=false。用户询问“完工了吗”已明确答复未整体完成；不是暂停或缩小范围。
工作树：/Users/kita/.codex/worktrees/mind-map/xnotes，分支codex/mind-map。
授权：代码、编译／测试、文档、本地提交。master54f05b0f既有同步已完成；不打包APK、安装、推送、合并或发布。
规则：page-level引用（分页永久页UUID，画布仅文档UUID，可选区域只存xmap），不恢复对象ID；原字节升级与PDF SHA校验／资源租约不变；失败已写版本保留，不回滚原件。遵守本仓AGENTS/MOVINKPAD/README/project-brief。

## 当前完成的 R3 有限出口第一片

上一阶段已提交2081c753/267322cb/1f5e31e7，恢复读取／独立副本与实际窗口完成。新片已verified并本地提交47c4cc49：实际分页首次保存／另存接NativeCreation和PageReferenceCopy，Main不预开输出流；强制另存返回原URI也拒非空。C1保持dirty／历史／Page／viewport，末次owner/source/scope/PDF指针核，PDF lease跨编码／发布，采用实际受管文件及元数据；写后失败保原模型、独立恢复完成不偷偷采用。新NativeDestinationPicker固定原callback+每launch运行时唯一key，Main先awaitStableForSave／核初始身份路径后再捕获最新proof，IME不作废刚打开的请求。

原字节页启用证明已提交1fe1286c，thin picker已提交929ab4df：BundleAnchors.verifyActivation只接受NeedsActivation，无marker无任何直接页ID；supplied IDs回放raw插入，全部其它payload／UTF8片段和canonical marker约束，原／候选inspectStrict完整PDF合同。仍无实际Storage activation purpose／恢复消费者，不能宣称PAGE UI已可用。

最终证据tmp/page-copy-ui-input-final-full.log，30522已终态0，331套3618项主机，0失败／错误／跳过，Android测试源码编译通过；无运行Gradle。此前26960相关41全过，76961 SaveGate17+Editor6+Picker2共25全过。实际Editor6例、Raw11及旧Anchor22、Picker2都执行；AndroidSplitEditorTest原2unsafe输出调用已迁异步实际文件API，新async夹具隔离session/registry/cache/PDF并恢复singleton，88931独立Android源码编译通过；保共享/历史/PDF断言但未执行。系统DocumentsUI/真实SAF/PDFium/MovinkPad Android15 ProPen3/GPU/大文件性能未运行，不当成真机验收。独立Raw/SaveAs/Main输入顺序/PDF所有权复核通过，两中文资源一致、diff --check过。

真红：70480真实empty SaveAs旧失败（17763为错误包filter非行为红），83368 verifier API红、14369 picker API红、13016 forced SaveAs API红；82631新PDF合同真红后补strict；33151 PDF文件采用指针真红后修。候选fd.sync曾因codec关闭底层流失败，日志editor-page-copy-exception-diagnostic，已按SessionStore nonclosing bulk wrapper修；不是平台差异。Picker初版测试假设OPENABLE分类，已核本地AndroidX1.10.1实际合同无此分类后移除无依据断言，不削弱action/MIME/真实结果/旧code隔离行为。

正式证据及边界docs/movinkpad/reference-save-as-page-copy-2026-11-09.md；Root生产与正式测试已全部提交：Raw 1fe1286c、Picker 929ab4df、实际SaveAs 47c4cc49；本轮开始clean且未夹带其它修改，当前仅同步断点状态。不打包/安装/推送/合并/发布。

## 下一条立即实现的出口

source_loop_audit已准备并冻结ignored tmp/editor-stored-copy-cases.kt（2个实际NOTE raw负载/PDF/unknown/非空目标/不改live用例）与tmp/editor-stored-copy-next-notes.txt（<=1页）。拟API internal suspend Editor.copyStoredFileTo(srcUri,destinationUri,name):Boolean；Root先审case、移正式取API红再实做，不再重开整套native4讨论。

复用现NativeCreation固定新DocUUID，BundlePdf.write在同一raw BundleSnapshot按新identity一次投影为portable包；外部PDF先holdResolved后resolve。文件副本读实际存盘源而非live重encode，不改当前model/context/history/viewport。Main saveCopyLauncher改固定原src回调，不先开流，并共用现thin picker。Canvas同native管道需补测试；.xmap必须MapCodec/MapStore独立JSON，目前缺明确create-empty／journal-readback入口，按最小缺口补，不交ZIP。

随后实际修createNoteFile的wt+失败delete与fork/merge UUID/page scope；这些是PAGE启用前仍必修出口，当前SaveAs成功不代表它们已安全。然后raw activation storage purpose+retained A→B proof/明确恢复→fresh catalog／PAGE来源／xmap保存重开。继续来源预览／状态／刷新／修复、跳转返回／反向引用／网络、A2无中心／同级插入／边缘自动平移。正式spec reference-page-ui-first-2026-11-09.md已独审；完整native4是独立方案，不把unused API当硬前置；原保存恢复安全与所有产品需求保留。

## 协作

Root独占Editor/Main/资源/docs并统一Gradle/本地提交。cancel_event_tests实现format与Picker且freeze；source_loop_audit写Editor6真实测试/Android原2例迁移及下片tmp且freeze；review_input_plan独审通过已completed。completed需followup_task，send_message不唤醒。当前无运行作业。直接下一真实副本出口，常规编码/测试/本地提交无需再问用户。
