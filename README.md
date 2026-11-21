# XNotes for MovinkPad

面向 **Wacom MovinkPad Pro 14（Android 15）和 Pro Pen 3** 的手写笔记应用，基于 XNotes 开发。笔记保存在设备上，无需账号或云同步。

## 下载与安装

从 [GitHub Releases](https://github.com/Hshevin/xnotes-android/releases) 下载本 fork 的 **ARM64 APK**，在平板上打开安装。已安装本 fork 正式版的用户可用同签名的新版本直接覆盖升级。分支测试包使用独立包名，可与正式版并存，各自保存数据；旧 AI 和双窗格专用测试包不再纳入本地打包。当前分支测试包见[本地交付与验证记录](docs/movinkpad/branch-tests-2026-11-05.md)。

最新本地覆盖更新：[0.9.8 / Release 98 ARM64 APK](build/xnotes/XNotes-0.9.8-movinkpad-release98-arm64.apk)，沿用正式包名与签名，包含 AI 模板文字 / 图像模型自选，以及无限画布退出分屏黑屏修复；三键、黑白界面、图层、分屏湿墨与黑屏、单份 PDF 重新关联已在 MovinkPad Pro 14 / Android 15 / Pro Pen 3 真机通过，见 [真机验收](docs/movinkpad/device-acceptance-release98-2026-11-08.md)。见 [打包与验证记录](docs/movinkpad/release98-2026-11-07.md)。APK 仅本地交付，日志推送远端，不发布 GitHub Release。

最新主线独立测试版：[master test12 ARM64 APK](build/branch-tests/master-test12/XNotes-master-33386908-test12-arm64.apk)，包名 `com.xnotes.movinkpad.branchtest.master`，与正式版并存，会升级已有 master 测试版。在 test11 上游合并基础上修复导图双指缩放与平移；4,988 项 JVM 测试通过，已在 MovinkPad 覆盖安装并以注入手势检查导图触摸，笔拖动与掌触待实机确认。见 [打包记录](docs/movinkpad/master-test12-2026-11-21.md)。

**test8 实测警示（2026-11-11）：** 主画布与文字输入已可用，但新建/打开放大手写节点和手写同级节点会闪退；混合分屏保存仍失败。绘制崩溃与新建空白笔记检查点导致的分屏保存失败已有源码修复，侧栏工具面板改造已集成，尚未打包或运行修复后 APK。见[实测与修复记录](docs/movinkpad/master-test8-map-emulator-2026-11-11.md)。

**test7 实际使用警示（2026-11-11）：** API 32 ARM64 模拟器走查发现思维导图主画布持续空白、放大手写操作栏不可见及混合分屏保存失败；局部内容保存可用，但导图可视编辑闭环未通过。另有软键盘遮住确认按钮、上下分屏画图区过小的问题。见[复现步骤与截图](docs/movinkpad/master-test7-map-emulator-2026-11-11.md)。源码候选修复已覆盖画布生命周期、复用笔记手写工具栏、键盘避让与分屏文件发布，见[修复与验证记录](docs/movinkpad/mind-map-usability-repair-2026-11-11.md)。未打包新 APK，原 test7 结论不变，修复后 Android 运行与目标真机待验。

分支测试更新：图层与按键录入已更新至 test2，可覆盖各自 test1；新增图层侧栏/缩略图及按键交接、取消、工具栏高亮修复，见[打包记录](docs/movinkpad/branch-tests-2026-11-05.md#test2远端分支更新)。

**回装提醒**：当前主线构建会把文档登记表升级到 v3，安装后不能直接回装 Release 98 及更早版本。回装前须先用应用导出完整备份，再清除应用数据；试用主线新功能建议使用独立分支测试包名。详见[版本与回装边界](docs/movinkpad/reference-registry-version-policy-2026-11-10.md)。

## 主要功能

- **手写笔记**：使用分页笔记或无限画布，混排手写、文字和图片。
- **PDF 批注**：导入 PDF，在页面上手写或标注文字，保留原始 PDF。
- **双窗格**：并排打开笔记或 PDF，也可同时查看同一份分页文档的两个位置。无限画布专供画画与草稿，只能单独打开，不进入分屏（[说明](docs/movinkpad/canvas-sketch-only-2026-11-07.md)）。
- **手写笔快捷键**：在「设置 → 输入 → 手写笔按键映射」先录入实体按键，再命名并分配工具、撤销或重做。工具支持按住临时使用或按一下切换；临时使用保持工具栏原有选中状态。Release 92 起可为每个录入按键另设双击动作，并调整双击间隔，也可绑定切换上一个工具、橡皮擦或平移。旧 Pro Pen 3 配置不迁移，需重新录入。Release 90 及以前仍使用原三键设置。
- **黑白界面**：支持浅色和夜间模式，以灰阶区分界面层级。
- **可选 AI**：在设置中添加一个或多个模型供应商（各自的地址、API 密钥和模型），在输入栏按供应商切换模型，从主菜单打开聊天，或在文档截图后选择「AI」。主动发送时才会将当前对话及所选截图或 PDF 内容发送给配置的供应商；联网搜索默认关闭。

本次上游合并的源码支持笔迹防抖、速度笔平滑度与最高速度调节、屏幕取色，以及分页笔记的自动滚动。分页截图完成后可用笔或手指拖动边框或内部，调整后的单页区域可直接引用到导图。导出新 PDF 时支持矢量笔迹。以上功能待 MovinkPad 真机验收。[合并记录](docs/movinkpad/upstream-merge-2026-11-20.md)

## PDF 保存与备份

当前源码将 PDF 原件统一保存在应用私有目录，日常笔记只保存引用和批注，相同原件共享一份；旧笔记首次打开时迁移。通过应用分享或导出 `.xnote` 时会重新嵌入 PDF，得到可独立打开的完整笔记包。

**备份或换设备请使用应用的分享／导出功能，不要仅复制日常 `.xnote` 文件。** 清除应用数据或卸载会删除私有 PDF 原件，即使外部文件夹仍有引用式笔记，也不能据此恢复原件。迁移失败时保留原件或恢复资料，并提示处理。

此存储调整随 Release 91 本地交付，Release 98 已在目标真机通过单份 PDF 与重新关联验收；真实 SAF 提供商迁移仍待确认。详见 [单份 PDF 存储与迁移记录](docs/movinkpad/pdf-single-copy-2026-11-05.md)。

## 图层功能（Release 91）

`drawing-layers` 已合入本地 `master`，新增分页笔记、PDF 批注和无限画布的常驻图层侧栏：缩略图支持不显示、已有笔记区域、整页笔记（无限画布仅前两种）、直接拖动缩略图或名称排序（无需长按）、双击名称重命名、新增、命名、显隐、锁定、删除及移动选中内容，支持撤销恢复。旧笔记归入默认层；PDF 原文保持独立。此功能随 Release 91 本地交付，Release 98 已通过真机显示与性能验收，见[图层开发与验证记录](docs/movinkpad/layers-2026-11-05.md)。

## 更多资料

- [版本更新与验证记录](docs/movinkpad/release-2026-11-02.md) · [早期开发记录](docs/movinkpad/development-history.md)
- [源码构建与测试](docs/movinkpad/server-build.md)（JDK 21） · [MovinkPad 适配与验证范围](MOVINKPAD.md)
- [渲染诊断与 Surface 捕获回迁](docs/movinkpad/render-diagnostics-backport-2026-11-07.md)（保留主线 View／湿墨层替换；仅增加诊断能力，不代表黑屏已解决；后续验证见第二批记录）
- [稳定工具栏与窗格布局回迁](docs/movinkpad/stable-pane-layout-backport-2026-11-07.md)（减少工具栏变化导致的不必要重挂；主线真实重挂保护保留，验证进度见记录）
- [光晕缓存重建失效回迁](docs/movinkpad/halo-cache-backport-2026-11-07.md)（context／缓冲重建后撤销旧缓存；验证进度与 GPU 验收边界见记录）
- [图片绘制与录音关联快照回迁](docs/movinkpad/render-snapshot-backport-2026-11-07.md)（GL 使用入队快照，保留主线 View／湿墨层替换；验证范围见记录）
- [完整场景批次发布回迁](docs/movinkpad/scene-batch-backport-2026-11-07.md)（失败批次不发布部分更新，SVG 在提交后调度；编译／JVM 已验证，窗口与目标 GPU 待验）
- [图片解码结果与来源管理回迁](docs/movinkpad/image-decode-backport-2026-11-07.md)（失败请求结清、旧结果撤权，同文档重挂保留有效解码；编译／JVM 已验证，GPU 与窗口待验）
- [无限画布退出分屏黑屏：排查与只读取证](docs/movinkpad/infinite-split-audit-2026-11-06.md)（根因未确认，尚无真机验证的修复）
- [MovinkPad 正式版左右分屏黑区候选修复](docs/movinkpad/canvas-split-black-2026-11-07.md)（黑区从画布旧宽度边界开始；已实现跨宿主替换绘制表面，Release 98 真机已无黑区）
- [无限画布侧栏黑块与图层闪烁修复](docs/movinkpad/canvas-flicker-fix-2026-11-08.md)（图层侧栏改为盖在画布上，图层显隐与排序增量更新；已纳入主线测试包，效果待真机验证）· [调研记录](docs/movinkpad/canvas-flicker-research-2026-11-08.md)
- [Release 98 之后主线审核与修复](docs/movinkpad/review-fixes-release98-2026-11-10.md)（Editor 常驻收集器泄漏、分屏另一侧首页不刷新、记住的视图在不同宽度窗格重新按宽适配；已纳入主线测试包，真机待复测）

本项目源自 [XNotes Android](https://github.com/shardulvs/xnotes-android)，沿用 [MIT 许可证](LICENSE)。

通用按键录入的实现、源码参考与验证边界见 [开发记录](docs/movinkpad/recordable-pen-shortcuts-2026-11-05.md)。

## 思维导图（画布导图 v2，开发分支源码）

导图是无限画布里的功能：在画布工具栏选「导图」，点空白处新建中心主题并直接输入文字。同一画布可放多棵树，与普通笔迹、图片共存；没有单独的导图文件。

- **编辑**：选中节点后，右侧「＋」新建子主题、下方「＋」新建同级（随布局方向调整）；外接键盘 Tab／Enter 同样可用。拖动节点到另一节点上可改父节点，拖到空白处可微调位置。节点菜单提供编辑文字、折叠、布局（向右、左右平衡、组织结构、缩进）、样式（形状、边框／填充／文字颜色、字号）、删除，以及复制／剪切、恢复自动位置、拆为独立主题。
- **手写**：在节点内用笔书写即写入该节点；橡皮只擦节点内笔迹，不删节点。
- **关系线**：用连接线工具从一个节点拖到另一个节点，可设箭头、说明文字和颜色。
- **撤销**：每次编辑一步撤销，与画布共用；选择、视口、折叠不进撤销。
- **来源**：在已保存笔记的页面菜单中点「复制页面链接」，再到导图节点的「来源」面板粘贴；在画布空白处长按选「复制画布链接」可把整张画布作为来源。点来源可打开对应页面，点「返回导图」回到离开时的视图和节点。页码随排序更新，删除目标页后，来源会显示「该页已删除」。
- **页编号**：新笔记从第一次保存起就带永久页编号。已有笔记首次复制页面链接时会询问是否启用，普通打开保留原文件。启用后，早于页编号功能的应用版本无法打开该笔记；启用中断时，重新打开笔记选「继续启用页面链接」完成。

**旧版导图已删除。** 独立 `.xmap` 导图、导图＋笔记混合分屏（`.xsplit` v3）、文件关系网络、原 PDF 原件库与反向引用不再提供。磁盘上的旧文件不会被删除或改写，首页照常列出，可重命名、移动、删除、分享，打开时提示不再支持。笔记＋笔记分屏（`.xsplit` v1／v2）照常使用。

设计与决定见[画布导图 v2 设计](docs/movinkpad/mind-map-v2-design-2026-11-21.md)。以上功能均待 MovinkPad 真机验收。

## 保存、恢复资料与另存

- 笔记与画布保存时校验页面身份与文件版本，失败保留资料与未保存状态；已写入的版本不会自动回滚，「放弃修改」只放弃之后的新修改。打开文件先完整读取和验证再替换工作区；保存后重新打开会读取刚写入的版本。
- 分页笔记的首次保存／另存按独立文档发布：已启用引用的副本取得新的页编号，原引用仍指向原文件；保存期间新增的内容保留为未保存修改。PDF 沿用共享原件并保留原始字节。
- 首页导航的「恢复资料」可逐页预览窗格保留的笔记／画布资料并保存独立副本，原资料和当前文档保持不变；写后失败可「继续此副本」，重启后可「继续未完成的副本…」明确选择原目标。
- 本地源码恢复的旧分页／画布工作副本可继续编辑；保存时先另存独立文件，避免仅凭记忆路径覆盖原件。取消或失败保留恢复内容；关闭同一文档的另一视图会保留实际文档。画布另存后可继续编辑和普通保存；关闭受保护副本时须明确另存或放弃。此保护与自动布局恢复已纳入 test6，待设备验证。
- 本地源码在插入菜单提供「录音恢复…」：可明确恢复、作为独立录音加入、导出原始音频或保留以后处理。原始音频保留至文档实际保存并包含它；已加入但未确认保存的录音不会在重启后擅自重放。

### 旧版导图历史记录

以下记录保留旧版导图各开发阶段的实现细节与验证边界，功能已删除，仅作历史证据：

- [版本与回装边界](docs/movinkpad/reference-registry-version-policy-2026-11-10.md)
- [主窗口宿主阶段](docs/movinkpad/mind-map-main-host-2026-11-09.md)
- [来源往返阶段](docs/movinkpad/mind-map-source-navigation-2026-11-09.md)
- [思维导图开发记录](docs/movinkpad/mind-map-2026-11-06.md)
- [决定记录](docs/movinkpad/reference-page-level-2026-11-07.md)
- [当前页引用阶段记录](docs/movinkpad/mind-map-current-page-reference-2026-11-09.md)
- [Canvas 来源阶段记录](docs/movinkpad/canvas-source-navigation-2026-11-10.md)
- [Canvas 区域来源记录](docs/movinkpad/canvas-region-sources-2026-11-10.md)
- [分页区域来源记录](docs/movinkpad/paged-region-sources-2026-11-10.md)
- [独立原文来源记录](docs/movinkpad/original-pdf-sources-2026-11-10.md)
- [原文网络整合记录](docs/movinkpad/original-pdf-network-2026-11-10.md)
- [反向引用阶段记录](docs/movinkpad/mind-map-backlinks-2026-11-09.md)
- [来源范围调整与更换记录](docs/movinkpad/source-target-edit-2026-11-10.md)
- [导图文件来源打开与返回](docs/movinkpad/map-source-navigation-2026-11-10.md)
- [导图与分页文档双窗格阶段记录](docs/movinkpad/map-native-split-2026-11-10.md)
- [双文件打开与验证](docs/movinkpad/map-pair-open-2026-11-10.md)
- [原生分屏替换与验证](docs/movinkpad/native-split-replacement-2026-11-10.md)
- [混合分屏持久化阶段](docs/movinkpad/mixed-split-persistence-2026-11-10.md)
- [冷恢复进展与本地合并](docs/movinkpad/mind-map-cold-progress-merge-2026-11-10.md)
- [剩余工作与接续计划](docs/movinkpad/mind-map-next-plan-2026-11-11.md)
- [扩展规格与阶段进度](docs/movinkpad/graph-network-2026-11-06.md)
- [来源预览阶段记录](docs/movinkpad/mind-map-source-preview-2026-11-09.md)
- [全库与多文件网络记录](docs/movinkpad/file-network-overview-2026-11-09.md)
- [展开与折叠记录](docs/movinkpad/file-network-expansion-2026-11-09.md)
- [加入关系画布与验证记录](docs/movinkpad/network-to-relation-canvas-2026-11-10.md)
- [显式关联探索与验证](docs/movinkpad/file-relation-exploration-2026-11-10.md)
- [接口检查与续作](docs/movinkpad/mind-map-rendering-resume-2026-11-08.md)
- [实现边界](docs/movinkpad/page-reference-implementation-2026-11-08.md)
- [整包复制记录](docs/movinkpad/reference-bundle-copy-2026-11-08.md)
- [登记契约与验证](docs/movinkpad/reference-contract-registry-2026-11-08.md)
- [恢复事务与验证](docs/movinkpad/reference-recovery-publication-2026-11-08.md)
- [来源读取与验证](docs/movinkpad/reference-source-read-2026-11-08.md)
- [保存与恢复阶段](docs/movinkpad/reference-native-save-2026-11-08.md)
- [复制上下文与验证](docs/movinkpad/reference-copy-scope-2026-11-08.md)
- [会话事务与验证](docs/movinkpad/reference-session-cache-2026-11-08.md)
- [原生会话适配](docs/movinkpad/reference-native-session-adapter-2026-11-08.md)
- [恢复合同与验证](docs/movinkpad/reference-working-recovery-2026-11-08.md)
- [交接合同与验证](docs/movinkpad/reference-native-handoff-2026-11-08.md)
- [来源版本与验证](docs/movinkpad/reference-native-source-version-2026-11-08.md)
- [页目录合同与验证](docs/movinkpad/reference-page-catalog-2026-11-08.md)
- [新建发布与验证](docs/movinkpad/reference-native-creation-2026-11-08.md)
- [同源打开与采用](docs/movinkpad/reference-verified-open-2026-11-08.md)
- [保存后重新打开的验证](docs/movinkpad/reference-open-after-save-2026-11-09.md)
- [保存回执合同与验证](docs/movinkpad/reference-edit-source-2026-11-08.md)
- [核实与验证](docs/movinkpad/reference-keep-written-save-2026-11-08.md)
- [关系编辑与验证](docs/movinkpad/mind-map-relation-editing-2026-11-08.md)
- [画布连线与验证](docs/movinkpad/mind-map-canvas-connect-2026-11-08.md)
- [画布选线与验证](docs/movinkpad/mind-map-relation-hit-2026-11-09.md)
- [节点多选与验证](docs/movinkpad/mind-map-node-selection-2026-11-09.md)
- [恢复预览阶段记录](docs/movinkpad/reference-recovery-preview-2026-11-09.md)
- [另存与页启用验证](docs/movinkpad/reference-save-as-page-copy-2026-11-09.md)
- [前阶段实现与边界](docs/movinkpad/reference-stored-copy-and-page-activation-2026-11-09.md)
- [页面来源流程与验证](docs/movinkpad/mind-map-page-source-flow-2026-11-09.md)
- [输入稳定与清理](docs/movinkpad/reference-stable-capture-2026-11-09.md)
- [编辑拥有者的稳定保存](docs/movinkpad/reference-owner-capture-2026-11-09.md)
