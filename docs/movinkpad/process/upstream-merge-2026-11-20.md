# 上游合并与 MovinkPad 适配（2026-11-20）

用户要求：将上游 XNotes 合入独立审查分支，保留本地存储、笔输入、图层、分屏、AI 和思维导图；接入上游书写与导出改进，关闭直接编辑 PDF。
状态：源码合并完成；验证结果见下表，MovinkPad 真机待验。
本轮边界：仅在合并工作树调整源码并验证，未提交、打包、安装、推送或发布。

## 范围与入口

本地基线 `a5b9ab04`，上游 `shardulvs/xnotes-android` 从共同祖先 `3926304a` 到 `27e83a07`，共纳入 122 个本地基线尚无的提交。工作分支为 `merge/upstream-2026-11-20`。源码版本名 0.9.10，正式包 versionCode 仍为 98。

- 在笔工具设置中可以启用 One Euro 防抖，调整强度与慢速阈值；速度笔可调变细程度、平滑度和最高速度，保存笔画时携带采样宽度。分页与无限画布都保留原有图层、标尺和临时按键工具逻辑。
- 颜色面板支持从屏幕取色，浅色与夜间模式分别保存笔色。在「关于」页长按应用图标可以选择启动器图标。
- 连续纵向阅读时可在视图菜单启用自动滚动、调整速度；拖动页面时暂停，离开画面后停止。自由移动与适宽吸附按上游规则处理。
- 分页截图冻结后可以用笔或手指移动或缩放。调整期间旧导图区域动作失效，确认时根据新矩形生成单页引用；跨页或越界时保留截图、清除区域来源。取消时恢复原边框，以及原页面仍有效时的引用。
- 新导出的 PDF 支持矢量笔迹与半透明荧光笔。内存紧张或界面隐藏时可释放已解码的图片与 PDFium 缓存。页面尺寸字段支持 0.01 mm；文本字段保留有效光标位置。

## 集成取舍

- 上游 `312ef329..c766f575` 相关的 PDF 字节访问、内嵌笔记、排队导出和暂存复制类及测试一同编译。产品保持 `DocumentKind.PDF_DOCUMENTS_ENABLED = false`。资源管理器仍按笔记元数据标记带 PDF 的笔记。单个、多文件导入均生成笔记，原件按本地共享资源协议保存。
- 移除了上游自动合入的单文件「保留为 PDF」、批量分享前写回 PDF、启动时恢复写回队列的调用。清单中禁用写回服务。笔记创建和保存继续经过已有身份、来源、正文版本及发布检查，遇到 PDF 目标时拒绝写入。导出新 PDF 与这些写回入口独立。
- 保留本地 GL 统计、几何与纹理诊断和相应的 HUD。湿墨层在分屏调整尺寸或屏幕旋转后重建，并用表面代次约束异步回调。系统启动画面取代了固定时长的动画，浅色为纯白，夜间为纯黑，待本地工作区呈现已准备好再显示编辑界面。
- 保留本地会话拥有者和队列。本地保存可以等待输入稳定，并在前一任务完成后继续提交后续工作。上游 `RetiredEditors.await()` 会在新 Activity 启动时完成整棵父 Job，后续子任务可能被拒绝，等待旧拥有者退场的工作也可能形成互等。保留该独立类及测试，Activity 重建仍走本地交接流程。
- 图层、分屏共享文档、原 PDF 租约、思维导图、录音证据与 AI 入口沿用本地机制。普通界面取本地黑白调色板，笔迹色和启动器图标保留内容颜色。

## Editor.kt 冲突逐块记录

| 原顺序 | 处理 |
| --- | --- |
| 1 | 每个进程首次使用目录时清理孤立临时文件，保留本地拥有者保护。 |
| 2 | 自动保存 URI 由共享文档拥有者提供，后台作用域沿用本地实现。 |
| 3 | 保留 `newCanvas()`，本地 JVM 与 Android 宿主测试均有实际调用。 |
| 4 | 画布快照通过受控发布，保留预期来源和录音回执。 |
| 5 | `buildPalette` 保留 `monochromePalette`，日夜笔色独立切换。 |
| 6 | 保留呈现设置的独立持久化，并保存当前及另一套笔色；重建由本地会话拥有者接管。 |
| 7 | 恢复笔记前校验已准备的会话，采用或释放其租约。 |
| 8 | 导图引用打开前核实实际拥有者、文档和页身份。 |
| 9 | 保留引用请求的版本、失败原因、原拥有者定位和工作区采用检查。 |
| 10 | 方形缩略图保留导图分支、画布分支及本地缓存失效规则。 |
| 11 | 整页缩略图使用同一套缓存与导图预览，不引入直接 PDF 文档缓存。 |
| 12 | 新建文件先确认空目标再发布，保留本地身份和失败恢复。 |
| 13 | 新画布先应用已保存的背景，再冻结带有新身份的快照并发布。 |
| 14 | PDF 导入共享原件并创建批注笔记，单个与批量导入共用本地流程。 |
| 15 | 本地契约和恢复异常阻止保存，保留具体拒绝类型。 |
| 16 | 编码前固定笔记引用的 PDF，继续使用受控保存入口。 |
| 17 | 发布时检查实际或预期版本，用本次结果确认录音证据。 |
| 18 | 更新文件戳后向调用方返回已核验的版本回执。 |
| 19 | 保存走完整候选、暂存与发布；移除编辑器内直接改写 ZIP/PDF 尾部的分支。 |
| 20 | 保存结束由本地拥有者和本次发布结果处理脏标记、失败及后续编辑。 |
| 21 | 分屏与导图独立识别，页数和 PDF 标记从分页笔记的元数据读取。 |
| 22 | 替换模型时结束旧文档的排版文本会话。 |
| 23 | 合并笔记前核实存储快照，并持有各来源的共享 PDF 租约。 |
| 24 | 保留共享拥有者、阅读位置和实际设备按键录入；避免再次分派厂商按键。 |
| 25 | 关闭时结束本地工作会话，保留保存和恢复交接。 |

## 验证

环境为 macOS ARM64、Zulu OpenJDK 21.0.6、Gradle Wrapper 8.13、AGP 8.12.3、Android SDK 36、Build Tools 36.0.0。系统的 `java_home -v 21` 未找到 JDK，使用工作树内临时下载的 JDK 21。构建、Gradle 项目状态及本次 Maven 缓存均独立。以下命令从工作树根运行。

```sh
export JAVA_HOME="$PWD/tmp/jdk/zulu21.40.17-ca-jdk21.0.6-macosx_aarch64/zulu-21.jdk/Contents/Home"
export ANDROID_HOME="$PWD/tmp/sdk"
export GRADLE_USER_HOME="$PWD/tmp/gradle-home"
export JAVA_TOOL_OPTIONS="-Dmaven.repo.local=$PWD/tmp/m2 -Djava.net.preferIPv4Stack=true"
../gradlew --no-daemon --project-cache-dir "$PWD/tmp/gradle-project" :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest
```

| 检查 | 结果 |
| --- | --- |
| 主程序、Android 仪器测试 Kotlin 编译 | 两项目标均成功 |
| JVM 测试（含 Robolectric） | 531 个测试套件，4,977 项通过，失败、错误、跳过各 0 |
| 英文及两套简中 strings.xml | 1,048 个可翻译资源键一致，资源类型与名称无重复；两份简中逐字节相同 |
| Android 运行与目标硬件 | 仪器测试仅编译，设备运行 0 项 |

第一次运行暴露了 PDF 导入和弹窗的残留调用、资源管理器元数据参数缺失及来源类型分支不完整，现已修复。一次依赖下载遇到 TLS 握手失败，重试成功。首次完整 JVM 测试 4,976 项中有 1 项失败：上游 PDF 尾部编解码测试只预期图片和 manifest，本地正常笔记还必须包含 `xnotes-identity.json`。修正了测试的期望条目，继续验证图片字节和嵌入笔记可读性。文档类型测试断言大小写 PDF 扩展名均不进入文档列表，后缀处理仍可用于底层格式。另测空 PDF 不能成为笔记发布目标，以及截图移动、缩放、取消和越界时的导图来源。

## 其他人工调整的文件

以下 Kotlin 文件以各源集的 `java/com/xnotes/` 为根。上游原样纳入的改动可在工作树用 `git diff --cached HEAD --stat` 完整列出。

| 文件 | 原因 |
| --- | --- |
| `app/build.gradle.kts`、`app/src/main/AndroidManifest.xml` | 使用项目规定的 Build Tools 36.0.0，保留版本号和启动别名，禁用直接 PDF 文档的后台服务。 |
| 主源码 `EditorActivity.kt` | 保留启动屏、取色路由和内存回收；恢复本地会话重建与分享流程，清除 PDF 写回的启动、通知及导入调用。 |
| `canvas/InteractionController.kt` | 调整中屏蔽旧区域凭据，确认后重算，取消后重新签发原区域。 |
| `gl/GlWetPad.kt` | 补齐合并后重建路径所调用的备用缓冲尺寸更新方法。 |
| `mindmap/MapSourceAccess.kt`、`storage/NotebookStorage.kt` | 普通笔记来源选择显式拒绝直接 PDF 文档；笔记目的地检查 PDF 后缀及提供商的文件名、MIME 类型。 |
| `ui/Backstage.kt`、`ui/ExplorerTiles.kt`、`ui/TrashPane.kt` | 单文件及批量导入均创建笔记；资源管理器和回收站按本地笔记元数据识别 PDF 批注，统一相关间距。 |
| `ui/AboutPane.kt`、`ui/NewNoteScreen.kt`、`ui/ColorPicker.kt`、`ui/MaterialColourPicker.kt`、`ui/ShapePopup.kt`、`ui/EyedropperOverlay.kt`、`ui/IconPicker.kt`、`ui/theme/Tokens.kt` | 将文字、行高和布局间距接入现有刻度；取色提示使用 72 dp 顶部间距避开工具栏。 |
| `ui/PenRecorder.kt`、`platform/FontCatalog.kt` | 更新 Activity 类型或说明。 |
| `app/src/main/res/values/strings.xml`、`values-b+zh+Hans/strings.xml`、`values-zh-rCN/strings.xml`（后两项同属 res） | 恢复「PDF 笔记」语义，补足图标、取色等中文资源，同步两份简中。 |
| JVM 测试 `canvas/PagedSourceRegionCaptureTest.kt`、`format/XnotesPdfTest.kt`、`storage/NativeCreationTest.kt` | 验证截图调整生命周期、本地身份 ZIP 条目和 PDF 创建目的地拒绝。 |
| JVM 测试 `pen/MainMapInputHostTest.kt`、`pen/MainMixedInputHostTest.kt`、`pen/MainPairKeyOwnerHostTest.kt`、`mindmap/MapSourcePreviewBackendTest.kt` | 更新 Activity 类型、反射和测试名称。 |
| Android 测试 `mindmap/AndroidMainFileNetworkHostTest.kt`、`mindmap/AndroidMainMapSourceNavigationHostTest.kt`、`mindmap/MainMapWorkspaceRecreateHostTest.kt`、`mindmap/MainMixedEntryPointsHostTest.kt`、`mindmap/MainMixedMapEditingHostTest.kt` | 将实际启动与重建的宿主更新为 EditorActivity。 |
| Android 测试 `mindmap/MapGraphSourceEditingHostTest.kt`、`mindmap/MapPairOpenEditingHostTest.kt`、`mindmap/MapVisibleEditingRegressionTest.kt`、`mindmap/NativeSplitReplacementEditingHostTest.kt`、`mindmap/OriginalPdfLibraryPaneHostTest.kt`、`ui/PaneChromeHostTest.kt` | 更新导图、分屏、PDF 原件库的实际宿主或夹具注释。 |
| `AGENTS.md`、`README.md`、本记录 | 记录 PDF 开关长期约定、源码新功能、冲突处理及验证边界。 |

## 真机验收

在 MovinkPad Pro 14、Android 15、Pro Pen 3 上检查以下操作。

1. 落笔、旋转、调整分屏宽度、切换文档后继续书写，观察湿墨位置、首帧和提交后的笔迹是否一致。
2. 比较慢速画圈、快速转折和抬笔末端的防抖手感，检查速度笔线宽与保存重开后的外观。
3. 分别在分页笔记、PDF 批注页及 GL 画布取色，检查取样颜色、浮动放大镜及取消手势。
4. 用笔与手指移动和缩放截图，跨页、取消、添加导图引用，核对最终的页面与区域。
5. 重建 Activity 并恢复分屏，检查系统启动画面的日夜配色与旧保存交接。到真实 SAF 提供商验证导入、导出及原 PDF 字节保持。
