# XNotes for MovinkPad：协作约定

本文件只记录**当前有效**的长期规则。一次性授权（某次打包、推送、合并）及其历史记录在 [`docs/movinkpad/authorization-history.md`](docs/movinkpad/authorization-history.md)，过期后不再约束后续工作。新规则写入对应主题，替换旧条目而非在末尾追加。

## 开始工作

- 先读 `MOVINKPAD.md`、`README.md`、`docs/movinkpad/project-brief.md`，并检查 `git status`。
- 本仓库自成一体，外层工作区的诊断工具不是本仓库的模块。外层工作区与本仓库各自维护说明；原型功能以本仓库为准。
- 目标设备：Wacom MovinkPad Pro 14、Android 15、Pro Pen 3。产品参照：OneNote Android 平板版（不改为 Windows 桌面版）。
- 保留上游许可证与第三方声明。
- 需要 Python 环境时用 `uv` 创建。临时文件放在已忽略的 `tmp/`，不放进源码或正式文档。

## 工具链与构建

- JDK 21、Gradle Wrapper 8.13、AGP 8.12.3、SDK / Build Tools 36 / 36.0.0、NDK 27.0.12077973、CMake 3.22.1。不要引入诊断工具的 AGP 8.12.0。
- 允许在本机直接编译，遵守执行环境权限。
- 交付限定 ARM64；不使用 `android.injected.build.abi` 等 IDE 注入参数，避免生成 testOnly APK。
- Release 构建的 `buildDirectory` 放到 iCloud 同步目录之外，避免出现 `… 2.*` 重复文件（见 Release 记录）。
- 分支测试使用独立工作树和构建目录。ARM64 交付与 x86_64 模拟器构建除输出目录外，还需用 `--project-cache-dir` 隔离 Gradle 项目状态，避免切换构建目录后 DEX 增量缓存残留旧路径。

## Git、打包与发布

- 保留用户已有修改。功能阶段或修复完成并通过相关验证后，自动本地提交与本次任务相关的改动，无需再问；不夹带无关修改。
- 推送、新建远端分支、改 Git 配置、打包 APK、设备安装、GitHub Release 均需用户**每次**明确要求；某次授权只对该次有效，不顺延到后续任务。授权与合并的历史记入 `docs/movinkpad/authorization-history.md`，不写入本文件。
- 合并开发分支后保留原分支，除非用户要求删除。

### 安装身份

| 构建 | 包名 | Gradle 参数 |
| --- | --- | --- |
| 正式版 | `com.xnotes.movinkpad` | 无 |
| 旧双窗格测试版（不再维护） | `com.xnotes.movinkpad.splitpreview` | `-PsplitPreview=true` |
| 旧 AI 测试版（不再维护） | `com.xnotes.movinkpad.aipreview` | `-PaiPreview=true` |
| 主分支测试版 | `com.xnotes.movinkpad.branchtest.master` | 初始化脚本，`-PbranchTestId=master` |
| 图层测试版 | `com.xnotes.movinkpad.branchtest.layers` | 初始化脚本，`-PbranchTestId=layers` |
| 按键录入测试版 | `com.xnotes.movinkpad.branchtest.penshortcuts` | 初始化脚本，`-PbranchTestId=penshortcuts` |

各测试版与正式版独立安装，互不覆盖。分支测试包使用 `scripts/branch-preview.init.gradle`；后续更新沿用各自包名与签名，递增 `branchTestVersion`，交付时报告包名和源码提交。旧 AI / 双窗格专用测试包不再纳入本地打包。不同功能分支未经用户要求不相互合并。

### 签名与覆盖升级

- 覆盖升级必须沿用已交付 APK 的签名，并提高对应包的 versionCode；交付前核对签名证书与安装身份。不要重新生成密钥，不要求用户卸载旧版。
- 原签名密钥通过已忽略的 `keystore.properties` 配置，Debug 与 Release 共用；不提交密钥或密码，不覆盖服务器默认调试密钥。证书 SHA-256：`A0:45:3B:7B:E6:C0:C8:23:9E:86:16:20:86:12:C4:12:CF:50:1D:A8:89:8C:7A:04:50:CC:92:3C:8E:35:84:51`。
- `-PmovinkpadPreviewSigning=true` 让 Release 使用 Debug 签名配置（沿用历史本机签名）。
- Release 启用 R8 与资源压缩，关闭 debuggable。
- 发布说明必须保留尚待真机确认的边界，不把候选修复写成已解决。

## 验证

- 运行相关单元测试与 Android 事件测试。模拟器或 JVM 结果不代表目标设备兼容；硬件验收单独记录设备、系统版本、笔型号和结果。
- 平台行为不同的回归（如 Android `org.json` 与 JVM 测试依赖对 `JSONObject.NULL` 的处理差异）需保留仪器测试，不能用 JVM 结果替代。
- 涉及前缓冲、Surface 重挂或 GPU 的显示修复，区分源码验证与目标 GPU 真机验证。
- 未运行的验证明确标记，不宣称通过。

## 笔输入

- Pro Pen 3 实测原始位保持 `0x20`、`0x40`、`0x04`，保留原始位掩码与轴值，不改写原始事件。
- 从触摸与悬停的 buttonState 检测边沿：比较 MOVE / HOVER_MOVE 上的状态变化，并对独立按钮事件去重。区分手写笔、手指与取消事件。不硬编码 deviceId。
- `pen/ProPenButtons` 是纯状态机，运行时接收映射槽位；`pen/ProPenInput` 为两种编辑器表面适配 Android 事件。
- 保留被取消的笔画与工具设置；笔画中途不切换工具。
- 选择移动／缩放／旋转等未确认预览在取消、第二指接管或清选区前恢复原几何、连接绑定与索引，不产生内容历史。成功操作先完成命令再通知宿主，避免自动切工具误回滚已确认内容；指针 IDLE 不等于保存捕获已稳定。

### 通用按键录入

- 以实际 MotionEvent / KeyEvent 信号录入并绑定工具或操作。旧 `pro_pen_bindings` 不迁移、不读取；新 `pen_shortcuts` 从空列表开始。
- 持久化使用设备描述符与事件身份，不持久化临时 deviceId。录入仅在设置页显式启动，不添加全局无障碍监听。
- 录入时每种事件类型最多接受一个候选，禁止把多个实体键静默合并。
- 笔画中的按钮转换仅在正常抬笔后提交；取消整笔丢弃全部转换，取消某键只丢弃该次按压。
- 所有窗格交接（标题、触摸、悬停、关闭）传递带输入身份与有效 HOLD 顺序的快照；继承的 SELECT、已被手动选工具覆盖的 HOLD 不得重触发。View 重挂与窗口失焦分开处理。
- HOLD 只改变实际输入工具；工具栏高亮与切换动画使用独立的正式选择状态，SELECT 与手动选工具才更新正式选择。分页、画布和共用工具栏遵循同一规则，不得为稳定工具栏而取消临时功能或在笔画中切换工具。

## 界面

- 仅黑白：纸面、面板、菜单为纯白 #FFFFFF / 纯黑 #000000，夜间反转，选中态完全反转。允许中性灰阶（R = G = B）表现层级（次要文字、分隔线、填充、背景）；不用彩色强调色、渐变或装饰阴影。笔迹颜色与 PDF 属于内容，不受主题约束。
- 页面上的工具标记（选框、套索、手柄、光标、参考线、文本框边框）须与页面形成对比，而非跟随主题：读取 `CanvasState.palette` / `TextEditHost.overlayPalette`，夜间模式下在 PDF 或浅色页面上改用黑色。无限画布同一视图混有黑纸与白图片，因此 `OverlayTessellator` 在每个标记下垫 1 px 反色描边；其深色取 (1, 1, 1)，因为夜间笔迹会把精确的 (0, 0, 0) 重绘为白色。
- 界面字号与间距只取自 `ui/theme/Tokens.kt`（`TextSize`、`Space`），控件触控区不小于 48 dp（`MinTouchTarget`）；需要新值时在此新增 token，不写字面量。详见 `docs/movinkpad/ui-monochrome-tokens-2026-11-03.md`。全界面视觉与真机验收待执行。
- 用户可见文字放入字符串资源：英文在 `values/`，相同的简体中文在 `values-b+zh+Hans/` 与 `values-zh-rCN/`。AI 界面文字在 `ai.xml`；`ai/` 后端消息经 `AiText.t(AiMsg…)` 与 `ai_messages.xml`；`storage/` 消息经 `StorageText.t(StorageMsg…)` 与 `storage_messages.xml`。持久化键（AI 轮次状态、默认会话标题）与面向模型的提示词保持不变，仅在显示时翻译。
- 功能名称统一为「AI」，截图入口、浮窗和设置不再使用「问 AI」。

### 工具栏

- 新增按钮必须在 `core/tools/ToolbarLayout.kt` 的穷尽分类中声明支持的文档类型及是否为独立文档设置，不给未知按钮兜底为通用。
- 编辑工具在所有打开的文档都支持时放共用栏，否则只放支持它的文档栏；缩放、导航、视图、样式等独立状态始终留在文档栏。布局按打开的文档类型分配，不随焦点增删顶栏。增改分类时补齐笔记、PDF 组合、单独画布及不重复显示的测试。
- 插入空白：分页文档（笔记 / PDF）分屏时属于顶部共用工具；画布不显示。
- 分屏时文档栏的编辑工具高亮仅在该窗格有焦点时显示；失焦后隐藏高亮及滑动标记，不修改后台工具状态。缩放锁定等独立文档设置仍显示各自状态，共用工具栏跟随当前焦点。

## 文档打开与分屏

- 无限画布定位为画画与草稿，只能单独打开，不进入分屏：不提供分屏按钮与「在旁边打开」，分屏窗格内拒绝打开画布并提示。含画布的旧 `.xsplit` 在替换当前工作区前拒绝打开；上次会话的含画布分屏只恢复主窗格自身会话。偏笔记的结构化与关联需求交给思维导图模块。详见 `docs/movinkpad/canvas-sketch-only-2026-11-07.md`。
- 主菜单打开普通文档进入单文档模式；窗格内首页打开文件只替换所在窗格；只有显式打开或恢复分屏才恢复双窗格。
- 替换工作区前等待保存完成，避免异步关闭回调误关新文档；文档未打开成功时，不得因预检查失败丢弃当前工作区。
- 换模型后的输入 reset 只丢弃旧草稿、连击、预览和异步请求，不通过新模型恢复或补交旧手势。嵌套自动滚动、PDF 文字请求和颜色拖放任务也须退休；旧事务的确认／取消／保存由替换前的 owner 流程处理。见 `docs/movinkpad/reference-stable-capture-2026-11-09.md`。
- 原生笔记/画布完整同源准备后才能替换；采用前重新settle所有相关documentOwner，并重新核实实际来源版本，自己保存改变来源时须真正重读新模型。最后IO前后核runtime owner、所有相关model的revision/路径及两个native job身份，不能只凭dirty再次为false采用旧读；最终同步核工作区、在途写与笔接触。打开/诊断/保存续接/选择器回调绑定实际模型，迟到不作用于新模型；打开登记在同一存储锁内拒绝未知pending保存。见 `docs/movinkpad/reference-verified-open-2026-11-08.md`、`docs/movinkpad/reference-open-after-save-2026-11-09.md`。
- 分屏文档的关闭 / 返回先显示该窗格的文件选择首页，关闭首页才移除窗格。暂留文档由已有的保存 / 放弃保护处理替换与最终关闭；同文件共享视图关闭拥有者时，保留另一侧首页的物理位置。
- 文件树只为已确认存在子文件夹的节点显示展开箭头。
- 分页编辑器跨 AndroidView 宿主重挂时，必须通过 `Editor.surfacesForAttach()` 替换 GlWetPad / FrontInk：先归还暂存笔迹并断开旧回调，保留文档、普通画布、缓存及前缓冲开关。未经目标设备回归不要恢复复用旧湿墨 View。底层 RenderNode / crop 机制仍为推断，不把该修复扩大为全部场景或其他设备的验收。详见 `docs/movinkpad/split-wet-ink-2026-11-04.md`。
- 无限画布的跨宿主重挂候选通过 `InfiniteEditor.surfacesForAttach()` 在旧 GL 线程完成 detach 后替换主绘制 View 与湿墨 View；保留同一 viewport、scene、文档与撤销，归还暂存及正在绘制的笔段，不复用旧 renderer 的帧回调。首次挂载不替换。画布不再分屏后，主菜单往返仍会卸载并重挂宿主；稳定工具栏布局只减少不必要拆挂，不能取代真实重挂时的替换保护，也不据此宣称黑区已解决。见 `docs/movinkpad/canvas-split-black-2026-11-07.md`、`docs/movinkpad/stable-pane-layout-backport-2026-11-07.md`。

## 存储、PDF 与迁移

- 笔记完全本地离线保存，无账号、同步或应用后端。修改须持久化；涉及存储、导入、删除、迁移的变更，验证恢复路径与失败行为。
- 新native请求固定捕获item写入字段；NativeWorkingSession先以实际owner只读epoch observation预约，只有requiresBody才固定正文，view-only不持模型正文、不遍历item、不授保存权限；Stroke仅pin不可变Samples前缀，不复制/重编码采样。每页/画布单独映射胶合与图层，复制嵌套可变容器；画布不支持TextItem时拒绝而非略去。旧默认浅快照保持；Editor实际保存已接固定视图与稳定屏障，NativeWorkingSession生产迁移仍未完成。见 `docs/movinkpad/reference-frozen-writer-2026-11-08.md`、`docs/movinkpad/reference-owner-capture-2026-11-09.md`。
- 生产保存先等旧编码，再在Main完整dispatch之后核actual owner／模型／笔接触／内容事务，固定捕获后才交IO；不自动确认或取消笔、选择、样式或表格预览。显式保存按原blur语义提交文字与flow burst，等待输入时不弹阻断确认的modal。共享文档核两侧输入，关闭／替换最后重新核实；退役实例不开始新捕获，已接受IO不取消。旧会话目录FIFO及checked写入不授native权限，正文复用只凭同runtime模型／bodyRevision成功记录；未采用secondary与失效文档占位不清旧恢复资料。
- NativeWorkingSession写metadata schema2、兼容1；T与当前正文同head持久，须实际staged/private Main permit/owner/head代次证明，不能拿旧T背书新失败尝试。cold未认领T只可读资料、不可普通写/clear；native4受控ack与actual Receipt accepted-ID完成已实现，cold actualB恢复/认领已实现（实际采用/Read转交与首次新正文提交后才开保存）；活owner接管与生产未启用，不能切半套生产或混用native-only写入绕过T。见 `docs/movinkpad/reference-native-handoff-2026-11-08.md`。
- 原生普通保存可prepare固定候选后再commit：阶段证明绑定实际storage issuer、canonical journal、本次写入record摘要与A/B版本；仅实际成功commit签请求receipt，prepare不等于保存成功。协调v4 stage仅接受实际同head Publication的typed ack，重启及旧ensure均拒写；完整cache/producer接线仍待完成。验证/复制/读回逐块检查源epoch与取消；见 `docs/movinkpad/reference-native-handoff-2026-11-08.md`。
- 严格保留已写版本须有有效typed SAVE、实际目标=候选摘要及完整原生契约；事务登记重查URI/kind/页契约，最后大包hash后重读target、再有界核marker后清日志。返回实际读版本不冒充请求发布receipt；external PDF实际SHA在lease内核对。生产producer接线仍待完成，见 `docs/movinkpad/reference-keep-written-save-2026-11-08.md`。
- 保存已写入文件而后续步骤失败时，之后选择“放弃修改”保留已写入版本、只放弃之后的新修改，不回滚原文件。未核实的部分写入保留pending资料，不冒充完整版本；见 `docs/movinkpad/reference-discard-written-2026-11-08.md`。
- 原 PDF 与只读导入原件保持不变；批注与可清理显示缓存分开。大型扫描 PDF 与工具光标方案仅在 `tasks/plan.md`、`tasks/todo.md` 规划，未完成。
- 导入与格式升级时保留旧笔迹及原 PDF 负载的原始字节；这不构成修改普通笔画简化的授权。迁移须先暂存并校验再发布，不得借模型重新编码来升级元数据或迁移笔迹。
- 页面引用登记契约按文档 UUID 单调保存，位置丢失不撤销；保存预检须同时核对位置与观察 UUID 的期望，typed 契约失败不得降级为“不支持拼接”再覆盖。schema2与header预检组件已实现，生产接线仍待完成，见 `docs/movinkpad/reference-contract-registry-2026-11-08.md`。
- 文档 UUID 与资源指纹分开。ID 缺失或重复时需显式恢复，不随意选择。登记表 / SAF 恢复及基于 ID 的分屏恢复已接入源码，设备验收待执行。
- 分页首次保存／明确另存走`saveAsToThen`及NativeCreation，非空目标（含原URI）不得降级为普通覆盖；选择器固定原请求及运行时唯一键，不预先打开输出流。原owner先准备PageReferenceCopy，固定正文发布成功后才最终核模型／来源／scope／PDF指针并采用；采用前判断晚到修改并保dirty，不以延迟saving modal当输入冻结证明。PDF采用实际受管文件与身份，临时lease跨编码和发布；失败保持原模型，可独立续完保留副本。文件浏览器副本固定原来源请求，只读实际存盘内容，经空目标发布，不采用或保存当前草稿；共享导出保持身份，独立副本投影新身份。
- 原生新建/导入/提取/合并不使用通用SAF写流回调；typed固定正文先核空目标，再经NativeCreation发布，不在失败或写后取消时删除provider返回的未知目标。分叉在Main准备/采用页scope，最终核原owner/路径/身份/来源/PDF，采用成功才登记ForkLedger；晚到内容保dirty。提取双端等待输入稳定，移动前核捕获版本，源有新修改则只保留副本；合并持源PDF租约到合成与发布结束。
- 引用以页面为单位（2026-11-07 用户决定）：分页笔记 / PDF 只为页面保存永久引用编号，画布按文档 UUID 引用，不为对象分配编号；可选区域提示只存于 `.xmap`。页面删除后引用显示已删除、不改指；复制得到新页面编号。对象编号已从模型与笔记/画布编解码撤除，B2c 取消；原始整包复制/导出、分页另存与分叉已接通新页面身份；来源往返及完整引用闭环仍待接通。页启用使用独立ACTIVATE_PAGES事务，首发与续写均证明原字节A→B权限并绑定同一候选；旧保存/恢复不得自动消费，成功重新读取实际页目录后再供引用使用。详见 `docs/movinkpad/reference-page-level-2026-11-07.md`、`docs/movinkpad/page-reference-implementation-2026-11-08.md`。
- 页面引用身份独立于 Page.uid 和 GlueIds，ReferenceIdentity 一次性赋值／恢复。拥有者捕获 snapshot 前准备新页面 ID，writer 不补活模型 ID；snapshot 冻结页面身份，页面深复制取得独立身份。已有文件启用须走原字节升级候选，不借模型 writer 升级。页面身份缺失／重复须显式恢复；对象缺失不使页面身份失效。显式仅笔迹副本使用独立恢复入口，不覆盖原文件，可略去无法读取或层归属损坏的对象（新页面身份与原件不相交）；略去数量独立按对象出现次数统计并告知用户，幸存连接线不得指向略去对象。
- 单份 PDF：内部 `.xnote` 用身份元数据 v2 引用私有不可变 PDF 对象；分享 / 导出生成嵌入原 PDF 的便携包（身份 v1）。旧包在打开时按需迁移：先校验并持久保护原件，再逐条保留非 PDF 负载并安全发布。
- 恢复资料的只读准备与原件来源权限分开：`NativeWorkingSession.Recovery.Unbound` 持实际正文／解码资产／PDF租约，调用者须关闭；渲染与复制任务结束后再释放。缺失、清除和损坏不能混作同一种空状态；只有实际Absent可检查旧保存槽。独立副本按持有原始bundle复制到Creation指定新UUID，不通过可变预览模型重编码，不重新绑定或覆盖原件。Backstage实际窗格入口已接通历史预览与独立副本；所有界面动作和选择器结果绑定原请求，关闭先等待IO再释放。写后失败保同一typed事务，重启续完须明确选择原目标；不自动采用或清恢复head。详见 `docs/movinkpad/reference-recovery-preview-2026-11-09.md`；平台及真机验收待执行。
- 共享不可变 PDF 对象使用索引引用、活跃租约与延迟回收；不得被缩略图、合并或导出临时清理删除。内部笔记单独复制不构成备份，完整备份必须经应用导出。平台及目标设备验收待执行。详见 `docs/movinkpad/pdf-single-copy-2026-11-05.md`。
- 引用式笔记的私有原件缺失（其他安装身份迁移、重装、清除数据）时，恢复对话框提供重新关联（原 PDF 或仍内嵌原件的旧笔记 / 导出包，SHA-256 必须与记录一致，不得用不同 PDF 替换底稿），以及不写原文件的「仅笔迹」副本。受管原件只由引用与租约回收删除，任何临时文件清理（如 `adoptOpenPdf`）都必须跳过 `PdfResources.isOwned`。详见 `docs/movinkpad/pdf-relink-2026-11-05.md`、`docs/movinkpad/pdf-original-deletion-2026-11-06.md`。

- 工作会话的来源描述仅是待核实数据，不是保存凭据；恢复必须重新核实真实来源版本。NativeWorkingSession的便携正文与严格metadata共用原子head，坏/未来格式不得被新capture、clear或GC覆盖清除；视口复用只凭同owner/kind/UUID/bodyRevision的成功提交证明。生产会话接线仍待完成，见 `docs/movinkpad/reference-native-session-adapter-2026-11-08.md`。

## 图层

- 分页 / PDF 按页、无限画布按文档管理 DrawingLayers。图层 ID 与对象归属在删除、移动、跨页转移、橡皮碎片和撤销重做中保持一致；保存快照冻结成员关系，不读取后续编辑过的 layerId。
- 默认单层沿用历史序列化形式；原 PDF / 模板、PDF 文字标注和跨页排版正文仍是独立的背景或排版内容。
- 多层实时笔迹按层序合成，不能直接放在顶层前缓冲；保留用户的前缓冲设置与上述湿墨 View 重挂规则。源码验证不替代真机 GPU、视觉与性能验收。
- 主要入口是绘画软件式侧栏（参考 Krita），不恢复为图层列表弹窗：分页 / PDF 在文档侧栏的图层页签，无限画布使用同类常驻侧栏。本阶段仅侧栏与缩略图，不做分组、透明度或混合模式。
- 缩略图与名称区域按下后移动即可排序（保留系统移动阈值防误触），不要求长按；点击选层、双击名称重命名。落下形成一次撤销，取消不改模型；选择当前层不占用内容撤销记录。面板跟随当前页面 / 文档与撤销更新。
- 缩略图只显示单层自身内容，隐藏层仍有预览，不混入 PDF / 纸张背景。显示范围：分页笔记 / PDF 为不显示、已有笔记区域、整页；无限画布只有不显示和已有笔记区域。内容模式按单层内容边界裁切；不显示时不计算边界、不生成位图；显示设置不进入撤销栈。
- 异步渲染只读几何快照；翻页、关闭或版本变化须取消旧任务。预览缓存不写入笔记格式。

## 思维导图

- 原生 Kotlin 导图使用独立 `.xmap` JSON 格式（`xnotes-mind-map`，写入版本 3，兼容读取版本 1／2），不得交给 `.xnote`／`.xcanvas` ZIP 编解码器。文本与手绘节点使用稳定 ID；手绘保留节点局部坐标的压力和时间采样，布局与子树移动不重编码笔迹。
- 来源预览为派生数据，不增加内容撤销步骤或清空重做；IO 对捕获的所有历史帧逐帧验证预算后，Main 只采用同一历史代次的整体结果。来源存在性／目标的生命周期票与历史代次分开，目标往返及删除后撤销不复用旧请求。最后 PNG 绑定实际描绘的原目标／版本，失效或换范围不清图、不冒称最新；固定空区域的证明只用于同目标同版本。图片使用受限静态 PNG 工厂、摘要／CRC／有界解压及总量预算，不能向历史暴露可改字节数组。预览不是源文件备份，受管 PDF 不归图片清理所有。同批同文件共读／解码／PDF 会话；图文件只在既有规范 URI 锁内读完整字节，再到锁外绘制。缓存语义摘要只防 self／cycle 刷新自激，来源写入与冲突仍核对实际 SHA。详见 `docs/movinkpad/mind-map-source-preview-2026-11-09.md`。
- 内容历史使用不可变快照；节点编辑确认后形成一个文档操作，取消手势／取消编辑不提交；根节点不能删除或换父，禁止父子循环。恢复日志先原子写入，再写 SAF；失败不得冒充保存成功，外部修改先核对摘要，不覆盖冲突文件。冲突只经用户显式选择解除：「保留当前版本」以目标现状为基准覆盖，「改用文件版本」读取成功后才删除日志；另存副本使用新文档 UUID；无修改时不因切到后台而保存。独立主题保持原位，其分支随布局排列。新副本仅接受可读空目标且无既有恢复日志，先原子保留候选再发布；同实际文件的URI别名共用锁并兼顾已知旧日志。预存或已同步的staging保留，只有本次拥有而未完整同步的临时staging可在失败时清理。详见 `docs/movinkpad/mind-map-review-fixes-2026-11-07.md`。

- 空白落点以新卡片中心为锚，节点与无方向关联线先保留为Session草稿，确认按最新图一次提交；取消不产生节点/线。文件选择从launch绑定文档代次与独立请求ID，不在结果返回时改绑。替换文档期间禁止新事务，普通自动保存不废草稿；来源编辑只合来源字段，连续回调同步推进自己的已成功列表，外部变化须重试。
- 手绘节点采样使用原 node-local 原点，允许有限的框外坐标；显示裁切不得夹紧或删除采样。原位／放大使用同一输入事务、工具与文档历史，落笔锁定归属和变换，每笔抬起一次提交；进入退出放大不整理、不写内容历史。手绘属性只合并所编辑字段，不覆盖其间新增的来源或笔迹。
- 页面来源选择保留原图/节点/草稿ticket到最终选择；先等笔接触结束，再重读实际来源核SHA与页成员。节点删除、草稿类型改变和图退休须取消相应IO；系统picker旧占位保留至其结果消费，防迟到结果错绑。普通读取只保留读权限，页启用/续写经明确确认。来源登记核实际类型/单调页契约，JSON导图不能重分类已有原生身份；追加只合并最新来源字段且拒超限，不覆盖正文。位置显示可选的上次核对页码，不展示内部UUID、不用显示页码定位；实际身份仍是永久pageId，范围/来源更换须重新核对提示。
- 从阅读页发起引用固定实际窗格／owner／模型／页面；保存以实际读取或成功发布的正文版本为基线，最终页目录须匹配该版本。未知基线不强制保存，旧文件启用后实际重开再发起，不能按页序号给旧模型补编号。该模型的后续自动／暂停／关闭保存也保持严格基线，取消引用不解除保护；实际替换才退休。已接受的保存完成后仍记录真实 B，不能把之后的 C1 标为已保存或因取消请求漏掉 C1 自动保存。见 `docs/movinkpad/mind-map-current-page-reference-2026-11-09.md`。
- 同一规范 URI 的导图经 `MapDocuments` 共享唯一实际 `MapSession`，Activity／引用流程只释放自己的租约。追加使用原目标代次及节点寿命票，在最新节点上合并；新节点与来源一步撤销，文本编辑／视口恢复中不整理分支。保存确认必须使用本次 `saveAttempt` 的实际结果，日志保留不冒充写入成功，重试不重复追加；不在后台另开一份模型覆盖活会话。
- Main 图宿主由 ViewModel 保留 Entry／租约／运行时 presentation，配置重建不通过 URI 重开清历史；composition 只按宿主 token 解绑，旧打开／保存关闭回调不得影响新宿主，清除旧 native proof 以免保留旧 Editor。显示图前等待全部实际 owner 和两个物理输入面及已接受任务，最终同步核只读停车观察；不强制保存或取消笔。图前台不挂载原 native SplitHost，返回沿既有 `surfacesForAttach()`，不能用 Compose 盖板遮住 on-top 湿墨 Surface。全局 native 键／四指／点击 latch 不作用于图，图触摸不经旧 Samsung 改写；每宿主相机仅 runtime、稳定尺寸／IME 后恢复，不写内容历史。见 `docs/movinkpad/mind-map-main-host-2026-11-09.md`，实际 Android 重建／Surface／GPU 待验。
- 来源打开只读核文档 UUID／永久页 ID，不经 ensure 补身份或用页码改指；已开来源保 actual owner／原槽／修改和历史，已知重复位置拒绝选择。全窗口图跳来源不恢复停放分屏，其余 owner 复用原保存／放弃保护；最后 IO 用实际正文／任务版本核验，原生采用与图隐藏之间不挂起，迟到清理不回滚。Entry 只存纯图返回书签，显式返回才恢复选择／来源条目，范围或图世代变化不复活旧票。来源备注 IME 不覆盖普通相机，失效成员收尾仍须解除相机保护。定位轮廓等 actual attach／稳定 preDraw，按真实纸面范围变换；fresh DOWN 取消尚未应用的定位，不在笔画中移动相机、不改原事件。详见 `docs/movinkpad/mind-map-source-navigation-2026-11-09.md`；真实 Android／Surface／设备验收仍待。
- 文本原位编辑复用 TextEditorOverlay／TextEditHost；组合输入、选择和草稿按事务 token 保留，不逐键写历史。确认核对初始正文并按最新节点宽度合并，保留并发来源；取消与迟到 IME 回调不写文档。光标避让仅改变相机，退出等待 IME／宿主尺寸稳定后恢复视口与选择。
- 节点选择与手绘套索为独立工具，选集／框选预览不写正文历史或导出。组移动按子树并集只平移一次，不隐式换父；批量删除先排除中心根再求子树，仅删图中节点与线。对齐仅支持至少两个自由顶级主题并携各自分支移动。轻点／框选／移动／删除／对齐使用实际文档、会话代次、正文与选择单调版本票据；签发核对视图原选集，旧按钮不能为新选区重新签票。取消后隔离余流，双指相机自身回调与外部取消分开。详见 `docs/movinkpad/mind-map-node-selection-2026-11-09.md`。

- 画布连线只把节点对送入同一普通线草稿，确认前不写文档。连接余流使用独立drain，在普通ink接管前拦截，不改旧笔优先规则；armed时禁quick-scale，笔接触冻结相机，取消/失焦/resize/disable归还busy。提示用图内覆盖层、工具栏标题限单行，避免重排取消模式；支持touch-mode焦点。详见 `docs/movinkpad/mind-map-canvas-connect-2026-11-08.md`。
- 普通关系编辑以initial edge和draft分开，经MapSession从latest文档提交；创建/修改/删除各一次commit，取消零写入。重复定位返回latest已有线，不携带另一线草稿说明；无向端点互换为语义no-op。端点/线失效不崩溃、不覆盖；英文“引用自”用“A references B”，不反转箭头语义。详见 `docs/movinkpad/mind-map-relation-editing-2026-11-08.md`。
- 普通线、箭翼与说明的绘制／命中共用MapRelationGeometry，48dp为屏幕目标全宽，按density/当前scale换算；节点、来源条、手绘正文及连接入口优先，父子线不混入普通关系命中。节点／线选择互斥且仅为会话状态，不进内容历史／导出；现有线必须以actual initial进入原草稿，全部关系列表保持可达。
- 导图native press/preview绑定内容与会话epoch；相同内容重载、禁用或内容更新也退休旧拖动，避免旧UP回退新来源／正文。结束POINTER_UP、失焦／卸载须清detector定时与适当余流，不阻碍下一次笔接管；正常同会话节点双击保留。见 `docs/movinkpad/mind-map-relation-hit-2026-11-09.md`。

## AI

### 网络与密钥

- AI 是用户授权的可选联网功能：只有点击发送后，才把所选截图、PDF 内容和当前会话发给所配置的供应商；无账号、同步或应用后端。
- API 密钥加密保存在 `noBackupFilesDir`，与历史分开，不进入笔记导出或日志。不得把其他聊天供应商的密钥转发给 DeepSeek。
- 模型列表只在用户点击「从供应商获取」时请求所配置供应商的 `/models`。

### 供应商与设置

- 多供应商（参照 DeepSeek Harness）：每个供应商有永久、不显示的内部 ID，会话与默认值按「供应商 ID + 模型」引用，不要改成按名称或 URL 引用。旧单供应商配置迁移为固定 ID `default`，保存时顶层字段仍写默认供应商以便降级读取。
- 供应商被删除的会话不得回退到其他供应商发送，须显示「选择模型」并拒绝发送。仅支持 OpenAI Chat Completions。详见 `docs/movinkpad/ai-providers-2026-11-05.md`。
- 自定义提示词与思考深度只用于回答请求（回答中的联网与 PDF 工具调用同样使用会话设置）；仅在不支持工具调用时退回的 PDF JSON 读取步骤使用 `AiConfig.forTools()`。会话内的模型 / 深度选择存于会话记录，缺省跟随默认。
- 上限（如 200 个模型）在添加时拒绝并提示，保存时校验失败，不得静默截断。
- 思考字段被拒自动降级：请求被 400 / 422 拒绝且供应商原因提到本次的思考字段时，去掉该字段重发一次，并在本次运行内记住该地址 + 模型 + 档位；该轮须标注已按模型默认回答，不静默降级。详见 `docs/movinkpad/ai-thinking-fallback-2026-11-07.md`。
- 输出上限字段按主机区分：OpenAI 官方 `api.openai.com` 发 `max_completion_tokens`（其推理模型拒绝 `max_tokens`），其他兼容接口仍发 `max_tokens`。请求失败时附上供应商错误体中的 `error.message`（单行、限长、遮蔽 API Key），非 JSON 错误页不显示。详见 `docs/movinkpad/ai-openai-http400-2026-11-07.md`。

### 联网搜索

- 默认关闭，只有开关，不让用户填写检索词，仅随显式发送执行。
- 开关打开时向回答模型提供原生 `web_search` / `web_fetch` 工具，由模型按需调用（有轮数与数量上限）。模型或接口不支持工具调用（请求被 400 / 422 拒绝）时不联网直接回答，并在该轮明确标注；不回退到单独的检索词识别。
- 搜索密钥独立加密保存，只发送到官方 `https://api.deepseek.com/v1/tools/search` 与 `/v1/tools/fetch`；网页由 DeepSeek 服务端抓取，设备不直接访问模型给出的 URL。
- 搜索失败明确反馈，不静默降级。网页资料作为工具结果返回、标为不可信数据并保留来源，不当作指令或替代问题。

### 会话与生成

- 会话任务由应用级 `AiSessions` 管理：关闭界面、切换页面、新建会话不取消生成，只有显式停止或确认删除才取消。生成状态与回答写入原会话，删除后的迟到回调不得复活记录。
- 生成进度显示真实阶段文字，不编造百分比。
- 流式正文读取原始 JSON 类型，只接受 String；禁止用 `optString` 强制转换 `content`。
- 回答中的公式参照 DSH 规则：`AiMath.normalize` 只在 AI 显示路径按 remark-math 区分公式与金额，不改笔记解析；未闭合或无法排版的公式显示源码，带自身颜色的公式不排版。详见 `docs/movinkpad/ai-math-2026-11-06.md`。
- 历史只保存在本地 `noBackupFilesDir`。原子写入失败保留已有文件；损坏记录保留并提示。进程重启后把生成中的记录标为中断，不冒充完成。
- 删除即移到回收站（记录带 `trashedAt` 留在磁盘，与历史分开），可恢复或永久删除，保留天数沿用笔记回收站；回收站关闭（0 天）时直接永久删除。不提供撤销倒计时。

### 草稿与附件

- 草稿与已就绪附件属于会话而非窗口。保留每个会话的搜索选择；切换页面时导入继续进行；会话删除或替换后拦截迟到的导入回调。
- 提交的草稿在问题保存后才清除，不擦掉更新的输入。
- 尚无任何对话轮次且未在发送时，关闭窗口或点「新会话」即清空提示词、截图、附件及其导入任务，不留下空白历史会话；不能以提示词是否为空来判断。清空后拒绝旧文件选择器与附件导入的迟到结果。已有对话则保留草稿与附件。
- PDF 附件：只为显式选择的 PDF 在 `noBackupFilesDir` 下建快照；模型读取限于当前会话的附件 ID，不开放任意文件系统访问。原 PDF 保持不变。
- PDF 读取参照 DSH `read` / `read_image`：回答模型通过原生工具 `pdf_read` / `pdf_search` / `pdf_view_page` 按需读取，与联网工具同一循环；参数错误与超预算作为工具结果交还模型，不中断回答；页图在该轮工具结果后以一条用户消息附上；带页图的请求被拒时去掉页图、告知模型改读文字并继续，不让整轮失败。接口拒绝工具（400 / 422）时才退回旧 JSON 读取循环。详见 `docs/movinkpad/ai-pdf-native-tools-2026-11-06.md`。
- PDF、分页笔记与无限画布的截图操作都须支持 AI。真实供应商与目标设备验证与 JVM 测试分开记录。
- 新建笔记「用 AI 生成」优先在应用内生成：仅在点「生成」后把要求与内置 `templates/AI.md` 发送给表单所选（缺省为 AI 设置默认）的供应商与模型（不带聊天自定义提示词；表单选择不改默认值），结果须经 `AiTemplateGen.check`（可读取、类型一致、图片节点符合模式、无被丢弃节点）后才入库；不通过时带原因让模型修正，有次数上限。有可用的生图模型（供应商的 `imageModels`，默认图像模型 `imageProvider` / `imageModel` 须为其所列之一，OpenAI 兼容 `/images/generations`）时，封面可由文字模型写含恰好一个 `images/cover.png` 的模板与画面描述，检查通过后才调用图像模型一次并打包入库；只读取 base64 图片数据、不跟随返回 URL，图像请求用图像供应商自己的密钥。不内嵌 Python。用户照片封面仍走「用图片制作封面模板」或「使用其他 AI 助手」。详见 `docs/movinkpad/ai-template-generation-2026-11-07.md`。
- 供应商分聊天模型与生图模型两张列表，可手动填写或「从供应商获取」后按名称分段勾选（`AiModelKinds` 仅为推测）；仅有生图模型的供应商不成为默认聊天供应商、不出现在聊天模型菜单。

## 文档维护

- 每完成一个功能阶段、修复一组问题或准备交付时，按需同步本文件与 `README.md`，不积压到最终交付；无变化时不为更新而改写。
- 本文件记录长期规则；`README.md` 面向用户（功能、安装、使用、构建 / 测试入口，区分正式版与测试版）；版本、开发历史和验收证据放在 `docs/movinkpad/`。一次性授权记入 `docs/movinkpad/authorization-history.md`。
- 已有的 `docs/movinkpad/` 历史记录作为当时证据保留，不为统一风格而改写。
- 新的 `docs/movinkpad/` 记录以下面三项开头，正文自由书写：

  ```markdown
  # 标题（YYYY-MM-DD）

  用户要求：……（复述需求与澄清）
  状态：已实现 / 已验证（测试项与环境）/ 待真机验证 / 讨论中
  本轮边界：是否打包、安装、推送或发布
  ```
