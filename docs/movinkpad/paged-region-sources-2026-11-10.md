# 分页固定区域来源创建与往返（2026-11-10）

状态：本阶段源码／相关主机与 Android 测试源码编译 verified，独立复核完成；整体 active，未完成。
基线：d2381a33，工作树 mind-map-region-continue，核验时 clean。上一 goal turn 为 progress：Canvas 区域完整流程实现／验证／本地提交。已知旧构建全部终态，不重启。
授权：实现、相关编译／测试、文档、本地提交；不打包 APK、安装、推送、合并或发布。

## 本阶段可观察结果

分页笔记／PDF 批注笔记的实际选择／套索和截图菜单提供“引用此区域”。入口固定实际模型／owner／Page 与页局部 Rect，复用 PageReferenceRequest 的保存版本、页启用／重开保护、已有／新图与已有／新概念、一步导入及失败只重存。保存 `.xmap` 重开保永久 pageId 与 region，刷新按固定范围裁切，来源打开仍由永久页 ID 定位，返回同一图／相机／选择／具体来源。

本阶段保留既有 PAGE＋region 格式，不新增文档或对象身份。区域指向当前这份笔记（PDF 时含此份笔记的已保存批注）；显示明确说明，不能称为共享原 PDF 的独立来源。原需求 #7 的“原 PDF 原文或含当前笔记批注”两种模式仍保留：独立原 PDF 内容投影／指纹／定位语义紧接本片设计和实现，不复用已废弃 includeAnnotations 字段，也不将资源 hash 当文档 UUID。本片是创建入口与页绑定的完整纵向切片，不代表 #7 或完整引用闭环完工。

## 合同与边界

- `PageReferenceRequest` 只新增可空不可变 region，整页调用默认 null；区域标题使用已有 region 字符串。`requestPageReference(page,region)` 最终核实际 page 成员、owner／session／模型／scope／初始页身份、有限正矩形及笔／内容事务，不确认或取消未完成预览。
- 来源准备在同一固定原页上生成 PAGE＋永久 pageId＋region；页重排后仍原 page，删除／替换退票，复制得独立页身份。最后目录仍匹配实际保存 B，C1、源外改、共享拥有者与旧 picker 保护不放宽。无路径或页启用仍按现明确另存／重开／重新选择原页或区域，不给旧模型按页序号补 ID。
- 选区可能跨页：须确认所有已选实际对象只属于同一 Page，归属不明／跨页拒绝；实际几何取 settled OBB／内容包围框，不取带旋转柄留白的菜单 rect。原 Page／内容坐标→页局部使用真实状态变换，支持四种视图旋转和页边距。
- 截图 world rect 可跨页／gap。正常结束时冻结实际 document、唯一完整包含它的 Page 与页局部 rect，菜单使用这份候选；不能在菜单点击时将旧 world rect 重新绑到后来重排的另一页。跨页、纯 gap、归属含糊或超范围显式提示重选。新截图／取消／换模型清候选，页删除拒绝，重排不换页。
- 菜单回调先签实际请求，成功才关闭菜单／清截图；拒绝保原选区与菜单。原始事件不改写，不增加内容 history。尺寸字号／触控区／三 locale 沿现规则。
- Note 普通读取可能跳过未知／缺附件对象。实际完整解码观察必须来自本次原始读取／有序物化对象，默认宽容行为保持；区域预览不以丢项模型证明 EMPTY，区域准备在自动 flush 前拒已知不完整来源，不能借引用洗白。补 raw unknown／缺附件真反例与旧 PNG 保留；不迁移整个 native4。
- 预览保持固定 frame，不按剩余内容重新取景。普通空纸／网格／隐藏层不冒充内容；只有完整成功读取且能证明当前区域无内容才给 EMPTY，PDF 原画面／模板／排版正文有内容或无法安全证明时保守 CURRENT。页删除仍 DELETED，失败保旧PNG并标历史。固定空范围仍可定位；超当前页面范围沿原OUTSIDE提示，不改指别页。
- 区域来源打开的 NOTE 首读与最后来源变化后的重读也启用实际完整读取；unknown／缺附件不得采用部分模型或隐藏图。该开关仅原不可变区域请求使用，普通 NOTE 与整页来源默认行为不变。
- 相关分页 Locator 进入宿主后未完成两稳定帧就卸载也须退休，首次尚未挂载的请求可以等待；新 DOWN／模型替换不改活动笔画相机，不写 lastView／正文／选择 history。

## 实现分工与验收

root 定规格／共用Flow说明、Main入口、实际Controller→保存重开→预览→往返消费者、证据／提交。
relation_network：InteractionController 的实际选区／截图归属和冻结候选，小只读 PageSourceRegion（actual Document／Page／Rect）DTO、事件／旋转／跨页测试；不碰 Editor。
relation_navigation：PageReferenceRequest／Editor 源请求、region 传递、NativeReferenceLocator 与对应 Host 回归；复用现严格版本和保存，不改旧授权。
relation_evidence：NativeDocumentPreview／MapSourcePreviewBackend 的固定页框和保守空区，PNG／层／PDF边界及派生history消费者回归；不改身份格式。
独立 reviewer 只读审计划及新差异，不派生代理；root 统一 Gradle，作者不提交。

验收真实同页选择／套索与截图、四旋转／缩放／负 margin、跨页／gap拒绝、捕获后页重排／删除／复制／换模型、共享窗格未完成预览、保存B与晚C1／源冲突、已有／新图和概念一步undo、失败retry不重复、实际PNG固定frame／空区／旧图、保存冷重开和按永久页定位返回。保原整页、Canvas区域和源码编译回归，不重复未变全仓。

真实Android／SAF／Compose／配置重建／杀进程、MovinkPad Pro14／Android15／ProPen3、Surface／GPU／性能未运行，必须单列，不用JVM或源码编译替代。

## 本机验证命令与结构

沿现 JDK21／Gradle Wrapper 及本工作树独立输出、project cache；不运行 assemble／安装。Kotlin源位于app/src/main/java，主机回归app/src/test，Android事件app/src/androidTest；临时证据仅tmp。

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ANDROID_HOME=$ANDROID_HOME ./gradlew --offline --no-daemon --project-cache-dir /private/tmp/xnotes-mind-map-region-continue-cache -I tmp/build-mind-map-region-continue.gradle :app:testDebugUnitTest --tests '*Paged*Region*Test' --tests '*PagedReferenceLocatorHostTest' --tests '*PageToMapFlowConsumerTest' --tests '*EditorCurrentPageReferenceHostTest' --tests '*MapSourcePreview*Test' --tests '*NativeReferenceHostTest' --tests '*MapSourceNavigatorHostTest' --tests '*MapSourceNavigationSessionTest' --tests '*PreparedNativeOpenTest' --tests '*NotebookIdentityReadTest' --tests '*DocumentCodecTest' --tests '*ReferenceCodecTest' --tests '*PageReferenceContractTest' --tests '*LayerCodecTest' --tests '*FlowXmlTest' --tests '*FlowXmlTableTest' --tests '*MarkupsXfdfTest' --tests '*SharedPdfCodecTest' --tests '*RoomCodecTest' --tests '*PageSourceFormatTest' --tests '*CanvasRegion*Test' --tests '*CanvasReferenceLocatorHostTest' :app:compileDebugAndroidTestKotlin
```

核心接口保持既有Kotlin实际对象与不可变几何风格：`PageSourceRegion(actualDocument, actualPage, fixedLocalRect)`，页面定位只看永久ID；XML观察只报告明确解析失败和实际drawing items存活，不冒称未来扩展完整无损。raw flow／XFDF存在但无法证明区域内容时保守CURRENT。作者统一冻结后固定输入SHA，运行终态后逐case核结果，旧有效未变测试不重复执行；非确定性窗口及GPU验收仍独立。

## 实际结果与证据

完整入口已接：实际单页选择／截图→固定 Document／Page／local Rect→现永久页身份与严格已存版本→已有／新图及概念→一次undo／失败仅重存→正式PreviewController PNG持久化冷重开→原生实际页来源／原图返回。PDF身份为这份批注笔记的UUID／页UUID，原件SHA只作资产校验；原PDF字节在严格保存Host中保持。未新增格式版本或对象编号。

新增37个主机方法：controller11、完整读取5、PNG预览6、派生预览consumer2、区域request7、严格来源打开2、locator1、实际Main／Flow往返consumer3；Android菜单2个事件仅源码编译，已登记Runner。

- 50814 首次编译：生产通过，root新夹具缺extension import及把Unit openAsync当Boolean，测试未执行；只修夹具API。
- 67148 三项行为RED：临时移除外发DTO的selection代次／screenshot身份约束并恢复旧Locator，三个新用例确实抓到clear后旧候选复活、重选同对象ABA、进入宿主后未显示的定位跨卸载重播。终态后立即恢复修复文件。`tmp/paged-region-behavior-red-recheck.log` 与 `-behavior-red-summary.json`。
- 54454：281项279通过，Android测试源码编译通过；1121个Kotlin／Java／资源／构建输入运行前后全同。两项新夹具失败：普通PEN按既有契约不写时间采样；测试切分页模式后未relayout即记相机。
- 93555仅复验两项：改用实际保存时间的SPEED配置且保t=10断言，完整来源往返通过。Locator剩余仅Double对象比较将0.0／-0.0当不同；改为三轴delta=0.0精确数值比较，所有页号／轮廓／正文／history／零写断言保留。
- 30768仅该Locator用例终态成功。按逐case替换保有效279项＋source-flow1＋locator1，合并 **281项，0失败／错误／跳过**。最终1121输入全同，最后两轮仅两个unit夹具变化；生产／Android证据保持有效，未重复跑有效项。`tmp/paged-region-{final,recheck,locator-recheck,verified}-summary.json`、相应日志与 `-locator-recheck-inputs.json`。

独立计划／实现／证据复核通过；Required完整性／actualPage冻结与外发DTO生命周期均闭合。raw unknown／缺asset／明确XML失败不能证明EMPTY；区域预览及source首读／最后重读拒部分模型，失败保旧PNG／工作区，普通Note打开保持宽容。rawXML存在、PDF画面与自定义模板不轻率标空；空白页／网格／隐藏层可以安全判空，旧图明确标历史。

未运行：真实Android／Compose交接／DocumentsUI／SAF、配置重建与杀进程、MovinkPad Pro14／Android15／ProPen3、掌触／Surface／GPU／性能。未打包APK、安装、推送、合并或发布。下一真实流程是原PDF原文独立内容模式，必须另核共享原件与具体批注上下文，不用已废includeAnnotations，不把PDF指纹当文档UUID；其余原14组需求保留，不以本片替代完整#7／全产品验收。

分页本地提交：`69ccde43`（完整读取／固定预览）、`d5c8fcc2`（实际入口／请求／定位）、`40ce03b5`（主机与Android菜单测试源码）。
