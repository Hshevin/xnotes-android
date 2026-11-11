# test8 模拟器回归与修复（2026-11-11）

用户要求：拉取远端打包记录，用提供的 test8 APK 在模拟器验证新建/打开手写节点及新增同级节点闪退；原 PDF 原件库改成和首页、AI 一样的下沉面板，不进入新的全屏窗口。
状态：test8 实测复现三条手写崩溃路径；源码绘制修复已通过先失败后通过的 JVM 回归，原件库面板源码已集成，分屏保存仍在定位；修复后 Android 与真机待验。
本轮边界：拉取远端记录，安装用户提供的 APK 到模拟器；不本地打包新 APK、不安装真机、不推送、不发布。Claude Opus 5.5 独立工作树实施，主代理验收、测试、合并与本地提交。

## 包与环境

- 远端记录提交 `6919bf01` 已快进拉取；[test8 打包记录](master-test8-2026-11-11.md)的源代码为 `6eb71cf9`。
- 提供 APK 的 SHA-256 为 `dfdca6671cb41c4f7ed6c7adef1b97cd03596292b9c7d2ba28b1af248a9ce67f`，与远端记录一致。
- 包名 `com.xnotes.movinkpad.branchtest.master`，版本 code 8；直接覆盖安装成功。使用 Medium_Phone / ARM64 / API 32 / Android 12，1800 × 1125，density 160。不是 MovinkPad / Android 15。

## 原 APK 实测

| 路径 | 实测结果 |
| --- | --- |
| 新建导图 | 主画布节点正常可见，工具栏与画布已启用 |
| 新增文字子节点，完成 | 进入真实输入框，确认后返回图；顶部完成/取消可达 |
| 文字子节点新增同级 | 正常进入文字输入，未复现崩溃 |
| 新增手写子节点 | 立即闪退 |
| 将文字子节点在属性中改为手写，选择该节点 | 原位工具栏可见，应用未崩溃 |
| 该手写节点新增同级 | 闪退 |
| 重启后从节点列表选既有手写节点，再点放大编辑 | 闪退 |
| Map → 旁边首页新建空白 NOTE → 上下排列 → 保存当前分屏 | 仍失败，未生成 splits 目录；日志停在 source saves or proofs |
| 首页原 PDF 原件库 | 仍替换为全屏界面，侧栏消失；空库提示可见 |

三个崩溃日志均为 `IllegalStateException: Underflow in restore - more restores than saves`，栈顶在 `InkPadView.onDraw:52`。新建同级沿用所选节点类型，因此手写同级与文字同级的结果不同。证据见本目录的 `evidence/test8-map-2026-11-11/`。

新图截图、原位工具栏截图与原件库旧全屏截图分别为 `new-map.png`、`ink-selected.png`、`original-pdf-fullscreen.png`。原始 XML、完整 logcat 与额外截图留在已忽略的 `tmp/test8-map-qa/`。

## 绘制修复与测试

上一轮 `36afb476` 把 save 移到 View 裁切前，却留下两次 restore；一帧只保存一次，最终清空宿主绘制状态或下溢。这是上一轮修复引入的回归，不能用此前编译通过解释为已验收。

Claude 修复 `463bcc30` 捕获 save 的返回层数，末尾只 restoreToCount 一次；保留 View 裁切和节点变换。主代理审查并合并。

新增 `InkPadDrawingRegressionTest`（Robolectric SDK 35 / native graphics）在旧码两项均失败：裸 Canvas 直接下溢；带宿主 save 的 Canvas 被错误消耗一层。合并后两项全部通过，额外断言重复帧保持 clip、宿主保存层及工具栏/邻接区域像素不变。

Android `MapVisibleEditingRegressionTest` 补充既有手写节点打开、手写同级进入真实绘制器和宿主 Canvas 层数保护。Android 用例仅编译，不冒称执行。

## 原件库面板

Claude `c2b4ed6d` 将原件库纳入 Backstage 的 ORIGINALS 视图，在侧栏右侧呈现，保留当前首页导航和选中态；切换 AI/首页或返回时关闭。原件阅读器仍为只读阅读界面，返回原件库时回到该面板。文件选择器宿主保留在首页之外，避免阅读器替换首页丢失请求。来源身份、恢复请求和文件操作保护不变。

主代理将新增尺寸整理到主题 tokens，并补真实 Main 的原件库与 AI 切换、返回键、宿主保留测试。小屏折叠导航、阅读器返回、选择器重建、分屏侧栏以及 Surface/GPU 共存仍须 Android 执行与真机验收。

## 分屏保存的新证据

test8 仍报无法保存，`xnotes.split` 明确停在 `source saves or proofs`，尚未到 stage 或发布。上轮硬链接回退不足以解决此实际路径；不能继续把它当作本案例已确认根因。当前保留两侧文档，继续定位来源保存和核验拒绝点。

## 本阶段源码验证

- 集成后的 `mindmap.InkPadDrawingRegressionTest` 2 项通过；核对 XML 后修正另外两类测试的包名过滤器，单独运行 `pen.OriginalPdfLibraryHostTest` 2 项与 `pen.ColdMainPresentationHostTest` 8 项，均通过。合计 12 项，无失败/错误/跳过。
- 主程序及 Android 测试源码编译通过，包含新增手写打开/同级节点和原件库面板切换用例。未生成新 APK，因此这些新增 Android 用例未执行，原件库新布局与绘制修复的实际画面尚未验收。
- 模拟器显示尺寸和 density 已恢复默认并关闭；原 test8 测试数据保留。
