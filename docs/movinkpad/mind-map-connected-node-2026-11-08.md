# 空白落点新建节点并连接（2026-11-08）

用户要求：继续原生 Kotlin 思维导图与文件关系网络；落在空白处允许新建文本、手绘或文件引用节点并连接，取消不留残余，整次新建连接一步撤销。
状态：已实现，计划及首轮源码独立复核完成；定向测试/Android源码编译通过，完整306套件3400项通过（0失败/错误/跳过）；仪器及目标设备未运行。
本轮边界：代码、测试、文档、本地提交；不打包 APK、安装、推送、合并或发布。

View 对配置了空白回调的宿主在正常抬起、确实未命中任何节点时发起新建草稿；端口起始流仍须越过系统移动阈值，端口轻点只进入点击连线模式。没有回调的调用者保留已有空白重试语义。先退出连线状态并释放笔接触，再交给宿主；取消抬笔、残留手指、源节点本身、掌触和生命周期取消均不创建。

MapSession 持有新建事务 token、所属图和会话代次、来源节点、文档坐标中心锚点及稳定新节点 ID。类型选择、文本输入、选择引用文件期间不改文档。文本/手绘复用现有节点属性草稿（手绘创建空框后可直接原位书写）；引用使用现有只读身份核实，成功后显示来源与独立标题、明确确认才创建。新节点以落点为中心自由放置，与起始节点建立无方向关联；之后可通过已有关系编辑修改含义。

确认按最新文档合并，不用开始草稿时的整图覆盖；核实会话/token、来源节点仍存在、ID、节点/线数量及所有字段。新节点与新连线合成一次 commit，成功后选中新卡片；失败保留草稿，取消零节点/零线/零内容历史。Undo/redo、保存重开应保留内容、来源与位置。关闭、改用文件版本、清除 ViewModel 会取消草稿和导入；迟到回调必须不能复活。

引用选择器使用独立请求 ID，并在宿主中保留在途请求绑定以跨配置重建。旧请求尚未返回时不能把结果误绑到后来的新请求。文件核实可取消，返回后再次检查实际草稿 token/代次，合并最新标题；选择/核实失败或取消不改原文件，不向图中留下临时节点。登记/读取能力沿 MapSourceAccess，不引入页面身份激活旁路。

验收包括三种节点、原位坐标、无方向线、一次撤销/重做及保存重开、并发来源/正文保留、容量/源删除/过期 token/取消/迟到导入/改用文件版本/关闭、共享 Android DOWN/MOVE/UP/CANCEL 与 pointer/掌触/空白判定。真实 Host 与设备验证若只编译需明确报告。页面级来源、生产 native/cache 统一、反向引用与网络继续保留。


落点明确为卡片中心。类型切换、属性尺寸和文本测高后按最终尺寸重算左上角，不重排其他节点。确认界面显示实际起始节点标题/编号和无方向关系含义。MapNodeDialog复用增加逐字段draft回传，文本/类型/尺寸保存在Session，配置重建不依赖旧Dialog remember；disposed的字段/按钮回调被阻止。

来源仍限已有身份的.xnote/.xcanvas/.xmap，不为裸PDF或缺身份文件调用ensure、迁移或重写。MapSourceAccess沿用登记与持久读取权限，取消保证不新增图节点/线/正文历史，不声称没有登记等本地副作用。pending picker ID由rememberSaveable保存，不因取消草稿而换绑；Session分别核attempt ID/token/独立epoch。IO结果合并最新标题，读取失败可重试；选择器启动失败也可退出/重试。

独立复核发现替换文档期间仍可创建绑定旧模型的新草稿，现用独立replacingDocument屏障阻止新草稿、内容提交和图输入，并在采用结果时再次废代次；普通autosave仍允许编辑。新增latch测试在真实MapStore.reload读取暂停时验证该窗口。图外UP不视为空白，负坐标及宽高边界测试确认不创建。

纯事务缺API反例已在tmp/map-connected-node-model-red.log重现。tmp/map-connected-node-model-session.log、ui-first/input/review-reload/boundaries各定向日志已成功；完整tmp/map-connected-node-reviewed-full.log为306套件3400项全过，Android测试源码编译成功。最后追加旧composition sync断言后，tmp/map-connected-node-final-focus.log15项定向Session及Android源码编译通过。测试覆盖三种主体、位置与原笔样本、节点/线/来源容量、重复ID/失效来源、一次undo/redo和保存重开、latest笔迹/来源合并、取消及旧token/attempt、读取/启动失败重试、检查期间标题、ViewModel清除、close/reload即刻废代次、actual-hit/port微移/取消/pen掌/图外抬笔。新增实际Host四场景只编译：文本确认一步undo、手绘/引用取消、真实文件inspect后确认且独立标题、Activity重建保留草稿取消仍零内容。外部系统picker界面与真实SAF provider尚未运行。


普通已有节点来源也改成launch前签requestId/docId/替换epoch/nodeId，UI跨配置保留单个pending ID；读取返回及等待笔接触释放后再次核代次。关闭/替换取消所有来源IO，旧picker或旧读取即使在相同UUID/rootID的新版本采用后才返回也不能追加来源。新增真正等待worker.isCompleted的latch例验证文件字节、正文和历史均保留。

来源列表改为按带epoch的SourceEditSnapshot编辑sources/primary字段，保留最新正文和笔迹；若列表已添新来源、主预览变化或文档已替换，旧列表编辑被拒并提示重试。disposed列表回调失效。正常来源读完仍等待落笔结束，再与当前笔迹合并；新增测试覆盖此成功路径及单步来源撤销/保存重开。


来源备注的连续回调用SourceListEditor同步续接。每行按稳定来源ID修改当前expected的单个字段；同一Main批次a→ab及不同来源交错不会因重组尚未发生而丢字/抹另一行。成功后立刻读取实际source snapshot推进expected，真正外部追加仍拒旧列表；新composition确认显示了实际来源列表后才sync，旧composition不能倒退自身刚成功的状态。关闭controller后旧lambda失效。tmp/map-source-list-callback-red.log是新适配器缺API的编译反例，fixed日志为真实Session连续回调/来源保护成功，不宣称已在真实IME上执行。

独立最终复核确认SourceListEditor连续回调/多行/外部变化边界闭合，未发现新增必修。新增21个主机测试方法，四个共享Android事件及四个真实Host方法在仪器源集中仅编译；不得把它们标作设备运行通过。
