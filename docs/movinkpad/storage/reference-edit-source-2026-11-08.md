# 活模型来源与保存回执（2026-11-08）

用户要求：持续推进思维导图和文件关系网络，复用原生模型/保存，可靠保存和恢复；只编译测试，不打包 APK。
状态：R2b2b3a 已实现；295 套件 / 3216 项主机测试通过，Android 测试源码编译成功，独立复核通过；真实平台/目标设备未运行。
本轮边界：源码、验证、文档、本地提交；不打包、安装、推送、合并或发布。

## 依赖与当前实际行为

0257bd59 已将真实打开接入同源 PreparedNativeOpen。actual模型和已读版本可以准确配对，但绑定仍由已关闭的准备对象持有，只描述原来的读取，不能把它当作之后保存成功的新版本。普通写入仍通过旧 publishNote，没有发布回执与会话原子配对；旧 SessionStore/CanvasSessionStore 未切换。不能半接缓存并据此自动覆盖来源。

R2b2b3a 为实际 producer 接线先补同一活模型的来源拥有者与保存请求合同；随后 b3b 统一实际 native 写入/缓存捕获与旧缓存恢复，c2 统一另存/自动分叉/merge 的 copyScope 与持久切换。此片不是整体保存恢复已完成。

## 规格

- 新增小型 NativeEditSource（名称按实现可调整），只从 PreparedNativeOpen 已采用的 actual 模型签发，不接受任意 model+SourceVersion 或 JSON tuple 构造。保持源版本、实际模型身份、kind、UUID 与规范位置配对；独立但同 UUID/页面/正文的模型仍拒绝。
- 同次Prepared采用只memo签发一个长期context；重复获取返回同实例或拒绝，不能用原read版本重建/回退基线。上下文可实现为PreparedNativeOpen内的小型嵌套类，以私有实际解码路径与memo约束签发，不做泛用factory。
- 从准备对象中分离长期活模型来源上下文；Editor 采用成功后保存此上下文，准备对象只负责一次性的资产/租约清理。共享视图继续通过 documentOwner 使用同一上下文；新建、旧session、导入、copy/fork/merge没有显式已验证创建路径时，不能凭路径或FileStamp获得上下文。
- owner主线程准备一个原生保存请求时，由上下文校验actual模型/UUID/规范路径并调用现有 Document.snapshot/InfiniteDocument.snapshotForWrite，绑定当前已签发的base版本及不透明issuer。请求和快照成对产生，不提供外部 snapshot+version 随意拼装的工厂；不更改 dirty、历史、选择或视口。
- request内部保存不透明issuer和运行时代次；其publish使用固定的原DocumentCodec/CanvasCodec编码本request内捕获的snapshot，再调用原NotebookStorage.publishExpected，不接受外部candidate文件。没有第二套writer/SAF实现。
- 新的极薄Receipt为私有构造，只有上述publish成功返回后才签发，绑定本request引用/token与expected base。读取所得NativeSourceVersion、inspect结果、另一请求/另一模型在同UUID/位置的真实receipt均不能ack当前请求。ack校验issuer、当前代次、实际模型、kind/UUID/位置和receipt请求关联后递增运行时代次；即使no-op发布SHA不变也废除同base所有旧票与重复ack。
- Note publish在长hash/编码前取得原PDF租约，核对snapshot实际PDF存在状态与SHA等于原来源，再用原codec保留该来源原本的embedded/external包装方式；不能让模型换了PDF、旧resource字段还在时把错误页背景写回。严格NativeSaveContract仍执行，失败无成功回执。
- 实际模型替换/关闭/释放时显式退役上下文；共享借用视图关闭不退役仍存活owner。capture/新publish/ack均拒绝retired上下文，不只是Editor丢掉引用。已经进入原存储提交的IO可为原目标完成，迟到回执不能改变新模型或当前context；不承诺回滚在途写入。
- 保存期间同一模型增加内容仍可接受已确认的base回执，表示当前工作模型是该基准的继续编辑；不能清dirty或覆盖其间笔迹/来源。模型或UUID/路径换掉、上下文已前进时拒绝迟到结果。无回执（取消、部分写、DB失败）时不前进。
- 不把SourceVersion摘要大小/渲染contentVersion/bodyRevision当作两份已编码正文谁更新的证据，不推断合并。现有snapshot共享已发布item状态的契约保留，不宣传整文档深冻结。
- 来源上下文没有缓存来源元数据的恢复构造。将来原子缓存恢复必须先校验 head/body 的类型/UUID/页契约，再读取真实来源并核对缓存记载的base SHA/kind/UUID/规范位置，才能签发恢复绑定。旧body+sidecar配对不可靠者保留为不自动写原文件的恢复草稿；不能用当前文件版本替旧缓存虚构基准。

## 接实际 producer 时不可省略的时序（b3b/c2）

- 原生任务先等待旧写完成，再在owner主线程核对原逻辑文档仍当前并冻结新快照/来源请求；不能在排队前捕获旧base，再拿已过时payload覆盖后来成功的版本。
- native成功receipt在主线程更新该模型上下文后，再捕获当前live模型与视口/reserve缓存请求；后续成功须使旧cache请求过时。native失败保留base/dirty，不把FileStamp当校验。
- Save-As B须在写B前持久记录“复制进行中、旧A缓存不得自动写回A”的状态；成功后投影/采用PageReferenceCopy并把当前live B与真实B回执原子配对。B已落盘但旧A cache仍存在、或后续cache失败，不能靠内存换URI解决。明确未采用工作副本和恢复，不把A正文重新贴B身份。
- 旧缓存损坏/来源冲突、分屏未采用的恢复草稿不能被新的普通clear/模式切换静默清掉；保留资料，显式处理。画布lastView走metadata，view-only不得从无关dummy文档提取正文/身份。
- 缓存如用portable PDF正文，只有真实body变化才重编码；如改引用式缓存，必须先具备head切换/读取的durable pin与PDF lease交接。预览不是备份。

## 本片验收

- note/canvas从真实prepare/adopt签发，独立同UUID模型、错种类/UUID/位置、未采用准备和外部拼装拒绝；正常树/笔迹样本与页身份不变。
- 真实snapshot+strict publishExpected+receipt接受与下一次保存，保存期间编辑/dirty保留；普通关联、视口/选择不改历史。
- 同一base两个请求只允许当前确认前进，no-op同SHA仍废旧票，旧/重复/foreign issuer/错类型位置/另一模型真实回执拒绝，read版本不能作receipt；取消/DB失败/来源同UUID外部变化不前进也不写错原件。
- Prepared唯一签发、retire后重复取不能复活、Editor实际模型替换/关闭/共享拥有者保留的场景覆盖；真实打开后取来源上下文的仪器场景继续保留，仅编译时标未执行。原生保存、会话适配、copy持久切换、页来源/返回/反向索引/网络与A2都继续保留，不用本片把后续需求取消。

## 实现与证据

实现为 PreparedNativeOpen.EditSource 与 SaveRequest/Receipt，限定在一个文件内保持私有实际解码来源和 memo。每次采用仅一个context；Editor存长期context，已关闭Prepared的临时租约/目录拥有者不再作为长期baseline对象。实际替换/关闭/释放会retire；共享owner仍保留时继续有效。MainActivity普通Save失败后的SaveAs fallback也重新检查原模型，避免旧A失败给新B弹选择器。

请求自己调用原codec编码自己的snapshot，再调用原publishExpected；没有外部candidate参数、没有从读版本签receipt的工厂。Receipt只有真正存储成功后生成，携request/expected私有关联。接受同时核对issuer、运行时代次、base对象身份、当前模型与目标身份；代次在成功后前进，no-op SHA也不能复用旧票。只更新base，不清dirty，不改当前笔迹、历史或视口。

PDF lease先于实际PDF散列/编码；实际文件有无和SHA与原来源匹配后才编码。包装模式取准备时的身份，嵌入原件仍嵌入，共享原件仍引用同一受管对象。变更模型实际PDF但保留旧resource字段会在目标写前拒绝。失败清理保留primary/suppressed；确认提交后单纯lease清理失败只记日志，不隐藏实际receipt。已有pending的未知保存仍不自动当作此请求成功。

新增 NativeEditSourceTest 16 项：模型/快照与页/笔迹身份、连贯两次真实发布、另一模型同UUID/location真实回执、独立模型/错类型UUID位置、保存中新增内容保留、退役/不复活、DB和取消无回执、同UUID外部修改零写、画布视口/dirty、PDF实际更换拒绝、真实no-op同SHA废旧票、PDF验证期GC保护、清理失败primary及成功回执、embedded包装/原字节、未采用拒签。

no-op用例只在fixture规范ZIP时间字段，仍真实编码、stage/readback/登记/清日志；确认两个不同请求的真实回执SHA等于原base，接受其一后另一个及重复回执均拒绝。首次缺API红灯 tmp/native-edit-source-api-red.log；tmp/native-edit-source-first.log 一项fixture未修改正文所以发生真实no-op，beforeWrite没触发，随后先加一笔确保“保存中新增内容”用例确实写入，并核对磁盘一笔/活模型两笔。

最终 tmp/native-edit-source-final-full.log：295 套件 / 3216 项 / 0 failures/errors/skipped；:app:compileDebugAndroidTestKotlin 成功。独立复核通过，并采纳embedded PDF补强建议。VerifiedNativeOpenTest另增2项 actual Editor上下文退役和共享owner保留仪器场景；这两项及前轮8项仍仅编译，未执行。

**生产普通writer仍未切换到新request publisher，SessionStore/CanvasSessionStore仍未迁移。** 当前接口已通过真实storage调用测试，Editor真实打开已持有新context；这不代表现有自动保存/暂停恢复/另存已全部采用新事务。b3b必须同时处理native成功后缓存捕获、旧cache来源验证和模式/关闭票据；c2必须处理copy进行中的持久保护和新文档/页面scope，不能省略。

后续producer还要覆盖所有替换调用（包括键盘newNote等旧同步入口）的保存等待，避免先retire再启动尚未捕获的旧写。现生产旧writer尚未用context，因此本片不借内部publisher声称这些调用已完成迁移。用户授权的PDF插入/合并背景等现有操作需要显式来源变更事务，不能为使普通保存通过而放松原PDF核验。旧缓存与已写后失败的放弃语义仍保留，不假定用户已经答复。
