# iPadOS 第四波契约（2026-11-24）

用户要求：M2 之后继续下一轮，先做选择与套索、文字编辑、图层与页面管理；只做本地提交，不推送、不安装、不合并到 `master`；遇到新的设计分歧停下等用户决定。见 [iPadOS 移植方案](ipados-migration-plan-2026-11-23.md)「下一轮决定」。
状态：三支已整合，JVM 与 iOS 模拟器自动化测试、Android Debug 和 Xcode 模拟器构建已通过；合并版界面与真机待验收
本轮边界：只做本地提交与构建验证；不制作交付包、不安装应用、不推送、不发布

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

## W4-A 实现记录

- 分页笔记和无限画布都可点选、框选、套索；在框内拖动可移动，八个方柄可缩放，顶部圆柄可旋转。选择限于可编辑图层的未锁定条目，不拾取画布导图。选区操作提供删除、复制、粘贴、复制一份；副本向右下各偏移 24 个内容像素，点空白可指定粘贴位置。选择和复制不进历史，每次实际变换、删除、粘贴或复制一份各占一步。
- `shared/src/commonMain/kotlin/com/xnotes/edit/SelectionEdit.kt` 从起始快照计算每帧，结束后忽略迟到的事件。`iosKit/src/iosMain/kotlin/com/xnotes/iosapp/ItemSelection.kt` 与 `SelectionTransform.kt` 负责命中、跨页交接、连接绑定和画布索引；跨页撤销回到原图层、原叠放位置。取消、多指接管、切工具、清选区、离开或进入后台时，不把预览写进历史或存档。`SelectionOverlay.kt` 按页面底色取黑白标记，PDF 用黑色；画布使用 `OverlayTessellator` 的 1 个设备像素反色衬边。
- **自动化验证通过**：JDK 21；`:shared:testDebugUnitTest` 2632 项、`:shared:iosSimulatorArm64Test` 2436 项、`:iosKit:iosSimulatorArm64Test` 8 项、`:app:testDebugUnitTest` 1784 项，报告均为零失败。原 `SelectionEditTest` 在 JVM、iOS 各 6 项，未修改；新增 `iosKit/src/iosTest/kotlin/com/xnotes/iosapp/ItemSelectionTest.kt` 4 项覆盖跨页与越界、工具切换、套索变换与剪贴、画布索引与后台保存、连接跟随／删除／绑定恢复。仅在本机用忽略的 `tmp/w4-a/red.gradle` 隔离其他工作树尚未实现的 `FlowTextInputTest.kt`、`PageOpsTest.kt`；没有更改测试或 `app/` 源码。模拟器 `XNotes` scheme 的 `xcodebuild build` 成功，英中两份字符串资源通过 `plutil -lint`。日志：`tmp/w4-a/final-tests.log`、`tmp/w4-a/xcodebuild.log`。
- **待主代理合并后验收（无分支截图）**：三个分支共用模拟器及 Bundle ID，本记录不以分支安装状态证明界面行为。主代理使用 T3 Device 面板打开合并构建，开启「手指绘画」，在一份分页笔记和一份画布的独立副本上**分别**做以下操作：
  1. 画两笔分开的笔迹。「选择」点一笔，再从空白处框住两笔；确认单选、多选成员分别正确。截 `tmp/w4-a-merged/note-band.png`、`tmp/w4-a-merged/canvas-band.png`（框、八个方柄与顶部圆柄）。
  2. 从框中心移动、拖角柄缩放、拖顶部圆柄旋转；每个动作后各撤销、重做一次，核对只有一步。截 `note-transform.png`、`canvas-transform.png` 到同一目录，标明移动方向和旋转结果。
  3. 点空白清选区，切「套索」绕住一笔并避开另一笔；画布再绕住一个导图节点，确认节点不入选。截 `note-lasso.png`、`canvas-lasso.png`，说明圈入／未圈入的对象。
  4. 对选区「复制一份」，核对右下偏移并撤销／重做；「复制」后点空白「粘贴」，再「删除」，逐步撤销／重做核对数量。截 `note-copies.png`、`canvas-copies.png`，标明原件、副本和粘贴位置。
  5. 拖动途中分别加第二指、切工具、返回文档列表；重开后核对笔迹和绑定仍在原位，未新增撤销步。夜间在浅色页／PDF 上检查黑色工具标记，在黑底带白图片的画布上检查跨边界的反色衬边，分别截 `note-contrast.png`、`canvas-contrast.png` 并描述可辨性。上述截图均须来自同一合并构建。
- **待用户确认**：本波对象剪贴板限定同一打开文档（重新挂载视图不清空）；是否另做跨文档或系统剪贴板互通，需确认资源迁移范围，不影响本波操作。
- **待真机验收**：iPad Air（M2 及以后）＋ Apple Pencil Pro 的握笔与触控命中、掌触及第二指交接、连续缩放／旋转的显示延迟和混合背景上的手柄可读性。本轮未安装真机、未生成交付包、未推送。

## W4-B 实现记录

- `shared/src/commonMain/kotlin/com/xnotes/edit/FlowTextInput.kt` 把 UTF-16 位置、选区、分段和删字映射到 `FlowEditor`。组字时正文显示候选，反复修改不产生历史；定稿后一步撤销，取消空候选恢复原选区和段落样式。非离开式保存、后台保存只捕获已定稿的正文，不把拼音预览写入原件。
- `iosKit/src/iosMain/kotlin/com/xnotes/iosapp/TextKeyboard.kt` 将 `UITextInput` 的位置、范围、marked text、候选替换、系统编辑菜单与硬件按键接入同一输入状态；`TextSurface.kt` 与 `TextTarget.kt` 提供点按放置、拖动选字、光标、选区和组字下划线。分页使用实际 `FlowFrame`；分页正文支持加粗、斜体、项目符号列表。工具标记取纸色的对比色（PDF 用黑色）；混合内容画布标记另垫一个物理像素的反色描边。
- **画布格式决定（已与主代理确认）**：`.xcanvas` 不序列化裸 `TextItem`。沿用 `app/src/main/java/com/xnotes/ui/InfiniteEditor.kt` 的 `insertTextBox`：画布模型和文件都是零宽透明边线、无填充的 `ShapeKind.RECTANGLE`，文字和字体由其 `ShapeLabel` 承载；`TextItem` 仅用于 iPad 编辑几何和字体。既有 Android 文本框也能命中；不新增条目类型、格式版本或 Android 改动。空白点击是草稿，首次输入才成为可撤销的对象；删除最后一个字会移除框，撤销恢复原框。画布框沿用纯文本，不提供分页正文的行内格式按钮。
- **自动回归**：原样的 `FlowTextInputTest.kt` 覆盖契约；`shared/src/commonTest/kotlin/com/xnotes/edit/FlowCompositionSelectionTest.kt` 增测逆向跨段选区、粗斜体、捕获失败后的组字恢复及组合字符删除；`iosKit/src/iosTest/kotlin/com/xnotes/iosapp/TextKeyboardTest.kt` 直接调用 UIKit 输入协议，验证分页格式、删除和跨段替换，画布候选、撤销、对象身份隔离，以及通过 `CanvasCodec` 重读后与 Android 新建框逐项比对几何、描边、填充、标签文字和字体。未改 `app/`、原样测试或互通金样。
- **验证结果**：JDK 21，执行 `./gradlew -I tmp/w4b.gradle :shared:testDebugUnitTest :shared:iosSimulatorArm64Test :iosKit:iosSimulatorArm64Test :app:testDebugUnitTest -PiosTestDevice=3A53F8F7-6847-446E-81A4-174A76DBFB48`：四组分别为 2637、2441、6、1784 项，均无失败、错误或跳过。两端各跑原样 `FlowTextInputTest` 9 项；仅本地初始化脚本隔离未合入的 `SelectionEditTest.kt` 和 `PageOpsTest.kt`，不提交该脚本。`xcodebuild -project iosApp/XNotes.xcodeproj -scheme XNotes -destination 'platform=iOS Simulator,id=3A53F8F7-6847-446E-81A4-174A76DBFB48' -derivedDataPath tmp/derived build` 成功。
- **待主代理合并后验收**（本工作树无 UI 截图）：① 打开分页笔记，选「文字」，点正文输入英文、换行并退格；切简体拼音键盘输入 `zhong`、观察组字下划线并选「中」。② 拖选正文，分别切换加粗、斜体、项目符号列表，逐次撤销、重做；验证全选、复制、剪切、粘贴及硬键盘方向键、Shift 扩选。③ 切换浅色页、PDF 和深纸检查光标、选区与下划线；在深色画布上的白图旁检查反色描边。④ 在画布空白处点按，输入中英文；切工具后再点原框插字，撤销、重做，确认没有多建框。⑤ 两种文档分别返回首页、重开，确认正文格式、段落、框的位置与文字均保存；把分页组字或选区、画布编辑与重开结果截图放到 `tmp/` 并附说明。设备没有拼音输入源时另记，不用协议测试冒充实际选候选。
- **待用户确认**：无新增设计分歧。**待真机验收**：iPad Air（M2 及以后）+ Apple Pencil Pro 的点按与拖选、软键盘候选和外接键盘快捷键、实际画面可读性及长文输入延迟。未安装真机、未推送、未制作交付包。

## W4-C 实现记录

- **新建**：首页在当前文件夹创建一页空白 A4 竖版笔记或空画布，直接接管此次创建的会话，不按文件名重新认领。`shared/src/commonMain/kotlin/com/xnotes/storage/lite/LiteCreate.kt` 先在文稿目录内独立暂存，读回核对类型、UUID、页编号及摘要，再在协调写入期间复核目录、文件身份和摘要。通过同卷排他硬链接发布；目标已存在（含检查后的竞态）就换下一个序号，不替换、不删除已有目标。失败清理暂存与本次私有资产；没有改变原有 `open`、`save` 或恢复协议。
- **分页**：`shared/src/commonMain/kotlin/com/xnotes/edit/PageOps.kt` 用现有页面命令执行加页、深复制、删除和移位，每次一条历史。同尺寸新页及复制页取得新永久编号，撤销重做保留编号；未启用页编号的旧包不在编辑时擅自升级。页面面板支持缩略图选页、长按拖放、删除确认，最后一页不能删。分页视图的纸边与页缝露出灰色桌面，夜间为深灰。
- **图层**：`iosKit/src/iosMain/kotlin/com/xnotes/iosapp/DocumentPanels.kt` 和同目录 `PanelControls.kt`、`PanelList.kt` 接入分页按页、画布按文档的图层栈。可增删、改名、显隐、锁定、拖动排序；选层会保存但不占撤销。删除图层同时删除该层成员及导图树，一次撤销恢复。新笔迹归当前可写层，多层湿墨按层序合成；隐藏只过滤显示。画布侧栏覆盖现有表面，只改视口右侧遮挡量，不缩放或重新居中。
- **缩略图**：`iosKit/src/iosMain/kotlin/com/xnotes/iosapp/PanelThumbnail.kt` 从单层独立几何快照异步绘制透明底；不画纸张、PDF 或其他层。隐藏层也可预览；关闭缩略图后不计算边界或位图。翻页、关闭面板/文档、内容版本改变即取消旧请求，在后台绘制结束前保留原文档资源。
- **验证**：JDK 21；`:shared:testDebugUnitTest`、`:shared:iosSimulatorArm64Test`、`:iosKit:iosSimulatorArm64Test`、`:app:testDebugUnitTest`。`PageOpsTest` 与既有 `LiteStoreTest` 未改；新增 `LiteCreateTest` 在 JVM 与 iOS 覆盖身份与重开、重名竞态不覆盖、失败无残件、暂存文件被改写时拒绝发布。`DocumentPanelsTest` 覆盖页面与图层保存重开、选层不占撤销、隐藏/锁定层拒绝写入、默认图层重排及删层带导图整步撤销。仅用忽略目录中的 `tmp/w4c.init.gradle` 临时隔离另外两路未实现的 RED 测试文件，未修改或提交测试排除规则。Xcode 27.2 的 `iosApp/XNotes.xcodeproj`、`XNotes` scheme 模拟器 Debug 构建成功；英文和简体中文 `.strings` 均通过 `plutil -lint`。
- **测试结果**：上述四个任务依次通过 2637、2441、7、1784 项，各任务 XML 报告均为 0 失败、0 错误、0 跳过。指定的 iPad 模拟器为 `3A53F8F7-6847-446E-81A4-174A76DBFB48`。构建、测试日志位于本工作树的 `tmp/w4c-xcode-final.log`、`tmp/w4c-tests-final.log` 和 `tmp/w4c-panels-final.log`。
- **边界**：未改 `app/`，未推送、安装应用或制作交付包。共用模拟器上同一 Bundle ID 的不同分支会相互覆盖，因此本分支未进行 UI 验收、无截图；单测通过不等于界面或真机已验收。

**待主代理合并后验收**（仅用 T3 Device 面板操作合并版，以下均未执行）：

1. 在同一文件夹点「新建笔记」，确认是一页空白 A4 竖版；打开「工具设置」启用手指绘画，第一张纸上写 A。点「页面」→「在后面加页」，第二页写 B。检查缩略图与页序，滚到页缝，记录日间灰桌面与白纸。
2. 选第一页，点「复制一页」，在副本添一笔；来回选原页和副本，确认原页未变。长按名称或缩略图，把副本拖到末尾；撤销一次、重做一次，各核对顺序。点「删除」先取消再确认，核对一次撤销恢复页和内容、重做删除。退到只剩一页时验证删除不可用。
3. 在有内容的页上打开「图层」，新建两个层并分别改名，在两层各画不同笔迹。隐藏其中一层，核对纸上只少该层而其缩略图仍在；在隐藏层和锁定层分别尝试书写，解锁/显示后确认没有新增笔迹。切换当前层、长按拖动换序、撤销/重做；另一次拖动取消应不改变顺序。删除一层后一次撤销，确认内容与层序一并恢复。
4. 另加一张空白页，切换页面确认图层互不串页。依次关闭/打开缩略图，快速翻页、书写后返回，确认未出现前一页/旧版本的预览。保存返回首页，重开核对页序、笔迹、图层名称、显隐/锁定及当前层。切换系统深色外观，记录深灰桌面、页缝及纸边。
5. 首页点「新建画布」，在两个新图层上分别写可识别笔迹，重复改名、隐藏、锁定试写、拖动排序、撤销与删除恢复；反复开合侧栏，对照笔迹位置和缩放，确认画布不缩窄、不重新居中。返回后重开核对层序、显隐、锁定和笔迹。再创建同类文档确认自动加序号、已有文件未被替换。由主代理把首页、日/夜页缝、分页/画布侧栏及保存重开的对照截图放入 `tmp/` 并注明操作。

**待用户确认**：无新增设计问题。旧包启用页链接仍须走既有的显式升级流程。

**待真机验收**：在 iPad Air（M2 及以后）＋ Apple Pencil Pro 上，验多层遮挡下的实时湿墨和最终笔迹一致性、压感与延迟，以及在 Metal 画布缩放/开合侧栏时是否闪屏或掉帧。模拟器不能证明这些结果。

## W4 整合记录

- 以 W4-A `b3825e83` 为基线，依次合入 W4-B `04ec73ae`、W4-C `280de9ca`。分页与画布共用六种工具；保留选择标记、`UITextInput`、按层湿墨、灰色桌面、侧栏遮挡量与首页新建。画布文本框仍用无边框矩形及 `ShapeLabel`。三支实现记录逐字保留，英文和简体中文字符串无丢失。
- 页或层命令先定稿组字、解除旧键盘目标、回滚选区并清除旧粘贴位置，再捕获命令和修改模型；随后刷新页位置、正文布局和画布条目。撤销／重做结构命令同样解除旧输入目标。手动切换工具先取消旧指针事务；Pencil 笔画中的按键切换仍等正常抬笔，取消笔画不执行。
- `iosKit/src/iosTest/kotlin/com/xnotes/iosapp/EditorIntegrationTest.kt` 新增 5 项：组字中删页与旧键盘范围隔离；移动封面与撤销后的正文光标页；移动文本框途中删层、撤销后的几何与索引；图层隐藏／锁定／重排与组字的历史顺序；取消笔／荧光笔／橡皮后忽略迟到事件。未更改或排除原契约测试。
- 验证：JDK 21.0.13.1；指定的 Gradle 全量命令成功。按 XML 汇总，shared JVM 2654、shared iOS 2458、nativelibs iOS 6、iosKit iOS 18、app JVM 1784，均为零失败、零错误、零跳过。`:app:assembleDebug` 通过，Xcode 27.2 的 `XNotes` scheme 对 `generic/platform=iOS Simulator` 构建成功；日志在 `tmp/w4-integration/gradle.log`、`tmp/w4-integration/gradle-final.log`、`tmp/w4-integration/xcode.log`。未改 `app/` 源码；未安装或操作模拟器界面。界面验收与截图留给主代理，Apple Pencil、实际组字候选与 GPU 显示仍需单独验收。
