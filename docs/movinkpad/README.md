# 开发文档索引

`docs/movinkpad/` 存放本 fork 的开发、发布与验证记录。面向用户的说明见仓库根目录 [README](../../README.md)，长期规则见 [AGENTS.md](../../AGENTS.md)，适配概览见 [MOVINKPAD.md](../../MOVINKPAD.md)。

## 常用

- [项目说明](project-brief.md)：目标、设备事实与对齐范围。
- [早期开发记录](development-history.md)：2026-11-05 之前的开发概况。
- [一次性授权与合并记录](authorization-history.md)：各次打包、推送、合并授权。
- [构建与仓库结构](process/server-build.md)：在 Linux 服务器上构建。

## 记录格式

每篇记录命名为 `主题-YYYY-MM-DD.md`，按主题放进下面的文件夹，开头统一为：

```markdown
# 标题（YYYY-MM-DD）

用户要求：……
状态：……
本轮边界：……
```

原文没有记下的项写「未记录」。打包日志、基准数据和截图等证据分别放在 `build-logs/`、`benchmarks/`、`evidence/`。

## 发布（`releases/`）

正式版与候选版的构建、签名、版本号与验收记录。

- 2026-11-02 [0.9.7 发布](releases/release-2026-11-02.md)
- 2026-11-02 [可覆盖预览版的 Release 候选包](releases/release-candidate-2026-11-02.md)
- 2026-11-07 [主线覆盖更新 APK 96](releases/release96-2026-11-07.md)
- 2026-11-07 [主线覆盖更新 APK 97](releases/release97-2026-11-07.md)
- 2026-11-07 [主线覆盖更新 APK 98](releases/release98-2026-11-07.md)
- 2026-11-08 [Release 98 目标真机验收](releases/device-acceptance-release98-2026-11-08.md)
- 2026-11-22 [主线正式覆盖更新 APK 99](releases/release99-2026-11-22.md)
- 2026-11-23 [0.9.9beta 发布](releases/release-0.9.9beta-2026-11-23.md)
- 2026-11-23 [主线正式覆盖更新 APK 100](releases/release100-2026-11-23.md)
- 2026-11-23 [主线正式覆盖更新 APK 101](releases/release101-2026-11-23.md)

## 测试版（`test-builds/`）

主线与分支独立测试包的打包、安装与实测记录。

- 2026-11-02 [PDF 双窗格测试版](test-builds/split-preview-2026-11-02.md)
- 2026-11-05 [分支独立测试包](test-builds/branch-tests-2026-11-05.md)
- 2026-11-07 [主线独立测试版 test2](test-builds/master-test2-2026-11-07.md)
- 2026-11-08 [主线独立测试版 test3](test-builds/master-test3-2026-11-08.md)
- 2026-11-08 [主线 test3 模拟器冒烟测试](test-builds/master-test3-emulator-smoke-2026-11-08.md)
- 2026-11-08 [主线独立测试版 test4](test-builds/master-test4-2026-11-08.md)
- 2026-11-08 [主线独立测试版 test5](test-builds/master-test5-2026-11-08.md)
- 2026-11-11 [主线独立测试版 test6](test-builds/master-test6-2026-11-11.md)
- 2026-11-11 [主线独立测试版 test7](test-builds/master-test7-2026-11-11.md)
- 2026-11-11 [test7 思维导图模拟器实际流程测试](test-builds/master-test7-map-emulator-2026-11-11.md)
- 2026-11-11 [主线独立测试版 test8](test-builds/master-test8-2026-11-11.md)
- 2026-11-11 [test8 模拟器回归与修复](test-builds/master-test8-map-emulator-2026-11-11.md)
- 2026-11-11 [主线独立测试版 test9](test-builds/master-test9-2026-11-11.md)
- 2026-11-12 [主线独立测试版 test10](test-builds/master-test10-2026-11-12.md)
- 2026-11-20 [主线独立测试版 test11](test-builds/master-test11-2026-11-20.md)
- 2026-11-21 [主线独立测试版 test12 与导图触摸真机检查](test-builds/master-test12-2026-11-21.md)
- 2026-11-22 [导图 v2 test1 打包与真机测试](test-builds/mindmapv2-test1-2026-11-22.md)
- 2026-11-22 [导图 v2 test2 打包与真机测试](test-builds/mindmapv2-test2-2026-11-22.md)
- 2026-11-22 [导图 v2 test3 打包与真机复验](test-builds/mindmapv2-test3-2026-11-22.md)

## 编辑器（`editor/`）

分页笔记、无限画布、图层、页码与工作文件夹。

- 2026-10-31 [夜间模式纯黑笔迹显示修复](editor/night-ink-2026-10-31.md)
- 2026-10-31 [工作文件夹切换与 Pro Pen 3 设置界面](editor/workspace-switch-2026-10-31.md)
- 2026-11-02 [无限画布标题重命名修复](editor/canvas-rename-2026-11-02.md)
- 2026-11-02 [双页码与正文起始页](editor/page-numbering-2026-11-02.md)
- 2026-11-02 [双页码版本启动崩溃修复](editor/startup-fix-2026-11-02.md)
- 2026-11-05 [图层第一版](editor/layers-2026-11-05.md)
- 2026-11-07 [无限画布专供画画与草稿，取消分屏](editor/canvas-sketch-only-2026-11-07.md)

## 手写笔（`pen/`）

Pro Pen 3 按钮位、输入日志分析与按键录入。

- 2026-10-30 [MovinkPad 真机输入日志分析](pen/pen-input-analysis-2026-10-30.md)
- 2026-10-30 [MovinkPad Pen Preview 1](pen/pen-preview-1-2026-10-30.md)
- 2026-10-30 [Pro Pen 3 按钮位映射](pen/pro-pen-3-button-mapping-2026-10-30.md)
- 2026-10-30 [XNotes 第一版：Pro Pen 3 输入与自定义按键](pen/xnotes-first-version-2026-10-30.md)
- 2026-11-05 [通用手写笔按键录入](pen/recordable-pen-shortcuts-2026-11-05.md)

## 思维导图（`mind-map/`）

当前的画布内导图（v2）设计与文件格式。

- 2026-11-21 [思维导图 v2：整体框架与界面设计](mind-map/mind-map-v2-design-2026-11-21.md)
- 2026-11-21 [画布内思维导图 v2 文件格式](mind-map/mind-map-v2-format-2026-11-21.md)

## AI（`ai/`）

AI 对话、供应商、联网、PDF 读取与模板生成。

- 2026-11-03 [AI 设置扩展、窗口位置记忆与主页侧栏修复](ai/ai-settings-2026-11-03.md)
- 2026-11-03 [AI 界面重做、夜间层级、PDF 上的工具颜色与双语](ai/ai-ui-dark-i18n-2026-11-03.md)
- 2026-11-03 [截图问 AI 第一版](ai/ask-ai-2026-11-03.md)
- 2026-11-04 [AI 未开始对话时的清理](ai/ai-empty-chat-2026-11-04.md)
- 2026-11-04 [AI 浮窗：多张截图与文档内历史](ai/ai-floating-screenshots-2026-11-04.md)
- 2026-11-04 [AI 会话回收站](ai/ai-trash-2026-11-04.md)
- 2026-11-04 [AI 联网：原生工具调用](ai/ai-web-tools-2026-11-04.md)
- 2026-11-05 [多模型供应商](ai/ai-providers-2026-11-05.md)
- 2026-11-06 [AI 回答 LaTeX 公式排版](ai/ai-math-2026-11-06.md)
- 2026-11-06 [AI 读取 PDF 改为原生工具调用](ai/ai-pdf-native-tools-2026-11-06.md)
- 2026-11-07 [OpenAI 官方接口返回 HTTP 400](ai/ai-openai-http400-2026-11-07.md)
- 2026-11-07 [新建笔记「用 AI 生成」改为应用内生成](ai/ai-template-generation-2026-11-07.md)
- 2026-11-07 [不支持思考参数的模型自动降级](ai/ai-thinking-fallback-2026-11-07.md)
- 2026-11-22 [AI 原始报错展开与 OpenCode 风格配置文件编辑器](ai/ai-raw-error-config-file-2026-11-22.md)
- 2026-11-22 [顶部 AI 入口、统一截图交互与思考强度兼容](ai/ai-toolbar-screenshot-thinking-2026-11-22.md)

## 存储与恢复（`storage/`）

文档身份、页面引用、保存与恢复流程、PDF 单份存储。

- 2026-11-03 [文档登记与共享资源实施记录](storage/document-registry-2026-11-03.md)
- 2026-11-05 [引用式 PDF 原件缺失的重新关联](storage/pdf-relink-2026-11-05.md)
- 2026-11-05 [PDF 单份存储与便携导出](storage/pdf-single-copy-2026-11-05.md)
- 2026-11-06 [关闭或切换文档时误删私有 PDF 原件](storage/pdf-original-deletion-2026-11-06.md)
- 2026-11-07 [引用锚点字节保真升级基础](storage/reference-anchors-2026-11-07.md)
- 2026-11-07 [页面与对象持久身份模型](storage/reference-identity-model-2026-11-07.md)
- 2026-11-07 [引用改为页面级](storage/reference-page-level-2026-11-07.md)
- 2026-11-08 [页面引用基础实现](storage/page-reference-implementation-2026-11-08.md)
- 2026-11-08 [页面引用整包复制与导出](storage/reference-bundle-copy-2026-11-08.md)
- 2026-11-08 [页面引用登记契约](storage/reference-contract-registry-2026-11-08.md)
- 2026-11-08 [活模型复制身份与会话依赖规格](storage/reference-copy-scope-2026-11-08.md)
- 2026-11-08 [保存后放弃修改的含义](storage/reference-discard-written-2026-11-08.md)
- 2026-11-08 [活模型来源与保存回执](storage/reference-edit-source-2026-11-08.md)
- 2026-11-08 [原生工作会话的固定写入视图](storage/reference-frozen-writer-2026-11-08.md)
- 2026-11-08 [核实并保留已写入的原生版本](storage/reference-keep-written-save-2026-11-08.md)
- 2026-11-08 [原生会话的轻量来源观察与按需正文捕获](storage/reference-lightweight-capture-2026-11-08.md)
- 2026-11-08 [原生新建目标与另存发布](storage/reference-native-creation-2026-11-08.md)
- 2026-11-08 [原生保存到工作会话的持久交接](storage/reference-native-handoff-2026-11-08.md)
- 2026-11-08 [原生保存与配套恢复事务](storage/reference-native-save-2026-11-08.md)
- 2026-11-08 [原生工作会话适配](storage/reference-native-session-adapter-2026-11-08.md)
- 2026-11-08 [原生来源版本凭据](storage/reference-native-source-version-2026-11-08.md)
- 2026-11-08 [实际来源的只读有序页目录](storage/reference-page-catalog-2026-11-08.md)
- 2026-11-08 [页面引用发布与恢复事务](storage/reference-recovery-publication-2026-11-08.md)
- 2026-11-08 [会话正文与身份配对](storage/reference-session-cache-2026-11-08.md)
- 2026-11-08 [原生来源身份只读检查](storage/reference-source-read-2026-11-08.md)
- 2026-11-08 [编辑器同源打开与采用](storage/reference-verified-open-2026-11-08.md)
- 2026-11-08 [已绑定工作副本的来源核实恢复](storage/reference-working-recovery-2026-11-08.md)
- 2026-11-09 [保存后重新核实来源再采用](storage/reference-open-after-save-2026-11-09.md)
- 2026-11-09 [编辑拥有者的稳定保存](storage/reference-owner-capture-2026-11-09.md)
- 2026-11-09 [页面来源入口的实施顺序](storage/reference-page-ui-first-2026-11-09.md)
- 2026-11-09 [恢复资料只读预览与独立副本](storage/reference-recovery-preview-2026-11-09.md)
- 2026-11-09 [分页另存与页启用原字节证明](storage/reference-save-as-page-copy-2026-11-09.md)
- 2026-11-09 [来源保存的稳定内容边界](storage/reference-stable-capture-2026-11-09.md)
- 2026-11-09 [文件副本与页面身份启用](storage/reference-stored-copy-and-page-activation-2026-11-09.md)
- 2026-11-10 [混合分屏进程恢复](storage/mixed-cold-restore-2026-11-10.md)
- 2026-11-10 [原生大笔记存储基准与事务复用](storage/native-storage-benchmark-2026-11-10.md)
- 2026-11-10 [文档登记表版本与回装边界](storage/reference-registry-version-policy-2026-11-10.md)
- 2026-11-22 [工作区文件冷启动与暂停写回](storage/clean-workspace-reopen-2026-11-22.md)
- 2026-11-22 [系统选择器与工作目录的同文件位置](storage/same-file-locations-2026-11-22.md)
- 2026-11-22 [保存步骤简化：只让文件保存编码](storage/save-slim-2026-11-22.md)
- 2026-11-23 [恢复记录只在离开应用时写入](storage/pause-only-recovery-2026-11-23.md)

## 显示与渲染（`rendering/`）

渲染回迁、分屏黑屏、湿墨层与闪烁问题。

- 2026-11-04 [退出 PDF 分屏后的实时笔迹](rendering/split-wet-ink-2026-11-04.md)
- 2026-11-05 [无限画布退出分屏后局部黑屏](rendering/infinite-split-black-2026-11-05.md)
- 2026-11-06 [无限画布退出分屏黑屏：深入核查](rendering/infinite-split-audit-2026-11-06.md)
- 2026-11-07 [正式版无限画布退出左右分屏右侧黑屏](rendering/canvas-split-black-2026-11-07.md)
- 2026-11-07 [第三批渲染回迁：光晕缓存随 GPU 缓冲重建失效](rendering/halo-cache-backport-2026-11-07.md)
- 2026-11-07 [第六批渲染回迁：图片解码结果与来源管理](rendering/image-decode-backport-2026-11-07.md)
- 2026-11-07 [渲染回迁合并后审查与冒烟测试待修](rendering/render-backport-review-followups-2026-11-07.md)
- 2026-11-07 [第一批渲染回迁：诊断与 Surface 捕获](rendering/render-diagnostics-backport-2026-11-07.md)
- 2026-11-07 [渲染诊断回迁合并后审查](rendering/render-diagnostics-review-2026-11-07.md)
- 2026-11-07 [第四批渲染回迁：图片绘制与录音关联快照](rendering/render-snapshot-backport-2026-11-07.md)
- 2026-11-07 [第五批渲染回迁：完整场景批次发布](rendering/scene-batch-backport-2026-11-07.md)
- 2026-11-07 [第二批渲染回迁：稳定工具栏与窗格布局](rendering/stable-pane-layout-backport-2026-11-07.md)
- 2026-11-08 [无限画布侧栏黑块与图层闪烁修复](rendering/canvas-flicker-fix-2026-11-08.md)
- 2026-11-08 [无限画布侧栏黑块与图层闪烁：成因与渲染方案调研](rendering/canvas-flicker-research-2026-11-08.md)
- 2026-11-08 [进入无限画布时的黑屏闪烁](rendering/canvas-open-flash-2026-11-08.md)

## 界面（`ui/`）

黑白层级、字号间距规范、文案与侧栏面板。

- 2026-11-03 [黑白层级与字号、间距规范](ui/ui-monochrome-tokens-2026-11-03.md)
- 2026-11-11 [统一侧栏工具面板](ui/sidebar-tools-panels-2026-11-11.md)
- 2026-11-23 [界面文案审核与修改](ui/ui-copy-review-2026-11-23.md)

## 开发流程（`process/`）

架构审核、分支合并、上游同步与构建环境。

-  [Linux 构建与仓库结构](process/server-build.md)
- 2026-11-02 [GitHub 分支整理与上游同步](process/github-sync-2026-11-02.md)
- 2026-11-04 [远端更新合并](process/remote-merge-2026-11-04.md)
- 2026-11-10 [原生存储与导图协作细节归档](process/agent-contract-details-2026-11-10.md)
- 2026-11-10 [架构与实现审核](process/architecture-audit-2026-11-10.md)
- 2026-11-10 [Release 98 之后主线审核与修复](process/review-fixes-release98-2026-11-10.md)
- 2026-11-11 [原生恢复职责拆分](process/architecture-refactor-2026-11-11.md)
- 2026-11-20 [上游合并与 MovinkPad 适配](process/upstream-merge-2026-11-20.md)
- 2026-11-23 [iPadOS 移植方案](process/ipados-migration-plan-2026-11-23.md)

## 已归档（`archive/`）

已从应用中删除的功能（旧版 .xmap 导图、文件关系网络、原 PDF 原件库等）的开发记录，仅作历史保留。

-  [思维导图与文件关系网络：持续需求](archive/mind-map-requirements-current.md)
- 2026-11-06 [思维导图与文件关系网络](archive/graph-network-2026-11-06.md)
- 2026-11-06 [原生思维导图第一版](archive/mind-map-2026-11-06.md)
- 2026-11-07 [思维导图工作暂停与渲染重构交接](archive/mind-map-rendering-handoff-2026-11-07.md)
- 2026-11-07 [思维导图分支代码审查与修复](archive/mind-map-review-fixes-2026-11-07.md)
- 2026-11-08 [codex/mind-map 分支复核问题](archive/mind-map-branch-review-2026-11-08.md)
- 2026-11-08 [画布点击连线与目标预览](archive/mind-map-canvas-connect-2026-11-08.md)
- 2026-11-08 [导图折叠分支数量提示](archive/mind-map-collapse-count-2026-11-08.md)
- 2026-11-08 [空白落点新建节点并连接](archive/mind-map-connected-node-2026-11-08.md)
- 2026-11-08 [导图节点边缘拖动连线入口](archive/mind-map-connection-port-2026-11-08.md)
- 2026-11-08 [普通关系线端点与事务编辑](archive/mind-map-relation-editing-2026-11-08.md)
- 2026-11-08 [导图适配渲染基线与页面引用续作](archive/mind-map-rendering-resume-2026-11-08.md)
- 2026-11-08 [页面来源闭环的生产接线核查](archive/source-loop-production-gaps-2026-11-08.md)
- 2026-11-09 [当前文件的局部关系网络](archive/file-local-network-2026-11-09.md)
- 2026-11-09 [网络逐层展开与折叠](archive/file-network-expansion-2026-11-09.md)
- 2026-11-09 [文件关系网络筛选](archive/file-network-filters-2026-11-09.md)
- 2026-11-09 [全库与多文件关系网络](archive/file-network-overview-2026-11-09.md)
- 2026-11-09 [来源侧栏的反向引用](archive/mind-map-backlinks-2026-11-09.md)
- 2026-11-09 [从当前页添加导图来源](archive/mind-map-current-page-reference-2026-11-09.md)
- 2026-11-09 [来源往返的主窗口导图宿主](archive/mind-map-main-host-2026-11-09.md)
- 2026-11-09 [导图节点多选与对齐](archive/mind-map-node-selection-2026-11-09.md)
- 2026-11-09 [页面来源选择与新建／分叉保护](archive/mind-map-page-source-flow-2026-11-09.md)
- 2026-11-09 [普通关系线的画布选择](archive/mind-map-relation-hit-2026-11-09.md)
- 2026-11-09 [来源打开、定位与返回导图](archive/mind-map-source-navigation-2026-11-09.md)
- 2026-11-09 [来源预览与刷新](archive/mind-map-source-preview-2026-11-09.md)
- 2026-11-10 [Canvas 固定区域来源完整流程](archive/canvas-region-sources-2026-11-10.md)
- 2026-11-10 [Canvas 整文件来源打开与返回](archive/canvas-source-navigation-2026-11-10.md)
- 2026-11-10 [显式文件关联的网络探索与画布编辑](archive/file-relation-exploration-2026-11-10.md)
- 2026-11-10 [导图与分页文档双窗格](archive/map-native-split-2026-11-10.md)
- 2026-11-10 [导图与分页文档：双文件打开](archive/map-pair-open-2026-11-10.md)
- 2026-11-10 [导图文件来源打开与返回](archive/map-source-navigation-2026-11-10.md)
- 2026-11-10 [导图恢复进展与接续计划合并](archive/mind-map-cold-progress-merge-2026-11-10.md)
- 2026-11-10 [手绘框尺寸与显式布局边界](archive/mind-map-ink-frame-layout-2026-11-10.md)
- 2026-11-10 [思维导图两轮审核修复与本地合并](archive/mind-map-merge-fixes-2026-11-10.md)
- 2026-11-10 [思维导图剩余工作与接续计划](archive/mind-map-next-plan-2026-11-10.md)
- 2026-11-10 [已提交导图进展合入主线](archive/mind-map-progress-merge-2026-11-10.md)
- 2026-11-10 [导图视口裁剪与绘制缓存实测](archive/mind-map-render-review-fixes-2026-11-10.md)
- 2026-11-10 [导图与笔记混合分屏的持久化](archive/mixed-split-persistence-2026-11-10.md)
- 2026-11-10 [原生分屏替换工作区](archive/native-split-replacement-2026-11-10.md)
- 2026-11-10 [从文件网络加入关系画布](archive/network-to-relation-canvas-2026-11-10.md)
- 2026-11-10 [原 PDF 在文件关系网络中的身份与状态](archive/original-pdf-network-2026-11-10.md)
- 2026-11-10 [原 PDF 独立原文来源](archive/original-pdf-sources-2026-11-10.md)
- 2026-11-10 [分页固定区域来源创建与往返](archive/paged-region-sources-2026-11-10.md)
- 2026-11-10 [来源范围调整与更换](archive/source-target-edit-2026-11-10.md)
- 2026-11-11 [思维导图停靠面板与交互改进](archive/mind-map-dock-interaction-2026-11-11.md)
- 2026-11-11 [导图菜单与创建时绑定来源](archive/mind-map-menu-creation-2026-11-11.md)
- 2026-11-11 [思维导图剩余交付计划](archive/mind-map-next-plan-2026-11-11.md)
- 2026-11-11 [导图节点轻量选中态](archive/mind-map-selection-outline-2026-11-11.md)
- 2026-11-11 [思维导图可用性修复计划](archive/mind-map-usability-repair-2026-11-11.md)
- 2026-11-11 [导图与全局入口的文件身份修复](archive/presentation-file-identity-fix-2026-11-11.md)
- 2026-11-21 [思维导图触摸导航](archive/mind-map-touch-navigation-2026-11-21.md)
