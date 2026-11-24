# iPadOS 工具栏图标（2026-11-24）

用户要求：iPad 顶部工具栏与 Android 版差距较大，先为已经实现的功能加上工具图标。
状态：已实现并通过自动化测试；模拟器日夜间截图验收通过；VoiceOver 朗读与真机待验
本轮边界：只做本地提交与模拟器验证；不安装真机、不推送、不合并到 `master`

## 现状

iPad 编辑器顶栏是两行文字按钮：第一行「返回 · 文件名 · 工具设置」，第二行笔、荧光笔、橡皮、选择、套索、文字、撤销、重做，选中时出现删除／复制／粘贴／复制一份，文字工具时出现加粗／斜体／列表，最后是页面、图层。Android 是一行图标栏（`app/src/main/java/com/xnotes/ui/Toolbar.kt`、`InfiniteToolbar.kt`），图标来自 `ui/icons/XnotesIcons.kt`（24×24 视框、2 px 圆头线条）和 `res/drawable/ic_stroke_*.xml`。

## 主代理决定

- **图标复制到 iosKit。** AGENTS.md 禁止在 `:shared` 或 `:app` 引入 Compose Multiplatform，所以把 `XnotesIcons` 中本次要用的图标（及 `icon`/`circle`/`rect`/`roundRect` 等辅助函数）逐字复制到 `iosKit` 的 `com.xnotes.iosapp.ui.icons`；`ic_stroke_regular.xml`、`ic_stroke_highlighter.xml` 转为同路径数据的填充 `ImageVector`（视框 100）。路径数据不改，以便日后两端一致。
- **一行顶栏，顺序参照 Android**：返回（`prev`）· 文件名 · 页面（分页笔记，`sidebar`）· 图层（`layers`）· 笔（`ic_stroke_regular`）· 荧光笔（`ic_stroke_highlighter`）· 橡皮（`eraser`）· 选择（`select`）· 套索（`lasso`）· 文字（`text`）· 撤销（`undo`）· 重做（`redo`）· 上下文按钮 · 工具设置（`sliders`）。上下文按钮：有选区时删除 `trash`、复制 `copy`、粘贴 `paste`、复制一份 `duplicate`；文字工具时加粗 `bold`、斜体 `italic`、列表 `listBullet`。宽度不够时中间工具段横向滚动，返回、文件名与工具设置不滚走。画布没有「页面」按钮。
- **样式照 Android `ToolbarIcon`（REGULAR 档）**：按钮 52 dp（不小于 `MinTouchTarget`）、选中圆 38 dp、图标 22 dp；选中时实心圆为选中底色（日间黑、夜间白），图标反色；未选中为次要文字灰，禁用为禁用灰；无水波纹，按下缩放 0.85。尺寸写入 `iosKit` 的 `Tokens.kt`，不写字面量；只用黑白与中性灰。
- **工具设置第二行**（手指绘画、局部擦除）保持文字开关，只把入口换成图标；选中内容的浮动菜单、笔色笔宽面板等 Android 其他界面不在本次范围。
- **无障碍与自动化**：每个图标按钮的无障碍标签沿用现有字符串（「笔」「撤销」等）并保留选中／禁用语义，现有测试与模拟器脚本可继续按名称查找。

## 实现要求

- 只改 `iosKit`（与本文档）；不改 `app/`、`shared`，不改已有测试。
- 先写会失败的 `iosKit/src/iosTest` 测试：顶栏每个按钮 id 都有图标和非空标签（用穷尽的 id→图标映射，未知 id 编译不过或测试失败）；尺寸来自 token；选中与禁用状态的颜色取自调色板。
- 运行 iosKit iOS 测试、shared JVM/iOS 全量、`:app:assembleDebug`、Xcode 模拟器构建；在本文档末尾追加「## 实现记录」（中文，不写本机绝对路径，列测试数）。界面截图验收由主代理做。

## 实现记录

- 分页笔记和画布共用单行顶栏。返回、文件名和工具设置固定，中段按页面、图层、编辑工具、撤销、重做、上下文操作排列，空间不足时可横向滚动。画布隐藏页面入口。选区清除后，若点到可粘贴的空白位置，仍可直接粘贴。手指绘画和局部擦除保留在设置第二行。
- 核对了 17 个线框图标的 Android 定义、两份笔类 XML 的全部 5 条路径，以及 19 个按钮的英文与简体中文标签。荧光笔保留三条圆头横线及绕中心旋转的半透明色带。顶栏按钮、选中圆、图标分别取 52、38、22 dp，按下时图标缩至 85%。按钮保留点击、选中和禁用语义。
- 先新增 `ToolbarIconTest` 的 4 项测试。首次运行在测试编译阶段发现缺少图标映射、尺寸与调色板。实现后四项均通过，覆盖按钮映射与标签、笔类矢量、触控尺寸、日夜间选中与禁用颜色。
- 验证使用 JDK 21 和已启动的 iPad Pro 13 英寸（M5）／iPadOS 27.2 ARM64 模拟器。下列测试均为全量运行，失败、错误和跳过均为 0。

| 验证项 | 结果 |
| --- | --- |
| `:iosKit:iosSimulatorArm64Test` | 30 项通过，含新增 4 项 |
| `:shared:testDebugUnitTest` | 2,654 项通过 |
| `:shared:iosSimulatorArm64Test` | 2,458 项通过 |
| `:app:assembleDebug` | ARM64 Debug 构建通过 |
| Xcode `XNotes` scheme | Debug 模拟器构建通过 |

模拟器日夜间外观、窄窗滚动和实际无障碍朗读待界面验收。本轮未操作应用界面。

## 主代理模拟器验收

快进到 `ipados`（`7618f862`）后在 iPad Pro 13-inch (M5) 模拟器 iOS 27.2 上查看：分页笔记顶栏为一行图标，顺序为返回 · 文件名 · 页面 · 图层 · 笔 · 荧光笔 · 橡皮 · 选择 · 套索 · 文字 · 撤销 · 重做 · 上下文按钮 · 工具设置；选区时出现删除、复制、粘贴（无内容时变灰）、复制一份，文字工具时出现加粗、斜体、列表；画布无「页面」按钮。选中工具为黑底白图标，夜间为白底黑图标，禁用项为浅灰。无障碍标签仍为「笔」「撤销」等原文字。截图在已忽略的 `tmp/w4-merged/tb*.png`。
