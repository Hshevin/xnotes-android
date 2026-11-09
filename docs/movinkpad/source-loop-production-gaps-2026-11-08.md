# 页面来源闭环的生产接线核查（2026-11-08）

用户要求：按既定需求持续推进思维导图与文件关系网络，保留页面级引用决定，不以基础接口冒充完整闭环，不打包APK。
状态：只读源码核查完成；本记录为后续依赖与风险，不是页面来源功能交付或真机验收。
本轮边界：读取现有源码、记录核查结论；未运行设备、打包、安装、推送或合并。

独立核查以b99c07aa为基线，后续A2普通来源的会话代次和字段合并已另修，见mind-map-connected-node-2026-11-08.md。

- MapRelations/MapGraphJson已有PAGE+pageId+region往返，不需改xmap版本；MapSourceAccess当前只读文档UUID并创建FILE来源。页成员核实、顺序目录、实际摘要和状态resolver仍缺；画布不能仅靠构造MapSource就冒称PAGE。
- Editor仍使用SessionStore/CanvasSessionStore旧分离正文/sidecar流程，native writer仍走浅snapshot/publishNote，NativeWorkingSession和EditSource.capture/accept交接未统一生产。旧restore先退休来源再直接采用缓存，不能拼接新来源proof来授其普通写权。
- 源内容稳定屏障不仅是stylusInContact。CanvasSelection变换与Editor表格预览会在确认前改活几何；固定writer快照只保证捕获后不变，不能证明捕获时已提交。需同owner先等旧写完成再stable capture。
- BundleAnchors原字节候选已有但生产未调用，新建/PDF导入也未启用永久页ID。NativeSaveContract明确拒普通保存的anchor模式改变；原字节启用后若工作缓存仍无marker，cold恢复会因合同不一致拒绝。因此页ID启用须显式原字节升级及同head/模型采用协议，不能只写一个enableAnchors入口或放松普通校验。
- Save-As/fork活模型当前只换document UUID，PageReferenceCopy/NativeCreationIntent等尚未接入这些生产入口。激活页ID前必须把新页scope、采用和持久copy交接接完整，防副本继承原页身份。
- native内核仍缺冷pending4固定B受控续写、活owner退休转交及unbound/legacy正文的无原件写权采用；Unbound只给metadata，不能据此声明旧草稿可完整恢复。未采用secondary草稿也不能被普通clear清除。

顺序保持：先统一生产owner稳定capture/native+working checkpoint/恢复/new-copy，保护旧会话且不半接；然后原字节页ID激活与单调登记；再从实际不可变来源读取有序页目录/摘要/成员关系，接页面菜单→既有节点或新节点来源→保存重开→状态/预览/跳转/反向引用。裸PDF须经已有导入取得文档和页面身份，不以SHA+页号临时冒充永久pageID。

可并行但需准确标边界的基础片：只读页目录/状态resolver（不隐式升级、不授写权）；按文件批量核实和按需预览；限定实际输入map集合、可重建的反向引用投影。一个概念绑定多个文件不能推导这些文件彼此依赖或引用；不把未保存工作版本和实际保存文件的索引混为一体。

后续验收重点：未提交预览取消、旧写排队、跨页/复制页、B写成回执未到、cache完成失败、同源打开前flush、旧会话保全、PDF字节不变与来源添加保存重开。这里只记录源码证据，不代表Android仪器/MovinkPad验收。
