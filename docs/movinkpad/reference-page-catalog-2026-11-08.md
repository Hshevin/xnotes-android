# 实际来源的只读有序页目录（2026-11-08）

用户要求：继续页面来源与文件关系网络，在可靠身份、离线本地存储和新渲染架构上实施；不隐式升级源文件，不打包APK。
状态：已实现；完整JVM307套件3409项全过，Android测试源码编译与两项独立源码复核通过；实际仪器/目标设备未运行。
本轮边界：原生元数据接口、测试、文档与本地提交；不打包、安装、推送、合并或发布。

前置核查见source-loop-production-gaps-2026-11-08.md。本片只提供实际来源的目录观察，不提前激活页身份、生产保存或旧缓存恢复。

AnchorManifest.Plan显式保存orderedPageIds，成员集由列表派生；原字节copy计划的顺序必须对应生成后的输出ID。BundleReferenceInfo保留原pageIds成员接口并携带顺序。NativeSourceSnapshot从已配对的实际版本/检查信息导出不可变目录，NotebookStorage通过openSource同源快照取得，不能再开URI、重扫manifest、创建临时页ID或解码笔迹/PDF。

能力分三类：CANVAS为NotPaginated；无marker的NOTE为NeedsActivation（不保证一定可升级）；有完整永久页ID的NOTE为Available且列出当前顺序。不把错误/未来marker、页ID丢失/重复、已有单调登记要求marker的文件降级为NeedsActivation。Available只说明本次引用身份完整，不保证全部内容可解码。

目录只暴露kind、UUID、canonical location key、实际SHA及页面能力，不暴露NativeSourceVersion保存接口、临时文件、lease、PDF路径或编辑模型。返回的是捕获时版本，不保证返回后源仍最新，后续解析需按文件实际版本核实。列表冻结，不能因调用者修改集合而改变观察。

沿openSource保留全部校验、PDF资源保护和失败清理；窄加checked resource resolve/hold传播取消，首次原件散列逐块checkActive，原resolve接口不变，资源gate内resolve→lease不留GC间隙。锁等待/平台DB操作仍可能延迟取消，不承诺立即中断。

性能边界：不增加目录专属manifest扫描/源复制，不解码或admit嵌入PDF；现openSource本身仍捕获并核验整包，多次散列及负载校验随大小增长，临时磁盘是整包规模。未来UI必须按文件显式单读/批量共享结果，不能每个卡片独立读大型包。“只读”是不改源文件/文档身份登记/恢复资料；资源索引与临时lease可有本地元数据维护。

验收：非字典序ID的顺序/重排/删页、嵌套扩展不入目录、copy新ID顺序与实际输出一致且不交原集；legacy不mint、canvas无Available；marker/身份/URI和UUID单调契约、alias、同UUID外改新SHA且旧观察不变；pending零读写不续写、权限与取消清理；embedded PDF不提取/admit，external PDF散列中取消及租约/GC边界。JVM及Android provider测试分开记录，不以源码编译冒充平台运行。


## 实现与验证

Plan与BundleReferenceInfo的成员集均从实际有序列表派生，构造数据冻结；内部Plan仅收List，旧两个synthetic写入测试改为空List，不再保留Set推断顺序的入口。目录外层私有构造，NativeSourceSnapshot.ReferenceCatalog只在open实际snapshot与checkActive有效时取得checked info，返回kind/UUID/key/SHA及三类能力，不返回原生保存版本对象。

8个新增目录测试覆盖顺序/重排/删页、旧数据冻结/关闭Source、无marker与canvas、缺/坏/重复/错绑marker、位置和UUID单调合同、alias、pending零读取、权限/取消/temp清理、embedded不admit/external租约保护与首次hash取消不污染缓存；另1个copy测试核fresh输出的真实顺序。raw manifest含未知渲染内容仍可提供身份目录，验证Available不声称解码兼容。最初缺orderedPageIds API反例在tmp/reference-catalog-order-red.log；boundary初次只有unsupported kind的异常类型预期与既有typed合同不符，已修测试而未改变生产拒绝。

最终tmp/reference-catalog-full.log：JDK21/SDK36/独立build及Gradle project cache，307套件3409项全过，0失败/错误/跳过，Android测试源码编译成功。新增实际owned-provider目录和平台PDF散列取消两个场景仅编译，未打包安装或运行仪器。两名独立复核者分别检查页顺序/身份/性能及checked PDF gate/清理/目录纯数据边界，均未发现必修；diff检查通过。

本片未接来源选择UI，不表示用户已可从PDF/笔记创建页面来源。下一步仍须生产owner稳定事务/原生会话统一及页身份显式升级；可独立推进按文件共享读取的resolver与只读反向投影，但不能把它们当作完整闭环或全库覆盖。
