# 页面引用基础实现（2026-11-08）

用户要求：按已确认的页面级引用决定继续导图开发；先同步并核实新渲染架构，保留已有功能和笔迹数据。
状态：R1a 模型/格式调整已实现，全量2971项JVM与Android测试源码编译通过；独立修复复核完成。页面来源闭环未完成。
本轮边界：本地源码、测试、文档与提交；不打包、安装、推送或发布。主线同步另见当日渲染续作记录。

## 本片范围

- CanvasItem 及七种对象撤除 ReferenceIdentity；InfiniteDocument 撤除所有锚点状态，继续保存 BundleIdentity.documentId。分页只保留 Page.referenceIdentity。
- DocumentCodec 的 reference_id 只位于直接 pages；快照保留原页身份，新页由模型拥有者在 snapshot 前分配，writer 不赋值。页面深复制的新身份与原页分离；移动/重排/恢复原页不重铸。
- 原字节升级 AnchorManifest 只接受 xnote 并只给直接 pages 插入字段；笔迹、未知对象和非 manifest 负载原字节保留。对象数不占200000页面身份限额，UTF-8/JSON/ZIP/取消/摘要等安全边界保留。画布不需要升级候选或锚点标记。
- 未交付的对象标记改为 xnotes-page-anchors/version1，条目名仍为 xnotes-anchors.json；从未打包的对象锚点实验格式不承诺读取兼容。旧的无锚点正式笔记/画布格式保持兼容。
- AnchorReadState 只核对页面。坏图、未知 kind、无效 Tape 等不再因对象身份导致整页拒绝；PDF 原件一致性/存在性、页面缺失/重复与结构/版本检查继续保留。
- readInkCopy 的遗漏数量改由原始 item 出现次数减最终物化成员数；两对象共用同一坏资源计两次。抢救探测有尺寸的坏图片，不把尺寸视为图片仍可读的证据。
- 仅抢救模式略去不存在图层上的对象并计数；普通打开保留原校验。GlueRead 最终只绑定当前页面成员，避免连接线指向已略去对象。抢救不修改源包/借用 PDF，副本生成新文档及页面身份。

## 验证

新 PageReferenceContractTest 的三个初始反例在旧代码上均失败（tmp/page-reference-red.log）：对象被写入身份、200001对象使页面升级超限、无对象身份阻止页面读取。修复后专项58项通过（tmp/page-reference-focused.log）；初次新增测试夹具修正了0.7压力的既有float编码误差断言以及extension键名，不改变生产采样编码。

独立复核发现坏层归属仍阻断抢救；新增即时对象、pending图片与坏层shape/幸存connector反例先失败（tmp/page-bad-layer-red.log），然后加入过滤与最终成员绑定。复核又发现提前过滤即时对象使pending下标失效；tmp/page-pending-order-red.log先复现IndexOutOfBoundsException，再将过滤推迟至全部pending插入完成。最终 tmp/page-reference-full.log：274套件2971项通过，失败/错误/跳过均为0，Android测试源码编译通过。独立只读复核确认两项问题已解决，未发现当前生产diff新的确定性阻断；未代跑测试。

Android仪器源码保留实际PNG/损坏PNG、平台JSON路径；只编译不代表运行。MovinkPad Pro 14 / Android 15 / Pro Pen 3、真实SAF、笔输入、IME、GPU及窗口验收未运行。

## 后续不可遗漏

R1b：BundleMigration/BundlePdf/Editor.copyFileTo等整包复制或另存时，除新文档UUID外也重铸页面UUID，必须按原字节转换而不是重编码笔迹；同一文档便携导出与独立副本需区分。此前“改docUUID保留成员ID”的旧方案已撤销。

R1c：.xmap来源契约撤除对象/固定区域身份，页面可附可选矩形定位提示；画布只整份文档。R2登记/SAF/pending安全激活仍未接入，R3页面来源选择、多来源预览、跳转/返回、反向引用与R4/R5网络继续。未向用户文件自动启用此格式，未宣称完成可用闭环。

## R1c 来源范围契约

.xmap 的 MapSource 现在仅有 FILE/PAGE；PAGE 必须为 canonical UUID，可附页面局部矩形，修改提示不改变引用身份/主要来源/正文。撤除 objectIds 与 includeAnnotations 模型属性；来源不再把对象范围、固定区域或含糊批注上下文当成目标。

读取旧v2的FILE来源兼容缺失或空objectIds、缺失或布尔false的includeAnnotations；显式null、错误类型、非空对象列表及旧OBJECTS/REGION/PDF_REGION范围拒绝，不猜测重定向。写入仍为v2，旧开发版读取器要求旧字段，不能保证读取新版来源；v1无来源读取保持不变。本分支从未打包，此调整不声称提供对象实验格式兼容。

新增6项PageSourceFormatTest，初始3个反例在旧代码上失败（tmp/page-source-format-red.log）。覆盖FILE/PAGE有无hint及旧字段缺失组合、wrong type/null、UUID、多个来源metadata/顺序/主要预览保持；Android仪器增平台JSONObject.NULL用例，只编译。专项通过后全量 tmp/page-source-format-full.log：275套件2977项通过，失败/错误/跳过均为0，Android测试源码编译通过。独立只读复核未发现阻断或兼容遗漏。

页面实际是否属于该文档、是否仍存在、画布禁止PAGE来源以及跳转，仍由后续真实来源解析负责，不能把本片JSON模型验证当成来源已可用。
