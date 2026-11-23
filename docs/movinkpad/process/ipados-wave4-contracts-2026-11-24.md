# iPadOS 第四波契约（2026-11-24）

用户要求：M2 之后继续下一轮，先做选择与套索、文字编辑、图层与页面管理；只做本地提交，不推送、不安装、不合并到 `master`；遇到新的设计分歧停下等用户决定。见 [iPadOS 移植方案](ipados-migration-plan-2026-11-23.md)「下一轮决定」。
状态：契约与 RED 测试已写，待实现
本轮边界：只做本地提交；不推送、不打包、不安装到真机、不发布

## 本波取舍（主代理决定，可在用户醒后调整）

- **本波不改 Android。** 用户夜间不在，不能做 MovinkPad 真机回归，所以 Android 的选择、文字与页面代码一律不动。iPad 用共享模块里已有的纯逻辑（`SelectionMath`、`ResizeMath`、`FlowEditor`、`FlowLayout`、`DrawingLayers`、`core/history` 命令）加上本波新增的共享小部件实现。这符合折中方案「复杂交互按需抽取」。本波新增的三个共享部件只给 iPad 用；以后 Android 要不要改用它们，再按 W3-B 的方式单独做，并做真机回归。
- **新建笔记**：本波只做空白分页笔记（A4 纵向、默认页数）与空白画布，从首页新建；模板选择以后再做。
- **页边可见**：W3-D 截图里分页笔记的页面与背景都是白色，看不出页界。本波给分页视图加中性灰背景与页间距（夜间为深灰），属于 W4-C。

## 分工（三路并行）

三者都会改 `:iosKit` 的编辑器文件（工具栏、`InkEditor`、`PagedView`、`CanvasView`）。各自的新逻辑放在新文件里，对共用文件只加最小的接入点，冲突由主代理合并时解决。

## W4-A 选择与套索

- **共享部件** `com.xnotes.edit.SelectionEdit`（`shared/src/commonMain`）：`SelectionEdit(items)` 在构造时排除锁定条目并给其余条目拍几何快照；`items`、`active`；`preview(t: Affine)` 每次都从初始几何出发应用变换，不累加；`cancel()` 原样恢复；`commit(): Command?` 无变化返回 `null`，否则返回一个 `TransformItems` 命令（条目已处于最终几何），之后编辑关闭，迟到的 `preview` / `cancel` 无效。测试：`shared/src/commonTest/kotlin/com/xnotes/edit/SelectionEditTest.kt`。
- **iPad 交互**：工具栏加「选择」（框选）和「套索」。笔或手指（按「手指绘画」规则）在选择工具下拖出框选或套索，成员用 `SelectionMath.bandMembers` / `lassoMembers`，画布用 `CanvasSelection` 对应函数；画布框选、套索不选中导图节点。点按条目单选；在选区框内按下即移动。手柄缩放用 `ResizeMath`，旋转手柄用 `Affine.rotateAbout`；预览都经 `SelectionEdit`，抬起提交为一步，第二指接管、切换工具、离开文档、点空白清选区都先 `cancel()`。选区菜单：删除、复制、粘贴、复制一份（偏移 24 px，同 Android `DUPLICATE_NUDGE`）。删除、粘贴各一步撤销。
- **标记颜色**：选框、套索、手柄按 AGENTS.md「页面上的工具标记」规则取对比色；画布用 `OverlayTessellator`。
- **模拟器验收**：分页笔记和画布上各框选一次、套索一次，移动、缩放、旋转、删除、复制一份、撤销重做各一次，截图并说明。

## W4-B 文字编辑

- **共享部件** `com.xnotes.edit.FlowTextInput`（`shared/src/commonMain`）：`FlowTextInput(flow, history)`；`selection: FlowRange`；`marked: FlowRange?`；`insertText(text)`（替换选区或正在组字的内容，`\n` 分段，每次一步撤销）；`setMarkedText(text, selectedInMarked: IntRange)`（组字，直接显示在正文里但不进撤销历史；`selectedInMarked.first` 为组字内选区起点，`last` 为终点位置，`n..n` 表示光标在第 n 个单位后）；`unmarkText()`（把组字结果定为一步撤销；组字为空则不留痕迹，选区回到组字前位置）；`deleteBackward()`（删除选区，或光标前一个完整字符——代理对整体删除；段首与上一段合并；文首无操作）；文档偏移按 UTF-16 计数、段落之间算 1 个单位：`utf16Length()`、`positionAt(offset)`、`offsetOf(pos)`、`textIn(start, end)`。底层只用 `FlowEditor` 的命令。测试：`shared/src/commonTest/kotlin/com/xnotes/edit/FlowTextInputTest.kt`。
- **iPad 交互**：工具栏加「文字」。分页笔记里点按流式正文放置光标、拖动选择；画布里点空白处新建文本框（`TextItem`），点已有文本框进入编辑。键盘经 `UITextInput` 接入 `FlowTextInput`，支持中文拼音的组字与候选、硬件键盘、复制粘贴、全选。光标、选区与组字下划线按工具标记对比规则绘制。加粗、斜体、列表三个格式按钮用 `FlowEditor.setCharStyle` / `setParaStyle`。
- **模拟器验收**：在分页笔记正文里用模拟器键盘输入英文与中文（拼音组字），换行、删除、撤销重做、加粗；在画布新建并编辑一个文本框；返回首页后重开确认保存。截图并说明。

## W4-C 图层与页面管理

- **共享部件** `com.xnotes.edit.PageOps`（`shared/src/commonMain`）：`addAfter(doc, index)`（新页与该页同尺寸，取得新的永久页编号）、`delete(doc, index)`（最后一页拒绝）、`move(doc, from, to)`、`duplicate(doc, index, measurer)`（深拷贝，取得新页编号）。每个函数先执行操作，再返回一条可放进 `History` 的命令；越界参数抛出异常且不改文档；操作后 `doc.dirty = true`。底层用现有的 `AddPage`、`DeletePage`、`MovePages` 与 `Page.deepCopy`。测试：`shared/src/commonTest/kotlin/com/xnotes/edit/PageOpsTest.kt`。
- **页面管理界面**：分页笔记加页面面板（缩略图列表）：在后面加页、复制、删除（确认）、拖动排序。每个操作一步撤销。
- **图层侧栏**：按 AGENTS.md「图层」：绘画软件式常驻侧栏，分页按页、画布按文档；新建、删除、重命名、显隐、锁定、拖动排序、设为当前层。用现有 `DrawingLayers` 与 `EditLayers` 命令；选择当前层不进撤销。缩略图只画单层内容，异步渲染，翻页、关闭或版本变化时取消旧任务。新笔迹写入当前层；锁定层不可写（`PagedStrokeCapture` 的 `writable` 已据此判断）。画布侧栏叠在画布上，不改变画布尺寸。
- **新建与页边**：首页加「新建笔记」「新建画布」（空白文档；在 `LiteStore` 增加 `create(directory, baseName, kind): LiteOpen`，只发布到确认不存在的新路径，暂存、核实后原子发布，新文档取得新的文档 UUID 与页编号，重名时自动加序号；实现者自带 JVM 与 iOS 测试，覆盖重名、发布失败不留残件）；分页视图加灰色背景与页间距。
- **模拟器验收**：新建笔记和画布各一个；加页、复制、移动、删除并撤销；新建两个图层、隐藏一层、锁定一层后尝试书写、调整顺序；重开确认保存。截图并说明。

## 共同规则

- 见 [第三波契约](ipados-wave3-contracts-2026-11-24.md)「共同规则」与 AGENTS.md「iPadOS」：JDK 21；iOS 测试先打开模拟器，再带 `-PiosTestDevice`；不得削弱、跳过、删除测试；并行任务尚未实现的 RED 测试只在本地临时隔离，不提交。
- 不改 `app/` 下任何文件；改动共享模块时，Android 全部测试须原样通过。
- 模拟器只经 T3 Device 面板操作（`device_open` 返回的 `agent-device`），用完 `device_close`。三个任务共用一台模拟器，先拿到的先用；遇到 `DEVICE_IN_USE` 就先做其他工作，稍后再试，不接管别人的会话。
- 界面文字进 `Localizable.strings`（英文与简体中文），只用黑白与 `Tokens`，触控区不小于 48 dp。
- 每个任务在本文末尾追加「W4-x 实现记录」。
