# 页面与对象持久身份模型（2026-11-07）

用户要求：持续实现导图与文件关系网络，来源依赖可靠页／对象身份，复制不加入旧引用，移动、图层与撤销保持归属，异常数据和保存失败不破坏原件。
状态：B2b1 原生模型与编解码已实现，全量 2808 项主机测试通过、Android 仪器源码编译通过、独立复核完成；B2b2 打开／登记／SAF 激活与 B2c 后继尚未实现。
本轮边界：仅实现、验证、文档和本地提交；不打包、安装、推送、合并或发布。

Page 与七类 CanvasItem 各持有独立 ReferenceIdentity 容器：ID 只读、一次赋值／恢复，同步更新与 volatile 可见，不能替换已有 ID。临时渲染对象不生成随机数；启用锚点的模型在拥有者捕获保存 snapshot 前，给新成员分配 ID 并按实际出现次数校验唯一性。writer 只验证，不能在后台给活模型补 ID。Page.uid 与 GlueIds 仍只供 UI 和连接器原有用途。

snapshot 保留页面 ID，冻结对象 ID／图层与成员列表；再次 snapshot 也读取原冻结映射，不回退实时对象字段。移动、跨页转移、改层和撤销持有原对象，ID 保持。深复制对象／页面拥有新身份容器，不继承来源，未知类型不能以返回原对象伪装独立复制。Document.deepCopy 是独立可编辑副本，保存应使用 snapshot。

原生 codec 将 reference_id 直接写入对应页面／对象，图像与音频 pending 直接携带源 ID，不按解析后序号配对。启用的写出带有独立版本标记且保留 manifest 最后约定；模式显式传递，没有可并发串扰的 codec 全局开关。legacy 模式不新增字段或标记，旧序列化与原样本规则保持。note 七类、canvas 六类（独立 TextItem 不是 canvas 的既有格式能力）均有往返；连接器目标仍指向重开的实际对象。

读取跟踪全部原始目标并与物化模型对账，标记在 manifest 后也追认。未知种类、无效带子、缺／探测失败图片、缺音频、缺 ID、重复身份、错误标记及自动补空白页不能产生冒充完整的 anchored 可写模型。无标记但有已识别 ID 时要求先进行 B2a 原字节补标记。版本使用精确数值词法，避免把接近 1 的高精度小数当作版本 1。图像只做原有接口允许的文件头探测，不提前完整解码大图片。

普通 anchored 读取必须具有可读的 PDF 原件；刻意省略 PDF 也不能返回部分可写模型。引用式身份与意外内嵌 PDF 并存的歧义包被拒绝，不能让包内字节替代登记底稿。失败只清理本次 read 实际创建的临时文件，pdfStore/resolvePdf 返回的借入／受管原件不在清理清单。

现有「仅笔迹副本」恢复使用独立 readInkCopy：先以临时源 ID 映射完整对账，再为新模型赋独立文档与成员 ID，不重置旧容器、不再复制大笔迹数组。清理 PDF 背景绑定、pdfResource、pdfPage/pdfTurn，保留尺寸、正文、注释、图层和样本。原来已锚定的源生成锚定副本，可写出重开且与原 ID 不相交；legacy 源仍生成 legacy 副本，待 B2b2 显式激活，避免本阶段悄悄改写旧恢复格式或引入对象数量限制。Editor 唯一入口已更新，失败显示既有本地化恢复错误。

最终证据：tmp/anchor-codec-final.log 全量 **2808 tests、0 failures/errors/skipped**，compileDebugAndroidTestKotlin 成功。回归包括并发 assign/restore 竞争、线程异常捕获、重复归属、复制／多次快照、七／六类型与 pending 顺序、连接器绑定、精确版本、PDF 缺失／歧义、仅笔迹副本、源 hash 不变与借入文件不删除。真实 RED 证据保留在 tmp/anchor-model-review-red.log（再次快照误读实时层）、tmp/anchor-codec-review-red.log（PDF 与版本）和 tmp/anchor-pdf-ambiguity-red.log（引用式底稿被内嵌字节覆盖）。独立代理 review_input_plan 对计划与实现多轮只读复核，全部必修已处理。

ReferenceCodecAndroidTest 已加入 PenTestRunner，用真实 Android PNG／图片头探测和平台身份解析验证往返及坏图片拒绝，**仅编译，未执行**。真实 SAF、设备、进程中断、GPU／延迟与完整 UI 未验收。Source 选择、预览、往返、反向引用和网络仍按 C1/C2/D 保留；不把本基础当作引用闭环完成。
