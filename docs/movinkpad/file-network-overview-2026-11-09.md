# 全库与多文件关系网络（2026-11-09）

用户要求：继续完成思维导图与文件关系网络，按完整需求推进真实用户流程，复用已有能力；不反复审计未变代码。
状态：已实现；89 个相关主机用例通过，Android 测试源码编译通过；真实 Android／MovinkPad 验收未运行。
本轮边界：实现、编译、相关测试与本地提交；不打包 APK、安装、推送、合并或发布。

本阶段接通：首页／主菜单进入文件关系网络 → 全库 → 按 UUID 选择多个文件 → 原有来源格式／目录筛选 → 查看完整概念与具体来源 → 打开图和来源 → 返回同一个网络。

- 继续共用当前 Reader、Controller、Scene、MapDocuments、来源导航和原生宿主停车流程，不建持久索引或第二套渲染器。
- 局部、多个文件中心、全库三种范围分开。无打开文档也能进入全库；内部视图编号不作为文件身份，不伪造原生来源上下文。
- 多文件范围在同一次图读取中先匹配中心／展开／图所属关系再扣预算，避免不相关关系耗尽额度。全库有明确读取和显示上限；未完整扫描时显示不完整状态。
- 已登记可用的孤立文件只占关系之后的剩余显示预算。名称与格式为上次登记信息；不读取原生或 PDF 正文，不更改 UUID，也不把未知／冲突身份洗成有效文件。
- 范围切换保留已应用筛选和相机位置，退役旧操作与弹窗。取消文件选择不改变范围。全库不提供容易误解的逐层展开；多文件与局部继续沿真实来源／所属边展开。

验收涵盖无文档首页、多个真实中心及所属图、孤立文件、无虚构中心、预算不吞选中关系、范围及操作代次、取消与笔接触、具体来源往返。只运行变化相关的主机测试和 Android 测试源码编译；不把这些结果算作平台与硬件验收。

未完成的手工关联、将文件加入可保存关系画布、区域／画布引用、导图双窗格、恢复与备份以及原需求其余交互继续保留。

## 实现与验证

首页侧栏和收起后的图标栏均有入口。`LibraryNetworkLaunchRequest` 不携带原生来源，沿现 `LocalNetworkCoordinator` 取得真实宿主停车证明后进入；无文档可用，笔接触时可取消且不取消原笔画。Query／Controller／相机保留在原 ViewModel，初次宿主闭包不进入 ViewModel。范围切换退旧成员、操作与弹窗代次，来源打开继续使用当前 MapDocuments actor 和原导航／返回保护。

`scanLibraryNetwork` 一次读图投影所有有来源概念；`scanSelectedNetwork` 先匹配多个中心、图所属与已展开文件，再计直接／深层独立额度。沿现 250 节点、750 边、5000 来源成员与读取预算，不拆概念，也不以孤立登记抢占关系额度。Registry 新入口仅 SELECT 已登记可用的原生身份，10001 项哨兵显式报告上限，位置观察仍受原有预算；不启用编号、不读取原生／PDF 正文、不建额外索引。

新增 15 个主机方法：Data 7、Domain 3、真实 Scene/View 3、真实原生宿主 2。涵盖多中心及所属关系、孤立文件余量、临时视图编号拒作来源、未知格式、范围／筛选与旧操作失效、同位置异 UUID、永久页契约残留、登记超限／取消、空网络点击及原相机、无文档进入和笔中取消。

验证使用 JDK 21、当前隔离输出／项目缓存、离线 Gradle；命令范围为 `:app:testDebugUnitTest` 的 `com.xnotes.mindmap.*Network*`、`MapBacklinkLibraryTest`、`pen.LocalNetworkWorkspaceHostTest`、`pen.MapNetworkNavigatorHostTest`，以及 `:app:compileDebugAndroidTestKotlin`。没有运行 assemble、安装或设备测试。

- 首轮 23405 只在新增 Android 测试的常量位置拼写失败，生产编译已完成，单元测试尚未执行；仅修正为 `AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id`。
- 阶段独审发现同位置实际身份与旧孤立登记合并缺口：旧 A 的位置实际读出 B 时，A 也须标身份冲突并移出孤立选项。已沿现 canonical 观察表修复并新增真实回归，登记／原件／恢复日志均不变；复核已闭合。
- 77467 实际运行 89 项，88 通过；Android 测试源码编译通过。唯一失败是旧等待条件在合法的临时空 live 报告上调用 `single()`。仅将等待条件改为 `singleOrNull()`，仍等待唯一概念且全部 LIVE，后续精确成员、取消信号、原字节断言不变。75007 仅重跑该项通过，生产与 Android 源码未再变，不重复未变的 88 项。
- 合并证据为 89 项、失败／错误／跳过均 0。日志与逐项结果在已忽略的 `tmp/network-overview-recheck.log`、`-recheck-summary.json`、`-wait-recheck.log`、`-verified-summary.json`。77467 的 1066 个输入 SHA 在运行后全部相同；此后仅上述等待夹具调整，最终输入清单为 `tmp/network-overview-final-inputs.json`。

已有 Android Main 场景扩展了真实空首页入口、关闭回原首页、全库选择取消、多文件 T/U、具体 U 条目打开及同范围／相机／选择返回。**仅源码编译，未实际执行**。真实 Android／SAF／DocumentsUI、配置重建与杀进程、MovinkPad Pro 14／Android 15／Pro Pen 3、掌触／Surface／GPU／性能仍未验收。本阶段不改渲染器和笔迹编码。

代码已本地提交：`a257f42c`（全库／多中心投影和身份边界）、`80d049c3`（首页入口与完整范围操作）。独立阶段复核已通过，无未闭合 Required。
