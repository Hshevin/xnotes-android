# 导图文件来源打开与返回（2026-11-10）

用户要求：按完整14组思维导图需求持续实现；接通整文件导图来源的实际打开、编辑和返回，并基于当前工作树新建工作树继续。
状态：已实现，31套243项有效Host用例及Android测试源码编译通过；独立迁移／新增用例／原始XML及输入链复核通过。实际平台及目标设备未运行。
本轮边界：原生Kotlin、本地离线；仅实现、相关编译测试、文档、本地提交，不打包APK、安装、推送、合并或发布。真正双窗格等原需求继续保留。

## 可操作结果

来源行「打开」和单来源引用节点双击现在可进入实际 `.xmap`，继续在主窗口编辑、撤销、保存，再从「返回来源导图」逐级返回。文件卡的整文件导图身份沿同一路径。自引用核实身份后成功结束；循环复用实际会话，每次离开仍保存独立的返回位置。

每个规范URI只保一个实际Entry／租约，返回不关闭访问过的会话或强制保存。各图未保存内容、撤销重做、观看位置和错误状态保留，原有自动／显式保存继续。最多暂留16个不同文件、64个返回帧，达到上限明确提示，不淘汰脏内容。

关闭子图先返回父图；根图关闭按本次实际保存结果处理失败、冲突或重试。若其他拥有者仍使用同一会话，只释放当前路由租约，不确认或取消对方输入、关闭整个会话或取消其自动保存。最后拥有者存在未确认输入时明确拒绝关闭；用户结束自己的草稿后可重试。明确选择保留恢复资料时，也只释放当前路由，不清恢复日志。

## 真实来源与会话

原先来源分派会把导图交给只识别NOTE／CANVAS的原生准备入口，普通MapWorkspace.open也会释放旧Entry。本次新增窄 `MapDocumentSourceAccess`，复用现登记表、MapSaves规范URI锁、MapFiles只读观察与MapDocuments实际会话，不另建编辑引擎或正文缓存。

- 仅FILE及真实登记类型为Map进入此路径；已核实NOTE／CANVAS继续原生路径。PAGE、CANVAS_REGION、PDF_ORIGINAL不交Map读取；Map或独立原文失败不降级。
- 完整JSON经MapCodec核物理UUID与SHA，不能让恢复赢家或活会话掩盖物理文件错ID／损坏。位置别名按canonical key归并，同UUID的两个真实位置拒绝；类型／永久页契约、Original保留身份、权限、回收站和恢复状态分别核实，坏日志及未知alias资料保留。
- 仅MapDocuments.acquire取得实际会话。已活目标保原模型、历史、dirty和冲突，不重开或用磁盘模型覆盖。会话凭证核实际对象、interactionEpoch和目录寿命，正常正文／派生状态变化不会误退身份。
- 取得会话并等待首次加载在最终锁之前，避免与open死锁。规范URI锁覆盖最后raw SHA／类型／UUID／恢复核验及无挂起的Main采用；Main同时重核原来源票、Workspace／host、actual actor和原生停车观察。
- `DocumentRegistryIdentityEpoch` 在结构SQL前进入变更态，跨实际外层事务提交或回滚后才结束；旧／新UUID及嵌套事务共用代次。来源准备与Main采用都核此票，阻止最后IO后新重复位置或身份变化被漏过；名称或无结构变化不无故退票，不改数据库schema。
- 未采用的持有者在跨dispatcher返回前登记租约，取消可完整归还。最后临时租约保留dirty恢复，其他租约存在时正常release，不取消对方autosave；已接受IO不取消。

## 返回、输入与关闭边界

返回帧固定不可变相机及来源书签。A→B→A后再次移动A，不会覆盖第一跳的旧相机。来源成员仍有效才恢复选择／条目；成员失效可回原会话，但不复活旧焦点。原图epoch／UUID变化不再采用旧相机。

来源push／return核实际未确认文字、连击、连接草稿、笔接触及活动来源picker／import，不强制确认或取消。退休OS槽继续只为原结果保占位。配置重建由ViewModel保整池及presentation，composition只解绑自身host；解绑清全部visited Entry的旧Editor闭包，晚IO／关闭结果不能pop新帧。

Map→Map始终保持图前台，不临时挂载native SplitHost。新增 `Editor.awaitMapSourceParking()` 复用已有owner／输入／writer等待与观察，不提交text／flow／table／selection；旧默认停车语义保留。每次push／pop包括回旧A和cycle，都设置本次跳过native persist策略，普通打开恢复原策略。这里的零额外保存指停放的Native文档；Map自身仍按正常自动／显式保存工作。

Main关闭先走Entry／lease级hook，不调用全局Session.save(onClosed)来退役共享草稿或设置closing。最后拥有者使用saveAttempt的真实结果，并核局部attempt、epoch、host和工作区代次；失败保原route。Network返回时关闭图也使用当前租约／代次及纯停车守卫。Native页面或独立原文从子图B进入后仍回B，Map父导航A保留。

## 迁移、验证与复核

基线为d0132a90eadb25ed77ba7f3f0f445691a880b13a。按用户要求创建 `/Users/kita/.codex/worktrees/mind-map-map-navigation/xnotes`、分支 `codex/mind-map-map-navigation`，复制source-target树34个未提交文件和证据。SHA-256／模式／Git状态逐项一致，独立复核通过；原树及原分支保留原样。迁移证据 `tmp/worktree-migration-2026-11-10.json`。

新树构建目录与项目缓存分别为 `/private/tmp/xnotes-mind-map-map-navigation-build`、`/private/tmp/xnotes-mind-map-map-navigation-cache`，与原树隔离；JDK21、Gradle8.13、AGP8.12.3、SDK36。仅运行compileDebugKotlin、testDebugUnitTest及compileDebugAndroidTestKotlin，无assemble或安装。

| 运行 | 实际结果与含义 |
| --- | --- |
| 20426 | 旧API真实Host 1项失败：正常结束为UNSUPPORTED，仍停A，预期应打开B；保留原生模型／历史／字节断言已通过。 |
| 32823 | 生产enum名称编译错误，未执行测试；summary明确0项，旧XML隔离，不算本轮执行。 |
| 37275 | 新套51项，37过、14失败；夹具生命周期、Provider注册、清理及实际输入焦点逐项修正。 |
| 27748 | 30套240项，2个旧用例失败；57个新增全过，Android测试源码实际编译通过。 |
| 33832 | 仅2个旧失败method定向复验，全过。普通替换先拒活动草稿、明确取消后重试；网络选择等待实际完成筛选的当前报告。 |
| 90621 | 新树3项全过：整池／实际actor退休后的新会话冷开、A→B→Native→B→A、A→B→OriginalReader→B→A。1218输入前后SHA相同；Android源码任务从Gradle缓存复用。 |

最终按case键合并27748的240项，只替换33832的2个原失败method，再加入90621的3项：**31套243唯一项，0失败／错误／跳过**。不是把partial XML当整套结果覆盖。27748之后只改2个对应测试文件，再新增leaf测试；生产／Android／资源／构建输入均未变。最终1218输入集合及SHA逐项核对，三套资源4键一致，两中文逐值一致。

新增用例覆盖实际provider、identity／恢复／取消、visited池／cycle／每跳相机、共享关闭／恢复关闭、纯native停车及Network返回。冷开先关闭整个池、等A／B租约与actor均消失，再acquire B并核新session、正文／永久页来源、clean且无undo／redo。Native／Original逐跳用例核同Entry、历史／redo、相机、条目、父route及停放Native原字节／零额外写。

Native用例复用已打开NOTE owner，替换保护回调受控，实际prepareReference／openReference执行。Original用例用真实catalog、资源租约、workspace、navigator和reader，但PDF解析／尺寸／白色像素由受控engine提供，输入为占位字节；不证明真实PDFium解析、定位像素或设备绘制。Android新增3项主宿主与1项登记事务回归仅源码编译，未执行。

独立计划与实现复核要求的共享关闭、纯停车、登记SQL代次、busy关闭和Network共享关闭均已处理；最后迁移／新增三用例／原始XML及输入链独立复核通过，无剩余Required。原始日志、XML、输入清单和失败原件保留在 `tmp/map-source-*`；合并清单 `tmp/map-source-verified-summary.json`，最终输入 `tmp/map-source-final-inputs-verified.json`，运行恢复点 `tmp/map-source-navigation-runtime.json`。实现与测试已按逻辑本地提交：b67ba73d／ab0d5ba5／3e3d7d10／48053e78／a100e494／c68668b1；本文与README／AGENTS／恢复点的文档提交随后见HEAD。

## 未完成范围

真实Android、DocumentsUI／SAF、Activity实际重建／杀进程、PDFium像素、MovinkPad Pro14／Android15／ProPen3、Surface／GPU／性能均未运行。当前证据不能替代这些验收。

原14组持续需求不缩减，整体active／complete=false：真正导图双窗格、Canvas侧反向列表、权限／移动／回收站／重复身份的精确恢复、完整引用备份、主要来源大图／选页缩略图、同级插入／边缘平移等继续实施。图预览不是来源完整备份，来源跳转也不构成原生保存会话迁移完成。
