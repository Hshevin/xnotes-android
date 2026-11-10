# 导图与分页文档双窗格（2026-11-10）

用户要求：按完整14组思维导图需求持续实现；在当前新工作树接通实际双窗格，不以全窗切换或预览替代。
状态：已实现一Map＋NOTE/PDF核心流程，30套196项有效Host及Android测试源码编译通过；独立消费者／原始XML及输入链复核通过，无剩余Required。真实平台待验。基线e49d9273，旧243项不外推本阶段。
本轮边界：仅实现、相关编译测试、文档、本地提交；不打包APK、安装、推送、合并或发布。完整范围不缩减。

## 用户流程与范围

这一片交付一张实际Map与一份NOTE／批注PDF并排编辑；物理左右／上下可调，两个逻辑槽可交换位置。笔记的分屏／在旁边打开和窗格首页可选择Map；全窗Map也可显式在旁边打开分页文档。来源子图关闭／Back先在同一Map槽返回父图，仅根Map关闭进入该槽首页，关闭首页才移除槽；Native关闭文档／首页沿原规则。保各自内容、撤销、相机和未保存状态，Map与Map的既有来源父链不丢。

Map来源若已在另一侧打开则定位原owner／原模型；未打开则经另一槽已有保存／放弃／取消保护后读取并替换该槽，图持续可见。Canvas只单文档，明确拒绝混合分屏，不把Canvas／OriginalReader／文件网络伪装分页槽。Map里打开Canvas仍走原有全窗导航和全部相关owner的保存／放弃／取消保护，成功后原分页owner已被关闭，不承诺恢复其模型／历史。返回图明确为Map＋文件首页，保Map原物理位置；Canvas当前模型按已有Home暂留政策放在首页背后，后续打开／最终关闭仍经原保存保护，不把Canvas表面挂入混合窗格。默认Canvas采用位于primary，故返回布局的native/Home逻辑槽绑定实际Canvas槽，Map用另一逻辑槽，调整reversed／ratio保Map原物理范围，不能留下幽灵native槽。独立原PDF阅读器只停车，另存纯运行时原混合布局书签，明确返回才恢复实际仍有效的分页owner布局；两种返回票分开，不复用纯布局冒充关闭owner恢复。

分页页／区域引用流程优先提供“另一窗格的导图”，显式选择后仍使用同一MapDocuments actor、既有导入确认及saveAttempt结果；不能以另开后台模型覆盖活图。原请求／页UUID／来源基线和迟到picker规则保持。

Map+Map、同图两个视图、混合.xsplit显式文件与进程恢复仍是后续必需阶段。当前不能把混合槽交给native-only v1—2 writer／reader；本片至少在ViewModel保逻辑槽、焦点、比例、方向、Map Entry／presentation和源返回布局，配置重建不重开Map清历史。真正持久恢复将用带类型的新契约，不能将Map降为missing native。UI对尚不支持的保存分屏／组合给明确反馈，不输出错误文件。

## 已核实的接口缺口

- MainActivity.SplitHost只按Editor.noteOpen／homes选择内容，Editor.active／inSplit／focusPane也只认native，focusPane会确认文字和复制工具／HOLD，不能借用它表示Map焦点。
- MapWorkspaceHost的visible与nativeContent全窗互斥；MindMapScreen已有普通AndroidView，可真实限制在一个槽内，不能盖住on-top湿墨Surface。Native真实attach继续Editor.surfacesForAttach。
- MainActivity的graphicalSurfaceVisible全窗输入gate、Map无条件BackHandler、native焦点高亮须转为当前混合槽的实际输入／焦点。图收到的原MotionEvent不经Samsung改写、native latch／四指／键不得作用图。
- ToolbarDocument尚无MAP；COMMON/ALL_VIEW使用entries全体，新增类型时必须显式保native支持集，不能自动把native工具授予Map。混合时native工具都留该文档栏，Map保自己的工具和撤销；共用栏只留实际通用的工作区操作，布局不随焦点增删。
- NativeReferenceRequest／openReference目前按全工作区关闭其他native。须加窄的“指定分页槽、保布局”策略，默认原行为不变；保实际owner／模型／最后IO版本验证和同步采用，不能绕过现openNative固定读或只用文件名代替身份。

## 实施边界与分工

A：纯运行时MapSplitPresentation／MapWorkspace接线，绑定实际Entry与逻辑Pane、焦点、代次／返回布局；不保Editor／Activity／View。Editor与NativeReferenceRequest增加受限目标槽的来源准备／采用及纯焦点交接，不改变原单文档／native split默认。补实际owner与失败／取消Host测试。

B：MainActivity实际混合SplitHost、槽内Home、打开／关闭入口与主菜单保护、触摸／悬停／key／Back的实际路由；新输入route只composition持宿主，不入ViewModel。ToolbarLayout显式MAP支持与测试；新增用户字符串三套一致，样式用Tokens。复用既有几何与分隔条，不另建绘制引擎。Main修改由B独占。

root：MapSourceNavigator保另一槽策略与来源返回、MapHostHooks／MindMapScreen焦点和嵌入参数、PageToMapFlow提供已开Map目标；锁定A/B API后接线。统一编译、证据、文档及本地提交。

C：真实混合Host／Main输入／页引用／来源往返回归，Android仪器消费者源码；只新增自己测试文件与runner，等API固定后写，不修改生产。单独独立代理审核计划／实质实现风险；作者不能代替最终独审。

## 不变量与验收

1. 真实两个受约束AndroidView／槽内Home，导图与NOTE/PDF可实际编辑／独立撤销，比例／方向／位置及配置重建保持；新IO失败不先拆旧内容。
2. 焦点按实际DOWN／hover／title传递，手写中不把另一侧工具或变换切走；native对native原快照继承规则不变。Map手势原事件、全局key/latch/Samsung/四指gate、Back只作用实际槽，跨边界按DOWN归属锁住。双侧笔接触禁止开始互斥手势，不能用重写事件修正归属。
3. ToolbarDocument新增MAP不使native COMMON变通用，NOTE/PDF／Canvas已有组合、混合组合及失焦高亮回归；Map工具与Native状态独立，视图设置始终本地。
4. Map来源定位另一槽已开owner不保存或重建其内容；未开来源严格准备与最后同源核实、保存／放弃／取消和迟到IO。保持Map Entry／历史／相机／父链与另一槽位置。Canvas全窗往返按上述Map＋Home策略，Original只读往返才恢复原分页布局，失败不动混合布局。补A+NOTE→B+同NOTE→关闭B回A，核同NOTE模型／历史／物理槽。
5. 页／区域引用实际选择已开Map actor，保图正文与并发来源，新来源一次撤销；saveAttempt真实失败保已导入内容且重试不重复；旧页／picker／host回调不能错绑。
6. Map最后lease关闭失败留槽，其他owner的输入／autosave／epoch不退役；原生关闭／替换仍复用当前保护。关闭文档→槽内Home→移除槽。普通主菜单打开进入单文档，不能暗中复活分屏；未成功打开保两侧。
7. 保原Native／Map JSON及笔迹原字节、独立格式／身份、共享PDF租约；不借混合UI启用新native会话producer。不写不兼容.xsplit；配置布局纯runtime，进程恢复仍单列未实现。

先固定可执行旧行为RED，再按小片实现与相关验证；最终从原始XML核唯一用例与输入SHA，不拿编译失败的旧XML冒充执行。Android事件／重建／SAF、真实Surface裁切／GPU及MovinkPad Pro14 Android15 ProPen3仍须单列实际平台验收，未执行不能宣称通过。

## 计划独审处理

两项Required已明确：Canvas默认全窗会关闭分页owner，因此返回只声明Map＋Home并绑定实际Canvas槽；Original只读停车另保原布局。来源子图先返回父图、仅根图关闭进Home，补实际父链混合回归。另落实按下接收者的KEY_UP释放、目标槽实际Editor/owner/model及split代次、共享owner物理位置。MapScreen通过单一movableContentOf移动，保原系统picker登记，不生成两份输入宿主。

## 当前实现与RED证据

73043真实执行4项、3失败／0错误／0跳过，1162 main/unit/resource/build输入前后SHA相同；指定槽旧实现实际关闭另一侧owner或采用到错误root，native COMMON错误进入共用栏，另1项通过。原始tmp/map-native-red-{log,summary.json,results/,inputs.json}保留。A/B/root已按此修复，C新增8项真实来源／引用／关闭／恢复Host正在准备首编；未把未运行的GREEN计为通过。

新增普通工作区替换先以prepareEntryLeave实际saveAttempt准备，IO期间保原Map Entry／布局；Native最终同步采用回调才退休Map路由，资源释放IO后的晚ack不再关闭当前图。指定槽新NOTE继续openNative原固定读／同源重读和最终owner/revision/job proof。mixed专用runtime标志阻止暂停／关闭写入native-only .xsplit及错误工具复制。实际双窗格仍需本阶段源码／Host／平台各自证据。

## 最终实现与范围

本片已接真实一Map＋NOTE／批注PDF核心操作：分屏按钮后从槽内首页选图、文件「在旁边打开」、全窗Map显式旁开；实际受约束View、方向／比例／交换、独立编辑历史、焦点／Back／手写及按键松开、来源定位另一槽、页／区域引用优先已有Map、关闭文档→Home→移除槽。Map子来源先回父；全窗／槽内使用单一movable内容，不复制系统picker。普通打开先完成新文件准备，最后同步Native采用后才退休Map路由，晚清理不会关闭新图。

配置保Map Entry／历史／纯布局；Native依原逻辑槽的实际工作副本恢复。纯Hint在读取后、模型采用前核UUID／kind／canonical路径，缺／错时保恢复资料并退该槽Home；不先采用错误session或盲目重开URI。Hint不是来源版本或覆盖保存权限，不表示Native新会话producer迁移完成。正常Original返回仅恢复原opaque owner／model票仍匹配的布局，失效安全回单图；已匹配但返回IO期间变化时本次拒绝，再次明确返回走单图。

Canvas仍独立显示，返回Map＋实际Canvas暂留槽Home；明确Home权才可保存后替换为NOTE或关闭。失败事务仍保pending，不自动重复stage或清journal。失败／取消／成功使用各自真实场景验证，不能把它写成同一失败事务已自动续完；通用Native恢复及完整producer接线仍有既有未完边界。

外部PDF／Markdown在active／parked mixed期间保持pending并明确提示，布局真正移除后续入原导入流程。Native Ctrl+O／设置快捷键进入本槽Home／Preferences，不收掉图。根图关闭后Entry为空的MapHome仍受mixed守卫；未接通的pair／saved分屏入口不采用Native并留下幽灵槽。

**仍需继续的入口与能力：** 一次选择两个文件的Map组合、saved native `.xsplit`直接替换混合工作区、typed mixed `.xsplit`／进程恢复、Map＋Map及同图双视图。全局文件网络／原件库在混合存在时仍由已有全窗守卫拒绝，后续需接入停车／返回策略；不视为已完整支持所有混合导航。Canvas侧反向引用、精确恢复／完整备份及原14组其他项目不缩减。

## 验证、失败与证据

| 运行 | 实际结果 |
| --- | --- |
| 73043 | 4项、3个真实RED：指定slot定位关闭他owner、新NOTE错误采用root、native COMMON进入共用栏；1162输入前后SHA同。 |
| 27757 | 首次实现36项，34过、2失败；1164输入同。Canvas夹具误授永久页契约、慢读场景沿用Cancelled guard，按实际契约修夹具。 |
| 9377 | 30套193项、5失败；1168 main／unit／resource／build输入同，Android作者单独excluded。旧相关用例全过，剩新夹具／事务时序问题。 |
| 64397 | 3个整套28项全过，1234项KT／Java／XML和构建配置输入同；Android新增夹具直接调用protected onNewIntent编译失败，未冒称Android执行或编译通过。 |
| 10998 | 仅补Android源码编译：改为实际Instrumentation.callActivityOnNewIntent，compileDebugAndroidTestKotlin实际执行成功。0测试task／0case；1234项KT／Java／XML和构建配置输入同。 |

9377的5个失败均保原XML：Main raw各事件／轴断言已过，夹具漏清ButtonClick.touching，补实际CANCEL及前置候选断言；shared view必须等已接受的会话清理才可重join，保两次old票失效断言；Canvas写失败已留真实pending，普通save被正确拒绝，不能清journal来通过；失败明确核hasRecovery，另用fresh场景验取消／成功；Original返回竞态用真实LegacySessionQueue已接受IO屏障固定initial-match后实际替换模型，不靠调度猜时机。

最终按**整套**替换9377中已改变的3套25项，加入64397当前3套28项：**30套196个唯一case，0失败／错误／跳过**。9377之后仅3个对应unit测试文件变化；production／resources／build不变。64397到10998仅1个Android测试文件变化，当前1234输入集合与SHA逐项一致，范围为app/src内1229个Kotlin／Java／XML文件及5个构建配置；不包含未改变的C/C++、assets、PNG或CSV。两中文资源逐值相同，三套mixed_split.xml 9键与graph.xml 289键一致。

Host包含实际Main Window原始事件及松键、Native指定slot／共享owner／typed detach、text／flow纯等待取消、Canvas Home实际writer／pending／accepted IO、同UUID模型ABA、实际原文reader租约、页区域向同actor导入与失败保存、整池退休后的新actor冷开、实际persist→新Editor精确槽会话恢复。新增Android 5项源码分别覆盖真Main双View／重建及导入／快捷键／saved入口，**仅编译未执行**。

原始日志／XML／输入清单在 `tmp/map-native-{red,new,related,final,source}-*`；最终逐case来源 `tmp/map-native-verified-summary.json`、输入 `tmp/map-native-final-inputs-verified.json`、运行恢复点 `tmp/map-native-split-runtime.json`。最终消费者及原始XML／输入链独立复核通过，无剩余Required；实现和测试已按逻辑本地提交：365791af／c28b7431／25f6f395／97f4b1cc／aa677bb6／089da1ce／2566425b／cd9b47b8；本文及README／AGENTS／恢复点文档提交随后见HEAD。

## 官方接口依据与平台边界

可移动内容保存既有remember状态与节点，适合在两个实际组合位置之间移动同一内容；本片仍需实际Android验证picker及View宿主迁移。[Compose Runtime API](https://developer.android.com/reference/kotlin/androidx/compose/runtime/package-summary#movableContentOf(kotlin.Function0))

Compose输入的Initial pass及事件消费用于协调父子处理，消费本身不能替代对AndroidView真实交互的验证；实际混合分屏pointer／Surface行为仍保仪器与设备门。[Android手势与事件分发](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures)

未运行真实Android事件、DocumentsUI／SAF、Activity真重建／杀进程、PDFium像素、MovinkPad Pro14／Android15／ProPen3、Surface裁切／GPU或性能。当前主机／源码证据不替代这些验收；没有APK／安装／推送／合并／发布，整体目标active／complete=false。
