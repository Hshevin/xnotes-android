# 思维导图与文件关系网络：当前恢复点

目标：按原需求继续实现，active，overall complete=false。用户询问“完工了吗”已明确答复未整体完成；不是暂停或缩小范围。
工作树：/Users/kita/.codex/worktrees/mind-map/xnotes，分支codex/mind-map。
授权：代码、编译／测试、文档、本地提交。master54f05b0f既有同步已完成；不打包APK、安装、推送、合并或发布。
规则：page-level引用（分页永久页UUID，画布仅文档UUID，可选区域只存xmap），不恢复对象ID；原字节升级与PDF SHA校验／资源租约不变；失败已写版本保留，不回滚原件。遵守本仓AGENTS/MOVINKPAD/README/project-brief。

## 已完成与本轮状态

- 导图节点文本／手绘原位和放大、层级／折叠、三种布局、普通关系线、空白落点新建并连接、多来源FILE列表、节点多选／框选／组移动／删除／自由主题对齐与xmap保存已实现；各边界见README及对应docs，不代表PAGE闭环或文件网络已完成。
- 恢复只读正文基础6c00efa0/8873f200、CREATE首发与typed继续be7bdb68、隔离配置渲染0e05feca、真实物理槽读取与原字节副本2081c753已本地提交。
- 本轮UI消费者已接通Backstage实际窗格入口、Controller/Host、Main根接线及三语资源。已独审发现并修复旧UI帧动作、主动取消永加载、另resume清无关pending三口；最终窄复核通过，准备本地提交。Root只改文档；全部生产和测试冻结。
- 最新验证：tmp/recovery-host-consumer-reviewed-full.log，22244终态0，328套3599项主机，0失败／错误／跳过，Android测试源码编译成功。此前6207定向Controller5+Copy5全过。无运行Gradle。两中文recovery.xml一致，git diff --check通过。
- 真红证据：50535两行为红及tmp/recovery-host-review-behavior-red.xml（owner退休LOADING、Retained另resume）；77769 Actions API红。最初92054消费者API红、首版92209完整3596、修后22244完整3599均有日志。
- 真执行包括旧picker A/B、旧帧所有Actions、owner退休终态、FIFO与真实复制IO的dispose等待、SQLite写后失败/原副本typed重试且writes1。Android真实UI两条仅编译，不是执行；系统DocumentsUI／真实SAF／MovinkPad Android15 ProPen3／GPU／大文件性能未验收。

## 下一条实际产品路径（已纠偏）

独立源码核查证明完整native4不是R3硬前置。NativeSaveContract及单调登记可以拒绝已启用原件被旧unanchored普通保存覆盖，但外层直接输出仍须修。正式下一阶段规格：docs/movinkpad/reference-page-ui-first-2026-11-09.md，tmp/reference-page-ui-next-plan.txt仅辅助原始交接；以正式规格为准。

1. 同一有限交付先修Main createLauncher/saveCopyLauncher在打开流前保护、Editor.save(output)/createNoteFile以及另存/分叉身份复用。复用NativeCreation+PageReferenceCopy，不删除provider返回的未知/非空目标；同目标走checked普通保存。已有saving modal与稳定捕获可冻结这次编辑，Main采用核原owner/model/revision/票据，失败不改scope/path/dirty。
2. 原字节页启用复用现journal与PDF保护。新purpose明确穷尽处理；retained A→B须验证只有页ID literal/marker改变，不能仅验UUID/摘要；反例伪造合法marker却改ink/PDF/unknown payload必须拒。
3. 成功后fresh catalog→PAGE来源→已有/新节点→xmap保存重开；Editor“引用此页”须真实member ID匹配，不按页序猜，不拿旧lastReadSourceVersion作最新回执。
4. 继续来源多预览／刷新／失效修复、跳转与返回／定位轮廓／双窗格／反向引用、局部及文件库网络；A2无中心／同级插入／边缘自动平移继续保留。

完整native4／同head及冷pending4是独立保存恢复改造方案，按真实失败行为决定，不再将未使用API无限提升为所有来源前置。原产品保存失败／异常恢复等安全需求仍全部保留。历史阶段证据保存在docs/movinkpad/，旧断点备份在ignored tmp/，不要沿已被上述决策替代的旧依赖顺序重复造基础API。

## 协作与恢复

cancel_event_tests持有并冻结本轮UI生产及AndroidUI测试；review_input_plan持有并冻结Controller主机测试、负责UI窄复核与R3计划审核；source_loop_audit已交付R3有限计划并完成只读任务。无agent运行Gradle或提交；root统一验证和本地提交。完成agent需followup_task唤醒，send_message不启动新turn。

当前待root：提交已通过最终窄复核的本轮UI/测试/文档；记录提交号并保持干净工作区。随后直接按已复核R3规格写真实失败反例、实现有限出口与原字节激活/UI，不再重开整套存储方案讨论；常规实现/验证/本地提交无需再向用户确认。
