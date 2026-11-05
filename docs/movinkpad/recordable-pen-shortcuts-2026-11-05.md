# 通用手写笔按键录入（2026-11-05）

## 范围

用户要求先审查 Key Mapper 源码，再结合现有笔输入实现录入映射；不保留旧 Pro Pen 3 配置，不打包，完成后在新远端分支存放并推送。分支：`codex/recordable-pen-shortcuts`。初始阶段不合入 master、不安装或发布 APK；用户随后确认合入本地主分支，见下方合并记录。

设置 → 输入 → 手写笔按键映射 → 录入按键，在区域中按下并松开一个实体键，命名、选择功能与模式后保存。已有映射可以修改功能/模式或删除。录入有 15 秒时限，超时、取消或离开页面不保存。重复录入提示编辑已有项；最多 32 个映射，达到上限明确提示。

## Key Mapper 源码参考

固定审查版本：`65c398a0a11510878ce3bd8c6745a186364388e3`（develop）。

- [RecordTriggerController.kt](https://github.com/keymapperorg/KeyMapper/blob/65c398a0a11510878ce3bd8c6745a186364388e3/base/src/main/java/io/github/sds100/keymapper/base/trigger/RecordTriggerController.kt)：录入时维护按下集合，匹配键码、扫描码、deviceId、source；KeyEvent 松开时只有匹配按下才记录。开始清状态，结束注销 InputEventHub 监听，有录入时限。系统级 Accessibility/evdev 分支不引入本项目。
- [RecordedKey.kt](https://github.com/keymapperorg/KeyMapper/blob/65c398a0a11510878ce3bd8c6745a186364388e3/base/src/main/java/io/github/sds100/keymapper/base/trigger/RecordedKey.kt)：持久身份包含设备描述符，区别于运行时 ID。
- [RecordTriggerState.kt](https://github.com/keymapperorg/KeyMapper/blob/65c398a0a11510878ce3bd8c6745a186364388e3/base/src/main/java/io/github/sds100/keymapper/base/trigger/RecordTriggerState.kt) 和 [RecordTriggerButtonRow.kt](https://github.com/keymapperorg/KeyMapper/blob/65c398a0a11510878ce3bd8c6745a186364388e3/base/src/main/java/io/github/sds100/keymapper/base/trigger/RecordTriggerButtonRow.kt)：显式录入入口、录入状态与完成状态分离。

仅参考行为和设计；本功能独立实现，不复制 Key Mapper 源文件、不添加其运行库。其仓库标注 GPL-3.0，XNotes 现有许可证与第三方声明不变。

## 输入和保存边界

- `PenSignals` 将笔 MotionEvent 的按钮位及 KeyEvent（键码/扫描码/source/描述符）转换为同一种录入身份；不保存临时 deviceId。设备不提供描述符时用空描述符加来源匹配，无法区分同源且无描述符的设备。
- 一次按压最多记录一个 MotionEvent 按钮和一个 KeyEvent 候选（共 2 个）；等所有信号释放后再等待 250 ms 收尾。执行时选定一个主信号：优先笔按钮状态，否则使用第一个 KeyEvent。额外 KeyEvent 仅消费，不更新状态，不复活已暂停的工具，也不撤销待执行动作。这样重叠或先后到达的别名都不会重复执行，且不对真实连续按压节流。同类型出现第二个候选时立即拒绝录入并提示一次只按一个键，不将独立按键静默合并。跨 MotionEvent/KeyEvent 的别名仍以用户一次只按一个实体键为前提。含 MotionEvent 的映射需要笔靠近屏幕；遥控使用需在远离屏幕时录入 KeyEvent-only 映射。
- 只支持可获得按下/松开的输入。系统未投递的事件不能录入。返回、主页、电源、最近任务和 Esc 不录入，保留退出入口。笔尖接触、手指和鼠标移动不是笔侧键。
- 映射执行复用 `ProPenButtons` / `ProPenInput`。笔画中仅缓存去重的按钮转换与绑定快照，正常抬笔后提交最终工具和命令；取消整笔丢弃全部转换，取消单键仅丢弃该键最后一次按下，保留其他键和同键之前的有效按压。
- 所有窗格交接统一使用 `PenInputSnapshot`（带信号身份、有效 HOLD 顺序与临时运行时设备 ID，仅存内存），覆盖标题、手指、直接笔接触、悬停、独立按钮事件和关闭窗格；不再把旧设备的原始位重标为新设备身份。SELECT 不重放，已被手动工具选择覆盖的 HOLD 不复活；共享文档关闭拥有者时快照交给实际保留的编辑器。KeyEvent 松开仍送到全部打开窗格。
- View 重挂仅暂停临时工具显示并保留按住状态；实际关闭与窗口失焦仍清理状态。窗口失焦清除 KeyEvent 边沿以接收下一次新按下；MotionEvent 取消后的已观察边沿保留，防止同一次按压随后重触发。
- 新偏好键 `pen_shortcuts`，不读取、不迁移 `pro_pen_bindings`。写入沿用 checked 原子保存，失败保留原设置；不修改笔记文件。未知/损坏映射加载为空，保存前拒绝无效、重复或超限数据。
- 录入监听只在可见设置页启动期间存在，结束、失焦和页面销毁时解除。普通编辑器只消费已录入的按键；原有非 Pro Pen 的手势设置仍保留。

## 验证

JDK 21 / Gradle 8.13，完整主机测试 **2638 项通过，0 失败、0 错误、0 跳过**。其中 `PenInputHostTest` 使用 Robolectric 4.17 / Android API 35 执行 35 项 MotionEvent/KeyEvent 契约；同一份 `src/sharedTest` 契约也接入 `PenTestRunner` 的设备仪器入口。`:app:compileDebugAndroidTestKotlin` 通过。

覆盖：任意按钮位、不同 source 的笔事件、重复按下、未绑定键透传、工具按住/恢复、笔画内延迟、笔画取消和按键取消、分屏交接与命令去重、失焦后漏收松开的恢复、手指/鼠标/笔尖排除、系统导航排除、重叠及非重叠别名。录入状态、旧配置不迁移、新配置往返、重复/超限拒绝、原子写失败保留目的地也有 JVM 回归。原 3907 条 MovinkPad 事件轨迹仍通过。

独立源码审查发现并修复了 KeyEvent 按住跨分屏丢失、失焦后下一次按压被吞、非重叠别名重复执行、别名松开复活已暂停工具。最后一项保留了修复前测试失败、修复后通过的证据。`git diff --check` 通过。

复现（首次运行需下载仅测试使用的 Robolectric/Android 运行库；不会生成 APK）：

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin
```

**未执行设备仪器测试、未验收实际 UI 布局、未验证其他品牌笔或目标设备硬件兼容性。** Robolectric 是主机模拟，不能替代上述验证。未打包 APK、未安装设备。

## 第二轮专项复查

用户要求再次检查并直接修复、推送，继续不打包。新增回归在修复前复现 **6 个事件用例失败 + 2 个多键录入用例失败**，修复后完整测试通过。

- 标题/手指先切焦后再进入的 MotionEvent 按压不会重复执行；不同设备相同按钮位不误判为继承按压。
- 正常结束笔画才提交 SELECT；ACTION_CANCEL 与 FLAG_CANCELED 不提交待执行选择；同键第二次按压取消不吞第一次命令，重复的取消松开也不回溯撤销有效按压。
- 临时工具按下并松开都发生在一笔内部时，不在抬笔后闪切工具；重叠 HOLD 在交接后保持原按压顺序。
- 录入器使用运行时设备 ID 配对按下/松开但不持久化该 ID，另一设备的空按钮状态或无匹配松开不能完成录入；已取消的笔尖抬起不会形成映射。
- 新增标题切焦与共享文档拥有者关闭的真实编辑器仪器用例；本轮只完成其编译，未执行设备仪器测试。

两轮独立源码复查未发现剩余明确阻塞项。`AGENTS.md` 已补充稳定的交接、取消和录入约定；检查 `README.md` 后确认现有用户说明和开发记录链接仍准确，无需重复改写。未生成 APK。

## 临时按住不改变工具栏选择

用户反馈悬浮按住时工具栏高亮来回滑动。现在将实际输入工具与正式选择分离：`ProPenButtons.selectedTool` 提供基础选择，`ProPenInput` 分别通知有效工具和正式选择；分页、无限画布和分屏共用工具栏统一读取 `toolbarTool`。HOLD 生效和释放不移动工具栏选中状态，SELECT 与手动选择仍正常更新。手动选中当前临时借用的工具会将它设为正式选择，松开按键后保留。

新增 3 项共享 Android 事件契约，覆盖悬浮/落笔/释放期间选择稳定、笔画结束才提交 SELECT，以及手动选中临时工具。JDK 21 下完整主机测试 **2641 项通过，0 失败、0 错误、0 跳过**，其中 Robolectric 事件契约 38 项；`:app:compileDebugAndroidTestKotlin` 与 `git diff --check` 通过。README 和 AGENTS.md 已同步。未打包 APK，未执行设备仪器或真机动画验收。

## 分屏失焦后的残留高亮

混合文档分屏中，选择仅原窗格支持的工具（例如 PDF 批注或分页插入空白）再转移焦点时，接收窗格已按现有规则回退工具，但原文档栏仍从旧选择绘制高亮。现在文档工具栏显式接收窗格焦点：失焦时同时隐藏编辑工具的滑动底色、选中图标色及再次点击设置提示；重新点击工具先按选择处理。该显示门控不清空文档工具状态、不影响缩放锁定等独立设置，也不改变工具栏布局或 HOLD 临时工具逻辑。

验证：2641 项主机测试通过（0 失败、0 错误、0 跳过），Android 仪器源码编译与 `git diff --check` 通过；实际分屏焦点、高亮淡出及点击交互仍待真机验收。README 的功能说明无需调整。未打包 APK。

## 合入主分支

2026-11-05 用户确认后，将功能分支 `e6f2ce1c` 合入本地 `master`，保留主分支已有 PDF 单份存储、AI 多供应商及侧栏滚动更新（合并前 `335ae827`）。源码自动合并，AGENTS.md 的追加记录冲突保留双方内容，并更新 README 的源码合入状态。

合并后 JDK 21 完整主机测试 **2663 项通过，0 失败、0 错误、0 跳过**，`:app:compileDebugAndroidTestKotlin` 与差异空白检查通过。未打包、未安装、未推送主分支；功能分支保留。

## 设置界面视觉整理（2026-11-05）

用户反馈按键映射设置不美观，仅调整外观，不改变录入、去重、保存或绑定逻辑：映射改为一张带细线分隔的描边卡片，每行显示名称、移除（✕）及动作/方式下拉；「录入按键」作为卡片底部整行入口；录入区为圆角灰阶区域、提示居中；命名表单的取消/保存右对齐；说明文字改为次要灰色。模拟器截图核对空状态与录入状态（浅色）；已录入行、夜间模式及 MovinkPad 真机待验证。

## 移除旧手写笔设置与设备专属文案（2026-11-05）

项目不再只面向 MovinkPad。按用户要求，录入式按键映射取代旧的手写笔设置：移除「手写笔双击」「手写笔侧键（点按）」「Redmi Smart Pen 侧键（点按）」三项设置、对应偏好字段（`stylus_double_tap` / `stylus_button_tap` / `stylus_button_1_tap` / `stylus_button_2_tap`）及 Editor 中按厂商键码（718、333、600–604、92/93、F21 点按）触发的处理；这些键现在只能通过录入映射使用，未映射时按普通按键处理（例如 92/93 翻页）。双指、三指点按与音量键翻页保留。旧设置文件仍可读取，多余字段被忽略且不再写回（`SettingsTest.retiredStylusTapSettingsAreIgnoredOnLoad`）。按住侧键的内置锁存不变；`ButtonClick` 不再收到按下事件，因而不会延迟悬停工具。

空映射提示去掉 Pro Pen 3 字样；关于页显示的 versionName 去掉 `-movinkpad` 后缀（调试版为 `-preview1`），包名、versionCode 与签名不变，覆盖安装不受影响；历史 Release 标签命名不追溯修改。JDK 21 下 2689 项 JVM 测试通过（0 失败/错误/跳过），Android 测试源码编译通过；未做模拟器或真机核对。

## 切换类操作与双击动作（2026-11-05）

- 录入映射的操作新增「切换上一个工具」「切换橡皮擦」「切换平移」，与双指/三指点按共用 `dispatchTapGesture`。命令现在在 `ProPenInput.apply` 的 `applying` 保护之外执行：命令选中的工具视为显式选择，之后按住临时工具松开时回到该工具，而不是命令前的工具（`toolPickedByAMappedCommandOutlastsALaterHold`）。
- 每个映射保留「单击/按住」动作，另可设一个「双击」动作（选择工具或执行命令，不支持按住）。语义：单击为按住临时工具时立即生效，间隔内第二次按下改为执行双击动作；单击为切换工具或命令时，等待双击间隔，未出现第二次按下才执行单击动作，出现则只执行双击动作。书写中等待到期的单击在抬笔后执行，书写中的两次按下各自保留事件时间。
- 双击间隔为偏好 `pen_double_press_ms`（默认 300 ms，150–800 ms，读取时钳制），在映射卡片下方以滑块调整，松手后保存并同步到分屏另一侧。
- 存储：映射新增可选字段 `double`，缺省为 `none`；旧版本读取时忽略该字段，不会因此丢弃映射。
- 验证：JDK 21 下 2704 项 JVM 测试通过（0 失败/错误/跳过），含状态机、Robolectric 事件契约（注入时钟与定时器）与设置读写测试；Android 测试源码编译通过。模拟器（sdk_gphone64_arm64，独立 splitpreview 调试包，用后卸载）以 adb 键事件录入按键并核对设置界面；真实手写笔按键的双击手感与默认间隔是否合适待目标设备验证。
