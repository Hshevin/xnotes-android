# 原生会话的轻量来源观察与按需正文捕获（2026-11-08）

用户要求：持续实现思维导图来源闭环，复用原生引擎，避免大文档重复编码或不必要复制。
状态：已实现；独立计划/源码复核通过，完整302套件3329项与Android测试源码编译通过。
本轮边界：源码、测试、文档、本地提交；不打包、安装、推送或发布。

## 问题与合同

固定writer快照确保后续编辑不改变这次正文，但NativeWorkingSession现在在cache.reserve判断requiresBody前便复制所有item字段；只平移/缩放也产生O(items)分配。生产激活前必须让view-only保持轻量，不能把这个成本转为后台读活模型。

新增EditSource内部只读epoch observation：构造器私有，factory只接受实际EditSource与其真实owner模型，在同source同步区内核对kind/UUID/canonical location/对象身份并捕获expected引用和generation；后续只读isCurrent不在IO读取live模型。无任意tuple factory、无encode/publish/source adopt能力；不是SaveRequest或Receipt，不授予保存权限。invalidate/accept（含SHA相同no-op）废弃旧observation，另一同UUID模型不能使用它。

NativeWorkingSession Main capture先获取此实际observation与固定metadata，再用live ownerToken/kind/doc UUID/bodyRevision reserve。只有ticket.requiresBody才固定模型内容；view-only Write不持note/canvas body，也不访问item列表/Stroke采样/codec/PDF，source检查仍覆盖所有发布路径。unbound只有无path模型可用，规则不变。首次/版本变化/其它owner/失败后重试仍强制新正文，不从旧head counter伪造proof；若固定正文失败（如未知item），不发布head、不覆盖已有草稿。页ID准备仍由owner负责，固定正文捕获完成后核对ticket的owner/kind/id/version与实际固定视图，不匹配拒绝，不拿新revision给旧正文背书。

元数据/来源验证在reserve前完成；对成功view-only仍沿原cache正文SHA与版本proof核验。observer仅保留私有source epoch，不创建native存储阶段或改变来源版本。SaveRequest本身继续固定正文，不能为性能把普通native请求降级为浅快照。

## 验收

note/canvas实际bound/unbound重复view-only capture无body，源码不访问members；以故意会在copy时失败的未知/不可编码item对旧成功版本做对照，view-only不进入copy路径，显式dirty后必须拒绝且旧head/body保留（此反例只测不访问，不允许生产不标dirty编辑）。真实正文后变更必须requiresBody且编码新内容；丢弃旧ticket、其它owner、失败first publish、source accept/no-op/invalidate、错owner/错storage provenance均回归。isCurrent与source epoch在IO取消/大包验证前后保持原行为。

完整JVM/Android测试源码编译与独立复核；Android平台与MovinkPad性能未运行单独报告。旧生产仍不切换；活动未提交事务稳定capture、native/cache同head handoff/ack与恢复认领、new/copy pending及来源UI闭环继续保留，整体未完成。

实现：EditSource.Observation只接实际source/model，私有constructor，不含正文或native发布能力。note在确定ticket key前准备页ID；owner/id/version只读取一次。reserve后按requiresBody创建固定副本并复核其key/observation，view-only无body、仍校验实际source epoch。encoder若被view-only错误调用会拒绝，不编码空画布。

验证：tmp/native-light-capture-red.log两个复制访问probe先失败；tmp/native-light-capture-first.log与tmp/native-light-capture-verified.log定向回归及Android源码编译成功。新增note bound/unbound、canvas view-only无body/不访问items、dirty后拒绝未知内容且保留head、实际错owner/退休/path观察，既有真实同SHA no-op例追加旧observation/view-only票据失效断言。probe故意不标dirty仅用于证明没有访问，不能作为实际编辑可不标dirty的规则。

最终验证：tmp/native-light-capture-full.log，302套件3329项全过（0失败/错误/跳过）；Android源码编译成功。新增3个JVM行为场景，既有真实same-SHA no-op场景追加observer/view-only失效断言。独立复核未发现未处理必修。未执行仪器、目标设备或大笔记捕获耗时/内存测量；这里只消除已知view-only固定副本分配，不宣称真机性能验收完成。
