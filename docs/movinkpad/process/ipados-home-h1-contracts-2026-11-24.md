# iPadOS 首页 H1 契约（2026-11-24）

上级计划：[iPadOS 首页对齐 Android](ipados-home-plan-2026-11-24.md)
状态：S1 交 gpt-6.2-astra 实现；界面由主代理在 iosKit 实现

## S1 浏览区纯逻辑搬进 shared（gpt-6.2-astra）

目标：Android 浏览区的排序、分组、日期与大小文字、视图选项模型只保留一份实现，放在 `shared` 公共代码，Android 与 iPad 共用。

范围（现有 Android 代码，行为一律不变）：

- `app/src/main/java/com/xnotes/ui/ExplorerSort.kt`：`explorerComparator`。
- `app/src/main/java/com/xnotes/ui/ExplorerGroups.kt`：`EntryKind`、`Hue`、`ExplorerWords`、`EntryGroup`、`DateBucket`、`dateBucket`、`groupEntries`、`hueName`、`hueOf`、`clockText`、`formatWhen`、`formatFull`、`openedLabel`、`formatSize`、`countsLabel`、`itemsLabel`。
- `app/src/main/java/com/xnotes/settings/ExplorerView.kt` 的枚举与 `ExplorerView` 数据类，以及 `Settings.kt` 里的 `ExplorerSortKey`。

要求：

1. 新包 `com.xnotes.explorer`（`shared/src/commonMain`）。日期用已加入的 `kotlinx-datetime` 0.7.1（`TimeZone`、`LocalDate`；`Month`、`DayOfWeek` 在 JVM 上即 `java.time` 类型）。不得引入其他依赖，不得用 `java.*`、`String.format`。
2. 条目抽象成公共接口 `ExplorerEntry`（`name`、`isDir`、`size`、`modified`、`created`、`color: Rgba?`）；Android 的 `BrowseEntry` 实现它。分组与排序函数对 `ExplorerEntry` 泛型，返回的组里保留调用方的具体类型。
3. `ExplorerView` 的 JSON 读写：公共代码里不碰 `org.json`；Android 的 `toJson`/`fromJson` 留在 app 端（扩展函数或 `Settings.kt` 内），字段名与默认值不变，已存的设置读出来完全一样。
4. app 端只留薄适配：现有 `app/src/test/java/com/xnotes/ui/ExplorerGroupsTest.kt`、`ExplorerSortTest.kt` 及其他 app 测试**一个字都不改**且全部通过（它们传入 `java.time.ZoneId`、读取 `DateBucket.start` 等，需要的话由 app 端同名函数转换后调用共享实现）。app 中其余调用点改为使用共享实现，不保留第二份逻辑。
5. 在 `shared/src/commonTest/kotlin/com/xnotes/explorer/` 新增公共测试，覆盖：比较器（四种键、升降序、文件夹在前、名称兜底）；`dateBucket` 各档（今天、昨天、本周早些、上周、本月早些、今年其他月、往年，含未来时间按今天算、周起始日）；`groupEntries` 四种分组及日期组排序方向；`formatWhen` 三种样式与 12/24 小时；`formatSize` 各档（999 B、1000 B→1 KB、1.5 MB、GB）；`countsLabel` 省略零项；`hueOf` 黑白灰与各色相边界。测试在 JVM 与 iOS 模拟器都跑。
6. iPad 列表 `shared/src/iosMain/kotlin/com/xnotes/storage/ios/IosDocumentList.kt`：`IosDocumentEntry` 增加 `size`（字节，文件夹为 0）、`createdAtMillis`（取文件创建时间，取不到为 null）、`childCount`（文件夹内可显示条目数，即同一过滤规则下的条目数；文件为 null），并实现 `ExplorerEntry`（`created` 取不到时用 `modified`，颜色暂为 null）。保留现有过滤规则（隐藏「.」开头、符号链接、未知类型）。补 iOS 测试。

验收（实现方在本文末尾追加「S1 实现记录」，中文，列测试数，不写本机绝对路径）：

- `:shared:testDebugUnitTest`、`:shared:iosSimulatorArm64Test`（需 `-PiosTestDevice=<UDID>`，模拟器已启动）、`:app:testDebugUnitTest`、`:app:assembleDebug`、`:iosKit:iosSimulatorArm64Test` 全部通过，零失败零跳过。
- `git diff` 中 `app/src/test` 无改动。
- 构建需 `JAVA_HOME=/opt/homebrew/opt/openjdk@21`。只做本地提交，不推送。

## H1 界面（主代理）

对照 Android 平板截图（已忽略目录 `tmp/w4-merged/android-*.png`）：

- 左侧浮动侧栏（xnotes 标题、收起按钮；首页及其下文件夹树、分屏工作区、AI、恢复资料、最近；分隔线下回收站、偏好设置、关于），可收成图标窄栏。iPad 未有的功能（分屏、AI、最近、回收站、偏好设置、关于）先置灰，点了提示尚未推出；随后续段落逐项启用。
- 浏览区顶部：首页图标 + 位置名、搜索（H2）、布局切换（H1 只有网格、列表）、视图选项与更多（H2/H3）。下方芯片行：排序（网格时）、按日期分组；右侧计数（「1 个文件夹 · 2 个文件」，列表加总大小）。
- 网格：文件夹卡片（图标、名称、条目数、⋮）、日期分组标题（「今天 2」）、文件磁贴（缩略图、类型角标、名称、时间、⋮）。列表：名称、类型、页数、大小、修改时间列，表头可点排序。
- 右下角圆形新建按钮，菜单：新建笔记、新建画布、导入 PDF、新建文件夹；空文件夹显示插画与三个大按钮。
- 新建笔记／画布／文件夹先弹命名框（Android 的新建笔记页含封面、纸张、模板，列入 H3）。
- 恢复资料页：列出待恢复记录，可恢复或放弃（取代首页顶部的待恢复行）。
