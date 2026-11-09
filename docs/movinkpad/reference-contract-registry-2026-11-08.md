# 页面引用登记契约（2026-11-08）

用户要求：持续推进思维导图与文件关系网络，不打包APK，可编译和运行测试；在启用页面来源前补齐身份与保存保护。
状态：R2a组件已实现，全量3011项JVM及Android测试源码编译通过，独立复核完成；生产保存/SAF接线尚未完成。
本轮边界：源码、测试、文档及本地提交；不打包、安装、推送、合并或发布。

## 范围与接口

DocumentRegistry从schema1升级到2，只新增page_reference_contracts(document_id)表，不改旧表。该证据按文档UUID保存，无位置/文档外键；移动、暂时失去权限、删除索引位置或文档行不清掉它。register(pageReferences=true)与文件登记在同一事务内记录，默认false不撤销已有true。

expectationAt(uri)用单次LEFT JOIN读取规范位置的UUID与契约（含不可用位置）；expectationForId(id)独立于位置查询UUID证据，保护外部移动到全新URI的文件。未知未来数据库版本拒绝降级，不改已有数据。

ReferenceContract是header级预检组件：调用方显式提供位置期望与观察UUID的期望。verifyObserved核对UUID和所需marker；verifyWrite先验证current，再核对candidate同UUID且不剥current marker。不允许用候选静默修复已退化的源，也不把未登记/零字节自动当新建许可。缺身份、身份改变、页元数据缺失用typed ReferenceContractViolation与中英文StorageMsg区分。

这不替代完整manifest页ID、原PDF、内容摘要校验，也不授予legacy升级、新建或替换来源权限。生产调用仍使用默认登记行为，尚未写入正契约或接此guard；须完成R2b之后再启用用户文件页面锚点。

## 验证与复核

新增7项Robolectric PageReferenceRegistryTest和5项真实ZIP Header契约测试。先以未实现行为运行tmp/reference-contract-red.log，随后实现；定向通过后全量tmp/reference-contract-full.log为280套件3011项，失败/错误/跳过均0，Android测试源码编译通过。

覆盖历史v1全部旧表行保留、未来v3拒绝不改库、契约插入触发器失败使文档和位置登记回滚、普通登记不降级、移动/URI别名/权限暂失/关闭重开、删全部旧location/document后UUID证据保留、新副本UUID不继承原件标志，以及外移新URI缺marker通过UUID证据拒绝。

guard覆盖A→当前B→候选A、源已丢marker而候选合法仍拒绝、无索引但剥当前marker、已有文件缺UUID需显式升级、typed reason及真正marker绑定错误由Header解析拒绝。

独立计划复核要求补独立UUID查询和先验current，均已落实；独立代码复核未发现需先修项。Android仪器增加实际SQLite v1→v2及单调契约/重开验证源码，仅编译未运行。JVM/编译结果不代表真实SAF、Android设备SQLite、窗口或MovinkPad/ProPen3验收。

## 下一片

R2b先扩展pending发布的验证/登记提交时序：在实际写入/恢复/清理前验证源与候选，文件读回成功后登记DB，DB失败保留日志供重试。随后接NotebookStorage与原生保存/in-place/恢复入口，补显式新建/复制/升级语义，防宽泛fallback吞掉typed违规；活模型另存/分叉身份切换仍需完成。再开放页面多来源、跳转/返回与反向引用，文件网络需求继续保留。
