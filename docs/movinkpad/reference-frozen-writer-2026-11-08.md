# 原生工作会话的固定写入视图（2026-11-08）

用户要求：继续原生思维导图与来源闭环，复用已有笔迹/文本引擎；保存期间允许继续编辑，不因显示或保存丢内容。
状态：已实现，独立计划/源码复核通过；完整 JVM 302套件3326项与Android测试源码编译通过；目标设备性能未验收。
本轮边界：源码、测试、文档、本地提交；不打包、安装、推送或发布。

## 已核实的问题

Document.snapshot/copyWriteSnapshot与InfiniteDocument.snapshotForWrite复制成员列表但共享CanvasItem。Stroke内部Samples是不可变已发布前缀（append仅填旧count之后的slack，变换/重设替换tuple），这保证逐笔读取一致，不保证请求捕获后正文不变。IO开始前修改/撤回选区可能改变旧请求实际写入的几何、正文或样式，使bodyRevision和文件字节对应不上。复制完整笔迹数组又会重现大笔记内存问题。

## 本片合同

新增显式固定writer快照，保留原浅snapshot默认行为以免扩大改动到旧生产渲染/保存路径。固定视图复制每个可序列化item的全部写入字段和成员关系；Stroke捕获同一Samples tuple，不复制或重新编码原始样本数组，冻结config/cuts/通用属性；后续append、重设、变换、橡皮替换与取消不能影响捕获内容。非Stroke复用现有模型构造/不可变资产，TextItem由自身带原measurer复制，无第二套文本引擎；AudioData来源文件已录完且不可变，remux以替换AudioData发布而不改旧文件，复制小peaks以隔离数组。Shape adj/path、connector route/end/label、image geometry、tape persisted shape/style全覆盖；未保存的播放/剥离/编辑显示状态不纳入正文。对未知item拒绝而非丢弃。

同scope内connector绑定重映射到固定副本，保持GlueIds编码语义，页间外部target按原writer行为不形成错误绑定；通用locked/group/sync/layer属性保留。分页保留原UUID/永久页编号/原identity/PDF方式/流式正文/布局字段，页layers和savedLayerIds按副本映射；不调用Page.deepCopy，不分配新页身份。画布保留文档UUID/layers/waypoints/bodyRevision/snapshotOwnerToken，不建空间索引。固定副本仅用于写入，不进入renderer、编辑历史或live模型。

接入本分支新SaveRequest与NativeWorkingSession capture；legacy SessionStore/旧Editor writer不切换。原位模型与渲染未修改。固定捕获保证“捕获后变化不影响这次正文”，不宣称能判断捕获时的未提交编辑：活动内容事务的等待/稳定checkpoint仍是下一片producer前置，Native cache不得据此在生产中捕获选区预览。bodyRevision/view-only只在该未来稳定capture合同下适用，本片不激活生产。

## 验收

真实note/canvas/native request与cache编码：先capture，修改同一Stroke几何/config/cuts/追加样本、文本/形状/图片/音频metadata/connector及层归属，编码/解码仍是捕获时内容；原始压力/时间与页编号/PDF原字节不变。connector目标绑定固定副本，旧writer默认浅行为保持；多个固定视图互不改写，未知item拒绝。页重排/删除/跨页成员、flow/bookmarks、层删除/撤销后旧捕获稳定，来源proof和内容version不伪造。复用现有codec做真实roundtrip，独立审核所有serialized字段与旧笔迹copy-on-append前缀保证。

全量JVM与Android源码编译；Android真实事件/设备未执行要明确，捕获耗时/峰值内存需MovinkPad大笔记验收，不把源码O(items)推断当成真机性能通过。后续内容事务屏障、cache schema2/ack、重启认领、生产迁移、新建/另存copy pending和来源UI闭环仍保留，整体未完成。

## 实现与复核

显式Document.fixedWriteSnapshot与InfiniteDocument.snapshotForWrite(fixedItems=true)已接本分支SaveRequest/NativeWorkingSession，旧默认浅快照保留。Stroke pin当前Samples，七类note/六类canvas的写入字段与胶合/图层逐一固定；复制嵌套容器、小数组与文档样式/模板/markups/quads/rooms，原PDF/图像/录完音频仍共享不可变文件，不加载整份资产到内存。

独立审核提出每页独立scope、Canvas拒绝TextItem和嵌套容器脱离，已落实。复核发现flow.deepCopy仍共享表格widths/minHeights；tmp/native-frozen-writer-table-red.log真实ZIP flow负载反例先失败，固定路径按copied table identity脱离两个列表后通过，cell归属与默认flow deepCopy未改。原生note/canvas捕获后几何修改的两个反例在tmp/native-frozen-writer-red.log先失败，修后保存与cache正文保持捕获内容。

本片尚有明确边界：当前NativeWorkingSession在reserve判定view-only前仍做固定item副本，body不重编码但会产生O(items)分配；下一片轻量source epoch observation→reserve→仅requiresBody固定正文在生产激活前处理。活动未提交变换的稳定捕获屏障仍另片，不把固定副本当作其替代。大笔记捕获耗时/峰值内存、Android事件和MovinkPad硬件未执行；不宣称GPU/性能验收通过。

最终验证：tmp/native-frozen-writer-final-full.log，302套件3326项全过（0失败/错误/跳过），Android测试源码编译成功；14新增JVM行为例。原默认快照未额外复制成员列表；固定copy与图层映射不再产生Pair中间列表。独立复核无未处理必修，未运行真机/仪器/大笔记内存与耗时测试。
