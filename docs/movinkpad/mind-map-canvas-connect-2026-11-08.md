# 画布点击连线与目标预览（2026-11-08）

用户要求：继续思维导图实现；点击连线再点目标，显示临时线与目标反馈，取消无残余，复用笔输入及普通线编辑/撤销。
状态：已实现；298套件3267项完整JVM回归、最后32项定向回归及Android测试源码编译通过，独立代码复核通过；真实AndroidHost/MovinkPad未运行。
本轮边界：原生Kotlin源码、测试、文档、本地提交；不打包、安装、推送、合并或发布。

## 实施片

普通线列表事务已提交78b0c471，真实来源恢复组件ea9d9854、会话适配4802a45c已保留。此片接“点击连线→已有目标节点”到同一草稿确认流程，不直接创建模型线；边缘拖拽入口、点击空白新建并连接、画布线命中/拖端点后续继续，不取消来源producer/copy/页激活和网络。

- MindMapView增加显式beginConnection(fromId)/cancelConnection、只读mode/target和onConnectionTarget；只能在当前图可见来源节点存在、无笔画/文本编辑及任何已按下pointer时进入（未过slop的按压也拒绝）。来源ID和所属图ID锁定，图替换或源隐藏/删除取消。进入模式不占历史，保留节点位置和相机。
- 单独处理连线模式MotionEvent，在原位ink/节点drag/双击/长按之前截获；默认模式行为不变。手指/笔选择目标不会在目标手绘框写字，也不会拖节点；按下/移动预览、实际未取消UP才回调一对节点。自环/空白/取消UP不发草稿，不改模型；可留在模式重试。
- 所有权按pointerId：pen/eraser接管已落下手指；pen拥有期间其它指针是掌触，不启动相机或结束交互；pen先抬起、掌仍在只发一次回调并吞余触摸。笔接触使用现input busy通知，落笔时相机冻结，抬起/取消/失焦均归还busy；不改raw事件/deviceId/按钮位，不在实际ink笔画中切入本模式。
- 两根手指到来时取消这次连线模式并交回现ScaleGestureDetector/视图手势，不生成线；第一根手指down已喂给scale detector，交回不伪造原始事件。begin先给两个detector私有CANCEL；armed期间保存原quickScaleEnabled并临时关闭，onScale也拒绝armed回调（Android ScaleGestureDetector内置独立quick-scale检测器，仅绕开外层GestureDetector不够）。取消模式交回两指时恢复原设置而不清已喂DOWN的正常scale状态；其它退出恢复原设置并清检测器。此片不重写现相机算法。连线笔接触不同时启动画布手势。
- 简单Canvas临时虚线与现普通线同样黑白，绘于节点下；命中目标用现dropTarget对比边框反馈。预览只在View瞬时状态，不入MapDocument/导出/内容历史，不修改手绘渲染或Surface/GPU。视口按钮与fit/center等在连线pen接触时也不移动相机。
- 独立导图工具栏提供“在画布连线”及模式提示/取消，沿用TextSize/Space/MinTouchTarget与三份字符串；这是MindMapActivity自己的工具栏，不向普通笔记共用ToolbarLayout塞未知按钮。返回/失焦/卸载/进入文本编辑取消模式；resize/disable/源失效/取消都归还busy并吞余stream直到新DOWN，旧UP不落入普通ink/drag。
- 有效目标抬起后先结束busy和模式，再打开已实现GraphRelationsDialog的“新线草稿”，允许编辑类型/方向/说明/端点；确认才经session.latest提交，一步undo。取消图不变，duplicate仍定位latest已有线并保留说明。键盘选择目标后Enter也可发起同一草稿，Escape取消，不能误双击进入正文编辑。pen拥有期间除Escape取消外不执行Enter/DPAD或相机动作，键盘Enter只在无接触时发一次草稿，源ID保持锁定。

## 验收

共享Android事件合同同时供Robolectric和仪器runner：finger/pen在手绘目标内仅连线不写/不动，MOVE临时反馈与UP发一次草稿；cancel/flaggedUP/自环/空白不发；pen/palm顺序、pointerId、pen先抬起与余触摸；连续双击空白/自环后MOVE不能quick-scale或正文编辑，之后真正两指pinch的比例/焦点正常、零文档变化；实际ink中拒绝进入；pen连接时fit/zoom冻结；图替换/源删除/隐藏/失焦/卸载取消和busy归还。默认原位ink/drag既有事件回归必须仍通过。

AndroidHost追加工具栏→实际View目标→弹窗取消/确认/重复和一步undo路径，只编译时明确未运行。简单绘图预览可用host Canvas验证不入导出/模型；真实触摸/笔/视觉与MovinkPad验收另列。overall complete=false。

## 实现与复核

MindMapView在同一原生View内增加临时连接模式/所有者；默认ink分支保持原pen-over-palm优先级。已按下pointer（包括finger落在inkBody未过slop）拒绝切入。连线笔接触冻结fit/zoom/center/DPAD/Enter，Escape允许取消；抬起先归还busy，再把节点对交给GraphRelationsDialog新草稿，仍需确认才写一次history。自环/空白/取消不建数据。

独立复核的五项发现均已修并留反例：

1. 结束/取消的余流用独立connectionDrain在普通inkBody接管前拦截；同stream新增pen不能回落写字，fresh DOWN才恢复。没有改旧ink的笔优先规则。
2. 提示/取消改为图Box内覆盖层，不把图外caption换成48dp按钮行，避免进入模式后resize立刻取消。
3. connectionEnterHeld消耗同一Enter的repeat直到UP/全新DOWN，不靠弹窗及时夺焦防正文编辑。
4. 工具栏节点标题单行ellipsis，键盘选择多行节点不增高工具栏、改变画布高度。
5. View允许touch-mode焦点；Host先Instrumentation.setInTouchMode(true)，再点工具并断言hasFocus后走DPAD/Enter。UiAutomation同名隐藏方法不在公开SDK，未使用。

Quick-scale在armed期间保存/关闭并抑制onScale，退出恢复原设置；两个私有CANCEL不改用户原事件。两指交接保留首finger已喂的main scale状态。测试用全新独立Android ScaleGestureDetector处理同一多指流作比例/焦点对照，不硬定900/600：Android在跨span-slop的MOVE才开始，当前流以700为基准，900/700是原有合法行为。相机算法未为测试改写。

临时虚线/目标边框只存View状态，节点下黑白绘制；默认MapPainter/导出不接此参数。Native Canvas回归证明亮/暗主题出现预览、取消后像素恢复、图编码字节不变；这不是目标GPU验收。

## 验证证据与边界

- `tmp/map-canvas-connect-full.log`：JDK21/SDK36/Gradle8.13，独立build/project cache，离线 `:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`；298套件3267项全过，0失败/错误/跳过。
- 最后caption/触摸焦点改动后 `tmp/map-canvas-connect-focus-verified.log`：MapInputHostTest29项+MapRenderTest3项全过，Android源码编译成功；`git diff --check`通过。三份graph.xml为55键、无重复键，两中文逐字相同。
- 新增9个共享Android事件方法和1个Native Canvas方法；既有20个输入与2个绘制回归仍通过。两个新增真实Activity Host场景覆盖HUD重组后size/instance/viewport保持、工具→目标→取消/确认/一步undo，以及touch-mode焦点→DPAD多行目标→源锁定→Enter；仅编译未运行，仪器runner沿用现用例类。
- 未打包/安装/推送/合并/发布。AndroidHost、真实窗口、SAF、ProPen3掌触/按键、MovinkPad Android15视觉/GPU/性能未实测，不能用上述主机结果替代。

下一阶段继续来源producer/new/copy持久切换/页面激活与多来源闭环，及A2边缘端口/拖线、空白新节点连接、画布线命中/端点拖动、多选、无中心。用户已确认保存后放弃保留已写入版本，仅丢之后修改，见reference-discard-written-2026-11-08.md；不再等待该产品决定，也不自动回滚原件。
