# 思维导图与文件关系网络：当前恢复点

目标：按原需求继续实现，active，overall complete=false。用户询问“完工了吗”已明确答复未整体完成；不是暂停或缩小范围。
工作树：/Users/kita/.codex/worktrees/mind-map/xnotes，分支codex/mind-map。
授权：代码、编译／测试、文档、本地提交。master54f05b0f既有同步已完成；不打包APK、安装、推送、合并或发布。
规则：page-level引用（分页永久页UUID，画布仅文档UUID，可选区域只存xmap），不恢复对象ID；原字节升级与PDF SHA校验／资源租约不变；失败已写版本保留，不回滚原件。遵守本仓AGENTS/MOVINKPAD/README/project-brief。

## 最新完成的 R3 第二片

已本地提交：
- 930de041：MapStore.create空目标／先候选日志后发布，MapSession/MapFiles实际副本消费者；canonical URI锁，当前raw／可构造direct／登记alias旧日志保护，孤立staging拒覆盖，限本次未同步partial清理，取消可续同候选。8个新增测试。
- 6a69cd73：Main文件浏览器固定source picker→Editor.copyStoredFileTo，原生存盘副本走NativeCreation，读取唯一原始snapshot/PDF先lease后scan并持至发布，不改live模型／路径／Hx／viewport。覆盖NOTE/PDF/Canvas/非空零写/已登记契约丢失/取消/写后登记失败续完/共享原身份。EditorPageCopyHostTest现13例（新增7）。
- 3e6cda3c：activatePages/completePageActivation，实际NeedsActivation+source版本，raw A→B权限证明与实际stage digest/B SHA绑定；重启只续固定A/B，PDF fresh hash/lease，登记后clear/fresh actual catalog。ACTIVATE_PAGES旧save/ensure/restore/keep/Create/v4均拒；坏标记JsonPullException归metadata，不改取消/PDF/IO/权限语义。7个新增测试。

52530定向37项全绿/Android源码编译通过，日志tmp/stored-copy-page-activation-focused-green.log。54558全量终态0：334套3640项，失败/错误/跳过均0，Android测试源码编译通过；日志tmp/stored-copy-page-activation-full.log及计数tmp/stored-copy-page-activation-full-summary.json。所有源码及测试在全量前冻结，无运行Gradle。独立窄审覆盖native-copy、Main固定回调、Map日志与staging、activation权限及最终3小修，均通过。diff --check干净。

行为红92043：正常共享复制因BundleHeader无equals却用实例==必失败，现比较identity/anchors实际字段；Storage7中坏marker为JsonPullException漏包装，修元数据识别。API红63850 native copy与44128 activation。Robo源查询经实际诊断33614发现ShadowContentResolver只调DocumentsProvider final pre-O query，必抛UnsupportedOperationException；测试resolverProvider桥只转同一queryDocument/openDocument/实际文件，未伪造产品结果。显式已登记页合同的反例先register(pageReferences=true)，不声称普通ensure自动登记该正合同。

证据及边界docs/movinkpad/reference-stored-copy-and-page-activation-2026-11-09.md。实际PAGE来源UI尚未开放，不能把Storage API算用户闭环。系统DocumentsUI/真实SAF与权限恢复/PDFium/Android仪器/MovinkPad Android15 ProPen3/GPU/掌触/大文件性能未运行。本片不改渲染器/前缓冲，不打包。

## 既有进度

原字节启用验证器1fe1286c；独立选择器929ab4df；实际分页首次保存/另存47c4cc49；前片记录f4b15c60。前片331套3618主机与Android源码编译通过。更早actual恢复读取/独立副本及Backstage恢复界面2081c753/267322cb/1f5e31e7已完成，旧证据保留对应docs。

另存：PageReferenceCopy按实际owner准备，固定正文与scope，PDF采用实际受管文件和身份，成功最终guard后adopt；C1保持dirty及原page/history/viewport。取消/后续失败保持原模型，明确完成保留副本不偷偷采用。picker独立运行时key，等待稳定输入/IME后捕获请求，不预开流。

## 下一条立即实现的出口

1. 修Editor.createNoteFile的直接wt与失败delete：provider返回未知/非空目标必须零写零删；共用现NativeCreation的空目标permit与固定候选，不另造引擎。按真实调用点覆盖空白新建/导入/分叉/合并，不先改海量API。
2. forkNote/forkCanvas/adopt及merge：PageReferenceCopy/独立scope，新Doc与新页身份；模型采用guard与晚到修改保持dirty。真实行为测试后本地提交。
3. 接实际PAGE来源选择：MapSourceAccess获取fresh actual catalog/version，旧笔记明确启用→现activatePages，成功fresh目录选择页→MapSource PAGE→附加节点→保存重开；本地UI迟到结果绑定原图/节点草稿。当前页入口等稳定保存后核实际模型页ID与目录，不能按序号给旧模型贴ID。
4. 继续来源预览/刷新/失效修复、跳转返回/双窗格/轮廓/反向引用、局部/筛选/全库网络与加入手工画布、A2无中心/同级插入/边缘自动平移。

正式finite spec仍docs/movinkpad/reference-page-ui-first-2026-11-09.md。完整native4/producer/cache迁移是独立方案，不把unused API当R3硬前置；原数据安全与全部产品需求保留。完全未知旧raw URI的hash日志不可反推，不猜测；已知alias冲突保留供显式处理。

## 协作

Root持Editor/Main/资源/docs并统一所有Gradle与本地提交。cancel_event_tests完成Map片并freeze；source_loop_audit完成Storage片并freeze；review_input_plan收尾独审通过，三者均completed。无运行作业。completed需followup_task，send_message不唤醒。后续可委派真实新建/分叉用例与独审，Root统一实际出口；常规编码/验证/提交无需再问用户。
