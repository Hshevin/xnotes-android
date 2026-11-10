# 导图与笔记混合分屏的持久化

状态：in_progress；完整思维导图目标 active，overall complete=false。

## 工作树与范围

2026-11-10 用户明确要求基于当前工作树新建后继续。从干净提交
`d706ebd1104e34005bcb45f444243429f8551557` 创建
`/Users/kita/.codex/worktrees/mind-map-mixed-persistence/xnotes`，分支
`codex/mind-map-mixed-persistence`。原 pair-open 工作树保留。
仅实现、相关编译与测试、文档及本地提交；不打包、安装、推送、合并或发布。

## S1：typed 安排文件与存储边界（verified，独立复核 PASS，已本地提交）

输入：现有 v1—2 NativeSplitOpenParser、私有 splits 目录与 saved-split.json。
输出：可独立验证的纯数据契约及受限存储组件；本片不改变现有用户入口，
不宣称已经支持混合安排保存、打开或进程恢复。后续 S2 才接实际 owner 保存、
两个候选同步采用与 Main 入口，S3 接冷启动工作副本恢复。

- 统一请求按版本显式分派：v1—2 委托原 Native parser；v3 必须有
  `format=xnotes-split`，明确恰好一个 `map` 和一个 `note`，每份来源包含
  实际文档 UUID、URI 和可选标题。未知版本、类型、重复角色、损坏字段不得降级。
- v3 仅存纯描述：逻辑 mapPane/focusedPane、物理第一份比例、方向和反转，
  Native 阅读位置与 overrides、可选 Map viewport/尺寸/density。
  ID、URI、视口或描述本身均不授予读写来源、清恢复资料或采用模型的权限。
  不存 Editor/View/owner/运行时票，也不将保存的 SHA 冒充正文基线。
- 严格 UTF-8、有界读取（与旧 parser 同为 1 MiB）、有限数与合法整数、
  canonical UUID、布尔不接受字符串；v3 view overrides 拒非法类型/范围。
  必需几何与位置字段须完整，不静默把坏字段恢复为默认；不可保存 nativeHome。
  JSON 容器不向请求暴露，构造后的数据不随原 JSON 改动。
- 私有显式文件仅接受 `files/splits/*.xsplit` 的 canonical 直接子文件；
  不读取任意 content/file URI、不跟随逃出目录的符号链接；recent 只读固定
  saved-split.json，只有该兼容入口接受旧无 format v1。
- 新 mixed 文件不复用按路径“已保存”早退，不覆盖任意既有文件。暂存使用
  同目录独占唯一名称，写完并 fd.sync 后，以 Files.createLink 原子 create-new
  发布完整文件，不先暴露空目标，也不降级普通复制/覆盖。link 成功是提交点，
  此后清暂存失败不能删除 final 或报告未发布，只保留本次清理问题。
  并发 store 实例不互相清理暂存。失败仅清本次持有的文件；损坏/未来版本
  的既有文件原样保留。名称只作为显示提示，最终目标保持目录边界。
- S1 的 recent 只读，不更新它。S2 在与现有 Native writer 统一协调后再接
  checked recent 更新，并分别报告显式文件成功与 recent 失败，后者不得把已
  保存显式文件报成丢失。此阶段不写 cold last-presentation，也不把 recent 当最后进程布局。
  发布/读取/删除均只处理安排 JSON，不访问 Map/NOTE/PDF/Session 正文。

验收：先对新契约运行 RED，再实现；检查 v1/v2 回归、v3 两种逻辑槽及所有
focus/反转组合 roundtrip、完整 overrides 与 camera、损坏/未来/超限/UTF-8、
冻结数据、目录穿越/符号链接/名称、并发保存、失败保留原件与 recent 只读。
typed 构造/编码亦核预算和几何；坏 v3 即使带齐旧字段也不降级；link前失败
保既有文件、link后清理失败仍已发布。相关 JVM 测试和 Android 测试源码编译，保存原始日志/XML/前后输入 SHA。
独立审源码与证据后本地提交。真实 Android/SAF/原子移动平台/目标设备未执行。

### 实际实现与验证

`ui/SplitOpenRequest.kt` 实现纯请求及按版本分派，`SplitArrangementStore.kt`
仅访问私有安排文件；没有改变 Main、Editor、来源或 Session 的生产行为。
独立 store 实例用原子 create-new 处理碰撞，不共用暂存文件；成功只清自己的
stage，清理失败保留成品与 stage。recent 不写，旧 Native writer 尚未统一。

- session11301：新 API scaffold 的首轮 RED，3 套26例14失败，1184项
  main/unit/resources/build 输入前后同 SHA；不是旧界面真实回归复现。
- session97062：首轮实现3套32例全过，Android 测试源码实际编译通过，1253
  项 Kotlin/Java/XML 及5份构建配置前后同 SHA。session11794 消除4处新测试的 nullable
  warning 后重跑存储整套13例通过，生产未变。
- 独审发现 Android JSONTokener 接受注释及未引号 token，旧深度 scanner 会被
  其中的引号蒙蔽。session25045 将检查函数仅暴露给测试，未改行为，直接预检
  1例真实 RED；首个注释 payload 命中失败，后两个 payload 在该次因首断言失败
  未执行。修复为标准字符串转义／literal 边界及数字词法检查后才交平台 parser，
  不 catch VM 错误兜底。Android 源码另保三 payload 的直接预检与解析拒绝测试。
- session24013：最终3整套33个唯一例全过（codec15、store13、legacy5），
  0失败／错误／跳过；Android 测试源码重新实际编译通过（新增5个方法未运行）。
  1253项输入前后同 SHA。最终结果整套覆盖前两轮，不叠加重复用例。

原始证据为 `tmp/mixed-persistence-{red,final,recheck,boundary-red,guard-final}`
各自 `.log`、`-results/`、`-inputs.json`、`-summary.json`；最终以 guard-final
为准。runner/init、实际命令、JDK21/SDK、隔离构建与项目缓存见 summary 及
`tmp/run-mixed-persistence-check.py`、`tmp/build-mixed-persistence.gradle`。
最终独审核对原始 XML 的33项集合、1253项当前 SHA 及独立库存、runner/init
与隔离命令全部一致，Required 已闭合；审计记录 `tmp/mixed-persistence-final-review.json`。
Android org.json、Android 私有文件系统硬链接、系统重建、SAF/PDFium、Surface/GPU
与 MovinkPad Android15 ProPen3 均未实际运行；无 APK／安装／推送／合并／发布。
JVM 临时目录的硬链接／并发／故障注入已实际运行，不等同 Android 验收。

代码与测试本地提交：`6726c8ec`（格式）、`8da30d1e`（存储）、
`19c17096`（Android 回归源码）；文档检查点随后提交。整体目标仍未完成。

## 后续依赖（均未完成）

S2：实际当前完整 Map+NOTE 才可保存；Map 实际 saveAttempt 与 Native 最新
成功 publication、双物理输入稳定、最后票重核后写安排。显式打开先读两份来源，
Map 实际 UUID/SHA/actor、Native 实际 UUID/原生页契约/身份域最终重核，
同步采用两侧和布局；物理第一份比例在实际槽重映射后不变；恢复相机不改历史。
Saved/recent/Main/槽 Home 统一分派，失败保原工作区。

S3：cold descriptor 与可重复 recent 分开；Native 缓存先 prepare，Map actor
就绪后再采用。旧 Session 无已核来源版本，不得自动拿当前原件 SHA 给缓存
正文背书，也不能打开原件替掉未保存缓存；必须保 sticky 未核源保护和独立
另存恢复路径，不启用半套 NativeWorkingSession producer。配置重建继续保留
现有 Entry/actor，不重开图文件。坏/错配恢复资料不清除。

Map+Map/同图双视图、混合全局网络/原件库返回及其余 14 组需求持续必需。
