# 原生来源版本凭据（2026-11-08）

用户要求：继续原生思维导图/文件网络，保留页面身份、会话与已有资料；来源变化不能让过期编辑覆盖原件；允许编译测试，不打包。
状态：R2b2b2a 存储 API 已实现；292 套件 / 3163 项 JVM 测试通过，Android 测试源码编译成功，独立复核完成。真实 Android/目标设备未运行；Editor/会话尚未接此 API。
本轮边界：源码、测试、文档、本地提交；不打包、安装、推送、合并或发布。

## 为什么需要此依赖

R2b1b普通保存验证UUID/类型/页契约与PDF，但同UUID外部新版仍可能接受过期候选。Editor只记FileStamp（大小/mtime）不构成内容证明。新WorkingSessionCache只能证明内部正文与metadata是一对，不能证明native来源没变。恢复后直接按当前位置重新记stamp会丢失原先打开的版本。

## 本片 R2b2b2a：版本化源快照与带预期版本的存储发布

新增内部不可变 NativeSourceVersion，包含规范location key、document UUID、NOTE/CANVAS类型、完整容器SHA-256；身份与内容指纹分开，不用文件名/页码或mtime猜测。新API不接xmap，不改变现有文件格式或添加对象编号。

NotebookStorage提供IO专用、AutoCloseable的源快照：从真实target流一次取得BundleSnapshot，完整核验BundleReferenceInfo/位置及UUID契约/文档类型，版本凭据与file来自同一私有容器。外部PDF在读取期间持有现有资源租约，不删除或复制原件。快照关闭仅清本次原始容器和释放租约，不拥有decoder所创建的图像/音频/模板，也不清理借入资源。调用方须在use内解码；提供显式校验模型UUID/页契约/页面集合的函数，Decoder在成功后管理其资产，取消/模型未采用由调用方既有资源路径处理。快照不能在关闭后继续校验或读取文件。

这里的版本表示实际读到的完整字节；不宣称打开后文件永不再变化。来源在读取后变化，由下一次带版本保存的摘要预检拒绝；大型PDF不为建立凭据再复制第二个原件。

新增必须接收expected NativeSourceVersion的普通保存API。先核对目标规范位置/种类/UUID/源快照摘要；外部同UUID改动、URI指向另一份文件、类型或UUID不符均在stage/target.write前拒绝，不生成或清除已有恢复记录，不覆盖源。URI别名使用同一规范location key。已有pending仍必须先显式恢复，不能把新expected当作无条件resume授权。

通过前置检查后仍用现有完整候选/原件journal与发布验证，不能退回裸覆盖。保存结果返回实际验证过的staged candidate版本凭据，不能在发布后随意hash调用者原candidate来制作凭据。只有发布+读回+PDF pin/登记+清journal成功才返回；失败保留原/候选/当前文件的既有保护。旧publishNote保持原API行为与Unit返回，不让未改造调用方误以为已获得来源版本保证。

补storage类型化“来源已变化”提示及三语言资源/穷尽映射。此片为存储接口，不把所有Editor保存或SessionStore视为已经接通；实际接线需携带model owner epoch，并且迟到回调只更新原拥有者的对应来源。排队的旧快照只有在同owner/同来源epoch的已验证连续提交中才可采用最新receipt，不采纳别的模型或外部同UUID版本。

### 验收

- note/canvas同源快照与SHA/类型/页编号校验，source实际读到A后外部变B仍只返回A的file与凭据。
- FILE/pipe流回退、读取/校验取消失败清本次snapshot；不删源/共享PDF，外部PDF租约覆盖生命周期。
- 错kind/UUID/marker/模型页集合拒绝，不修改原模型；关闭后不可用。
- expected来源A时外部同UUID内容B、位置换文档、类型不符、SHA不符、别名位置，均有零写/零新journal证据。
- 正常带版本保存返回真实staged candidate摘要；发布时原candidate被替换不伪造receipt，DB/部分SAF/取消失败不返回成功receipt。
- stage后目标A→B仍由原publication检查保留B和journal；不宣称SAF provider具备跨进程原子CAS，受管文件沿用move前复核。
- JVM/Robolectric和Android仪器源码；真实SAF/Android平台与MovinkPad待另行运行。

随后统一审SessionStore/CanvasSessionStore/Editor的源快照打开、保存receipt与cache恢复，以及旧缓存保留/来源冲突入口；再接另存/fork/merge的copy scope与输出流打开前保护。尚未收到答复的部分保存后“放弃修改”决定继续只阻塞对应回退/清理，不阻塞本片read/expected-save接口。

计划审核已通过，接受补充：NativeSourceVersion使用私有构造器、无通用copy，签发必须完整检查实际文件并计算其SHA（只收原始File而非任意UUID/hash tuple）。读取端再与BundleSnapshot已捕获SHA核对；发布端只对同份retained candidate签发，待发布/登记/清journal完成才返回。

源快照提供retainPdfForAdoption，取得独立资源租约，调用方在use内保留并在模型持久pin/采用完成后关闭；不得在use外才开始保护借入PDF。嵌入原件通过快照的admitOriginalPdf接已有PdfResources的admit+校验+hold同gate操作，避免新managed PDF在admit与取得lease之间被回收；模型decoder资产不因此被源快照自动删除。关闭只释放其自己拥有的租约/容器，重复关闭无副作用，version值仍可使用。模型校验仅声明UUID/marker/页集合一致，不声称比对了所有正文对象。


## 实现与最后验证

`NativeSourceVersion` 及其 `Inspected` 证明对均为私有构造，无通用copy；工厂对实际文件完整解析并前后核对SHA。`NotebookStorage.openSource` 提供拥有原始容器和临时PDF lease的 `NativeSourceSnapshot`，`publishExpected` 对规范位置/UUID/类型/实际SHA进行前置检查，回执只取retained candidate，在发布、登记、清journal完成后才返回。旧 `publishNote` 仍为Unit，尚未改造的调用方没有被冒充为已具备SHA版本保护。

独立复核的两处组合问题已按反例修正：DocumentCodec给pdfStore单条目、不关闭底层ZIP的借用视图，消费者关闭view后仍能读取identity/manifest/图片；原先直接传ZipInputStream的做法会被资源admit关闭。Header comment/Zip64回退可能在返回前执行长顺序校验，因此新增 `readWithPdfProtection`，从有界目录metadata取得的SHA集合只用于提前临时保护，之后仍完成所有local/中央关系、完整ZIP/页面契约与源摘要验证；它不授予文档身份或登记权限。Hint后失败释放lease、清本次snapshot并保留原因，不清来源或decoder资产。

PDF校验/lease获取及新嵌入原件admit+expectedSHA+hold在同一resource gate完成；独立handoff lease需在模型持久pin/采用完成后关闭。模型核对除UUID/marker/page集合外，还比较实际PDF文件SHA与Info.originalPdfHash，支持取消；v1陈旧pdfResource不虚构原件。未对普通正文重编码比对，不宣称所有未知/坏对象都拒绝，继续页面级决定。

新增21项JVM/Robolectric回归，包含同源容器/流关闭、模型不匹配、legacy/canvas、取消、同UUID外部修改、位置/种类/身份不符零写、URI别名、原caller candidate变化不伪造receipt、DB/部分写失败/旧pending、stage后外部变化、PDF lease/采用/GC、嵌入原件指纹拒绝、真实codec组合、Header内GC及hint后失败、模型底稿不一致和v1陈旧字段。

红灯：`tmp/native-source-version-red.log`（缺接口）；`native-source-codec-red.log`（真实组合Stream closed）；`native-source-validation-gc-red.log`和`native-source-header-gc-red.log`（校验期保护不足）；`native-source-model-pdf-red.log`（同UUID/page但不同底稿）；`native-source-hint-error-red.log`（hint后错误未本地化）。首次租约测试的两项失败源自resolve会刷新7日保留时钟，测试已在handoff后再次把资源置为可回收，确保保护来自lease而非grace，未修改回收策略。

最终 `tmp/native-source-version-full.log`：292 套件、3163 项，失败/错误/跳过均0，`:app:compileDebugAndroidTestKotlin`成功。新增3项Android provider/PFD/SQLite/PDF lease/真实PNG与entry流组合测试并注册固定runner，只有编译证据，没有实际运行。未打包/安装/推送，未执行真实SAF、设备或GPU验收。

独立最终复核确认Header回退、资源移交、受控签发、底稿指纹和失败清理无剩余阻断。API组件验收完成，不扩大为所有Editor/Session/new-save/fork/merge入口已改造。下一步必须把原生成功receipt与会话捕获时序接起来，避免正常并发保存形成过期base；另存切换必须阻止旧cache恢复后写回原文件。
