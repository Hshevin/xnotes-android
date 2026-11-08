# 普通关系线的画布选择（2026-11-09）

用户要求：持续实现思维导图与关系画布；普通连线的命中范围宽于视觉线宽、重叠优先节点，选择后可编辑类型／方向／说明／端点或删除，不改层级、不误删节点。
状态：已实现／已验证；完整主机回归 315 套件 3520 项通过、Android 测试源码编译成功，独立源码复核通过；实际 Compose／目标设备验收待执行，整体未完成。
本轮边界：原生 Kotlin 源码、主机／共享事件测试、Android 测试源码编译、文档与本地提交；不打包、安装、推送或发布。

## 本片合同

1. 沿 MapPainter／MindMapView 提取普通关系线共享几何，绘制与命中使用同一端点及说明文字边界。仅处理两个端点均可见的普通关系；父子层级线保持原操作。触控目标宽至少 48 dp，按屏幕密度及当前缩放换算，不随缩小而变得难点；节点、正文笔输入及来源条优先。
2. 点击松开且没有越过系统移动阈值后才选线；线附近拖动仍操纵视口，不改变节点／线。CANCEL、被取消的 UP、第二指操纵、失焦、禁用、文档更换或线失效均不提交旧命中。既有笔接管／掌触／连线模式的顺序保留。
3. 节点与关系选择互斥，仅为运行时状态，不写 JSON 或内容历史。选择显示黑白反馈，缩略图与 PDF 不显示选中态。删除、换文档或折叠导致端点不可见时清除关系选择。
4. Enter 或编辑入口打开该现有关系的 GraphRelationsDialog 草稿，initial 绑定实际旧线，复用 changeRelation；不能把旧线当 newRelation 重新创建。取消不修改，确认一次撤销，删除仅删线。重叠候选按最近距离／逆绘制顺序稳定排序，并提供现有关系文字列表选择，避免同路径关系不可达。
5. 不新增编辑引擎，不改导图格式，不把无中心画布、多选、来源跳转或 native4 生产迁移混入此片；这些后续需求全部保留。

## 验收

几何覆盖横竖斜线、线宽外触控区／范围外、端点外延、退化线、说明边界、重叠与折叠端点、缩放／密度；共享实际事件覆盖零 commit 的选线／取消、多指、拖动平移、节点优先、手绘笔输入、连接模式、迟到 UP 和换文档。原生 Compose 场景核实现有线编辑／取消／删除与一次 undo／redo。Android 场景仅编译时明确未运行，硬件与视觉验收单列。

审核细则：48 dp 指全宽，半径为 24 × density / contentScale；线段距离须 clamp。说明沿当前 LEFT 对齐、midX／midY−8 基线、前 80 字和同一字体度量，不能另算居中标签。UP 没有 MOVE 也核 slop；resize、disable、文档／可见端点变化均废弃待选候选。Session.publish 保留有效关系选择，不因 node=null 自动改选根节点。草稿及工具栏始终保留完整关系列表，确保完全重合的多条关系仍能分别编辑。模型／View 事件通过不代表实际 Compose 弹窗点击通过。

## 实际接线

MapRelationGeometry 为普通线、箭翼及说明提供绘制／命中共同几何，MapPainter 的 fit／预览／PDF 边界包含说明。MindMapView 仅在有效 UP 后选线；线附近移动仍平移，节点、来源条、手绘正文及连接入口优先。MapSession 持有互斥的节点／关系选择，不进 JSON／历史，保留有效线选择跨编辑及 undo／redo，隐藏／删除／新会话时清除。

选线后「编辑连线」或 Enter 以最新实际 existing edge 初始化原有草稿，复用 changeRelation。普通列表及 duplicate 定位同步选择，可分别编辑完全重合的不同关系；删除仅删线。默认缩略图及导出没有选中态，现有可编辑格式不变。

独立复核还修正了捕获事务寿命：掌触不取消笔选线，笔可接管先到的手掌；POINTER_UP 归还 detector，剩余掌触不妨碍下一次落笔。失焦／禁用／卸载、内容变化及相同内容的新会话会退休旧长按／双击／拖动预览，防止旧 UP 覆盖异步新增正文或来源。正常同会话节点双击保留。

## 验证证据

- `tmp/map-relation-hit-red.log`：新 API 缺失编译红；`tmp/map-relation-hit-palm-red.log`：两种笔掌序列真实红（应选线，实际 null）。
- `tmp/map-relation-hit-lifecycle-red.log`：5 例中 4 项真实失败，复现旧长按编辑新节点和旧拖动覆盖较新的正文；禁用对照已通过。
- `tmp/map-relation-hit-epoch-red.log`：4 例真实失败，覆盖相同内容重载／禁用的旧拖动、跨会话双击和抬笔后残留长按；最小修复后均通过。
- 首轮定向 `tmp/map-relation-hit-focused.log` 为 70 项通过及 Android 源码编译；中间完整 `tmp/map-relation-hit-reviewed-full.log` 为 3516 项通过。
- 最终 `tmp/map-relation-hit-notified-full.log`：JDK 21、Gradle 8.13、Robolectric Android 35，隔离输出及项目缓存，`:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin` 成功；315 套件 3520 项，0 失败／错误／跳过。本轮比基线 3483 新增 37 项（来源重开 8；本片几何 7、共享事件 13、主机计时 5、Session 2、CPU 渲染 2）。两中文 graph.xml 相同，diff 检查通过。

一次最终全量的旧来源等待测试在无 Composition 的夹具中漏发 snapshot apply 通知，证据为 `tmp/map-relation-hit-final-full.log` 和保留的 `tmp/map-relation-hit-final-connected-failure.xml`。只将测试的抬笔状态提交放入 withMutableSnapshot，不增超时、不修改生产等待逻辑；该类 15 项在 `tmp/map-source-snapshot-notification-focused.log` 通过，随后最终全量全部通过。通知机制经本机 runtime 1.11.2 字节码及 [Android Snapshot 官方说明](https://developer.android.com/reference/kotlin/androidx/compose/runtime/snapshots/Snapshot) 核实。

MapRenderTest 使用原生 CPU 绘制验证双主题单色选中差异、说明实际边界、远离线的说明命中，以及真实 thumbnail／PDF 内图没有选中态。新增 13 个共享 Android 事件场景及 2 个实际 Compose／无障碍弹窗场景仅编译；实际 Android 窗口、SAF、MovinkPad Android 15／Pro Pen 3、GPU 与性能均未验收，没有 APK 或设备安装。

## 继续范围

无中心关系画布、多选／对齐、分支同级插入与边缘自动平移仍待实施。原生保存回执／工作会话统一、复制／另存持久交接、页面引用激活、多来源预览／失效恢复、跳转返回、反向引用和文件关系网络全部保留，不能把普通线选择视为整体完工。
