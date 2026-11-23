# 早期开发记录

This fork targets Wacom MovinkPad Pro 14 and Pro Pen 3. Three-button mappings and a black/white UI palette are implemented in the preview; target-device acceptance is pending.

- [Preview scope and existing validation](../../MOVINKPAD.md)
- [Project brief](project-brief.md)
- [Linux build and repository structure](process/server-build.md)
- [Workspace switching and consistent pen settings](editor/workspace-switch-2026-10-31.md)
- [Branch split and upstream sync](process/github-sync-2026-11-02.md)
- [Remote AI updates merged with Release 87 fixes](process/remote-merge-2026-11-04.md)
- [Black/white hierarchy, type and spacing tokens](ui/ui-monochrome-tokens-2026-11-03.md)
- [Planned PDF and cursor work](../../tasks/plan.md)

## AI 第一版（2026-11-03）

源码已合入主工作区，保留 Release 81 的灰阶、字号/间距 tokens 和工具栏布局。尚未开始下一轮 UI 调整；用户随后授权导出 AI preview3 测试 APK，包含本轮合并后的源码。

由独立工作树 `ask-ai` 合入本地主分支的功能包括 PDF、分页笔记及无限画布截图菜单的「AI」。设置中填写 HTTPS 供应商地址、支持图片的模型和 API Key；支持应用内拖动/收起浮窗、流式回答、追问、停止和文本选择复制。当前源码已加入左侧 AI 历史：本地保存、搜索、继续、重命名，删除后移到回收站，可恢复或永久删除（[记录](ai/ai-trash-2026-11-04.md)）。关闭浮窗、切换文档或新会话不取消生成；主动停止或确认删除才取消。进程结束后恢复最近保存的内容，并标记中断。

本分支新增可选网络功能。笔记仍离线保存，只有主动发送才上传选区及当前会话。已导出 AI 独立测试 APK（preview3，原本地文件 `XNotes-0.9.7-ai-preview3-arm64.apk`），包名 `com.xnotes.movinkpad.aipreview`，可与 Release 并存。使用 `-PaiPreview=true :app:assembleDebug` 构建；真实接口、密钥存储故障恢复及平板交互尚待验收。详见 [实现与验证](ai/ask-ai-2026-11-03.md)。

当前源码新增默认关闭的「联网搜索 · DeepSeek」开关：设置独立搜索密钥，开启后自动结合截图、当前问题和对话识别检索对象，再搜索和回答，显示来源；无需填写搜索词。此前 **preview2 / versionCode 2** 已包含搜索开关、null 修复、AI 命名和历史会话功能，可覆盖旧 AI 测试版并保留其设置，不覆盖正式 Release。详见实现记录。

另已修复流式回答前出现连续 `null` 的解析问题：仅追加字符串类型的正文。修复已包含在 preview2 APK。

最新源码将主菜单 AI 改为完整聊天页；文档内保留浮窗，退出来源文档时自动关闭，生成继续保存。支持可选「附加当前 PDF」、选择/粘贴本机 PDF；附件保存本地快照，模型按需读取文字、搜索页码范围或查看单页图片，读取结果参与联网检索。当前单文件上限 200 MB，不再限制 30 页；真实模型与大 PDF 性能待验收。

聊天交互源码已完善：宽屏历史与聊天并排，窄屏切换；各会话草稿文字、截图、PDF 引用和联网开关本地恢复。附件准备跨页面继续，支持失败重试/取消；显示读页、检索及回答阶段，过程与来源可折叠。生成时可写下一条草稿，阅读旧消息不强制滚底；已包含在 preview3，界面与目标设备验证待完成。

AI 回复现已接入原生 Markdown 显示：标题、强调、列表、引用、代码块、表格与可点击链接；支持选中文字、复制代码和复制原始 Markdown。流式解析在后台执行，不加载远程图片或执行 HTML；本次渲染更新随 Release 83 本地交付。

AI 界面已重做（随 Release 83 本地交付）：右侧问题气泡；输入区合成一张卡片（＋附件、联网开关、圆形发送/停止）；浮窗用图标标题栏，默认停在右侧；历史每条两行，操作收进 ⋮。界面和后端提示均有英文与简体中文。同时加深了夜间灰阶层级；PDF 页面上的工具（框选、套索、截图框、控制点、文本框边框等）在夜间改为黑色；无限画布上的工具标记加了 1 像素反色底边，在黑纸和白色图片上都看得清。存储层错误提示也已双语。真机验收待完成。见[记录](ai/ai-ui-dark-i18n-2026-11-03.md)。

Release 84 已本地交付 AI 设置扩展：可保存多个模型并从供应商获取列表、设默认思考深度与参数格式、最大输出长度和自定义提示词；输入栏可为单个会话切换模型与思考深度；可设置新会话默认（模型、思考、联网、自动附加当前 PDF），并内置中英文默认提示词。模型列表最多 200 个，超出时明确提示。浮窗会记住上次拖动的位置。另修复了分屏后主页左上角箭头无法收起/展开侧栏的问题。真实接口与真机验收待完成，见[记录](ai/ai-settings-2026-11-03.md)。

2026-11-04 调整：尚未开始对话时，关闭 AI 窗口或点击「新会话」会清空提示词、截图和附件，不累积空白历史；已有对话继续保留草稿和附件。已随 Release 85 本地交付，设备交互待验收，见[记录](ai/ai-empty-chat-2026-11-04.md)。

当前源码（未打包）：AI 浮窗收起时宽度不变；浮窗内可直接截图，每个问题最多附带 6 张，可在后续追问中继续添加；浮窗内可查看并切换本文档的历史会话。从浮窗进入截图模式后可在任意窗格框选，分屏时两个窗格的截图都可加入当前会话，会话归属最先截图的文档，并同时出现在另一文档的浮窗历史中。旧的单图记录仍可读取。真机验收待完成，见[记录](ai/ai-floating-screenshots-2026-11-04.md)。

当前源码（未打包）：联网搜索改为参照 DeepSeek Harness 的原生工具调用：开关打开后，模型自行决定是否调用 `web_search`（一次可给多个检索词）和 `web_fetch`（经 DeepSeek 服务端读取网页全文），不再先单独识别检索对象。不支持工具调用的模型不联网直接回答，并在回答下注明。真实供应商与真机验收待完成，见[记录](ai/ai-web-tools-2026-11-04.md)。

当前源码（未打包）：参照 DeepSeek Harness 支持多个模型供应商。设置中按供应商分别填写名称、URL、Key、模型与思考参数格式（可从预设开始、从供应商获取模型）；输入栏模型菜单按供应商分组，会话记录所选供应商；旧单供应商配置自动迁移。真实供应商与真机验收待完成，见[记录](ai/ai-providers-2026-11-05.md)。

此前 preview3 修复截图论文搜索跑偏路径：自动识图生成检索词，参考资料与原问题分离；当前源码已改用上述原生联网工具流程。

Release 83 本地交付本轮 UI、Markdown 和中英文资源改动，并修复修改纸色后页面工具颜色缓存未刷新、Pro Pen 3 设置缺少 Hans 翻译。保留原包名和签名；代码检查、测试和 APK 核验见 [Release 记录](releases/release-2026-11-02.md#release-83ui翻译与代码检查2026-11-03)。目标设备与真实供应商验收仍待完成。

## 文档登记兼容层（开发中）

源码已接入独立 ZIP 文档 UUID、保留旧负载的迁移与恢复、SQLite 位置/资源登记、共享 PDF 对象，以及按 ID 恢复分屏和失联占位。AI 历史、草稿、撤销和生成任务参与资源引用/租约保护。便携笔记包仍嵌入原 PDF；真实 SQLite、SAF 故障恢复和平板交互待设备验收，已包含在 preview3。见[实施与验证记录](storage/document-registry-2026-11-03.md)。旧笔画保真要求仅针对旧格式导入/升级，日常笔画处理逻辑保持不变。

## 当前开发状态（2026-11-03）

- 本地 `master` 已快进合入 `codex/split-workspace`，包含分屏功能及上游 0.9.7；原分屏分支保留，尚未推送此次合并。见上面的分支同步记录。
- 分屏功能基线为 **0.9.7-split-preview10 / versionCode 77**：支持笔记、PDF、画布分屏、应用内分屏文件管理、默认新分屏位置和重复保存去重。详见 [分屏开发与验收记录](test-builds/split-preview-2026-11-02.md)。
- 测试包名 `com.xnotes.movinkpad.splitpreview`，与正式包名 `com.xnotes.movinkpad` 分开安装。用户已确认该版本满足当前需求，并要求打包原应用覆盖升级版；当前维护入口为 `master`。
- 本轮验证：2462 项 JVM 测试、39 项 Android 模拟器测试通过。测试数量是本次记录，不代表后续版本已验证。

本地 Release 89 ARM64 APK（`XNotes-0.9.7-movinkpad-release89-arm64.apk`）。沿用正式包名及 Release 88 签名，versionCode 89，可覆盖升级；新增 AI 回收站、浮窗历史删除及搜索栏布局修复，保留 Release 88 AI 功能和 Release 87 湿墨修复。2591 项 Release JVM 测试、Android 测试源码编译及 APK 身份、签名、对齐核验通过；本次未执行设备安装或仪器测试，新增 AI 行为仍待真实供应商和目标设备验收。详见 [Release 记录](releases/release-2026-11-02.md#release-89ai-回收站与搜索栏2026-11-04)。已发布为 [GitHub Release 89](https://github.com/Hshevin/xnotes-android/releases/tag/v0.9.7-movinkpad.89)（标签 `v0.9.7-movinkpad.89`，附 APK 与 SHA256SUMS）。

此前 Release 85 为 **0.9.7-movinkpad / versionCode 85**，使用原应用身份和历史签名，包含退出 PDF 分屏的湿墨层调整及 AI 未开始对话输入区清理；2563 项 Release 单元测试通过，R8、资源压缩、lintVital 与 APK 身份/签名核验通过。本轮未执行设备安装或 Android 仪器测试，前缓冲实际显示、真实 AI 供应商及目标设备验收仍待完成。交付与验证见 [Release 记录](releases/release-2026-11-02.md)。测试版的数据不会自动迁入原应用。版本 79 修复返回主菜单后打开普通文件仍保留分屏的问题，普通文件进入单文档，显式恢复分屏不受影响。

构建覆盖升级 Release（须保留本机原签名密钥）：

本服务器已支持 Debug 与 Release 共用签名：在忽略提交的 `keystore.properties` 配置 `storeFile`（相对 `app/` 的路径）、`storePassword`、`keyAlias` 和 `keyPassword`，两种构建都会使用该密钥；未配置时保留原构建行为。密钥与密码不提交到 Git。正式发布前仍需核对包名、versionCode 和证书；共用签名不改变独立测试包与正式包的身份。

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./gradlew --no-daemon -PmovinkpadPreviewSigning=true :app:testReleaseUnitTest :app:assembleRelease
```

构建独立分屏测试版（JDK 21）：

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./gradlew --no-daemon -PsplitPreview=true :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

Release 80 包含实时笔迹刷新调整、分屏关闭先回到窗格首页再退出，以及文件树叶子文件夹箭头修复。前缓冲显示和性能仍待目标设备验证；发布结果见 Release 记录。

2026-11-04 用户反馈 Release 85 仍存在退出 PDF 空分屏后半边笔迹要等提笔才显示的问题。禁用前缓冲后正常，随后重新开启也曾恢复正常；具体根因尚未确认。已按用户要求转为先调查、确认后再修复，未采用新的整层重建方案。见[调查与验证记录](rendering/split-wet-ink-2026-11-04.md)。

随后已在新建 Android 15 模拟器实走 PDF 空分屏打开/退出，确认父 SurfaceView 层随拆挂重建；模拟器缺少 mutable render buffer 配置，未进入实际前缓冲，尚未复现半屏故障。此次仅记录调查证据，没有新的产品修复或正式交付包。

用户随后授权按现有推断尝试修复：当前源码已串行化湿墨层销毁/重建，阻止旧渲染线程和迟到回调访问新一代资源。2563 项 JVM、49 项 Android 模拟器测试通过（含强制慢线程重挂测试）；属于待真机确认的候选修复，随后已按用户要求随 Release 86 本地交付。详见上述调查记录。

Release 86 真机反馈：原空窗格区域仍需提笔才显示，症状完全一致。下一候选在分页编辑器重挂时替换整个湿墨 View，保留文档、已有笔迹、页面缓存与前缓冲开关；随后随 Release 87 本地交付，用户已确认原报告场景解决；底层裁剪状态残留仍属推断，其他场景验收不据此扩大。

Release 81（2026-11-03，仅本地打包）加入黑白灰阶层级、统一字号和间距规范，以及 48 dp 触控区。已知在 1280 dp 宽的分屏下工具栏会溢出到滚动区；真机验收待执行。

工具栏调整统一按文档能力分配：插入空白在笔记/PDF 工作区置顶；PDF 文本标注在全 PDF 工作区置顶；混合不支持的文档时下放对应文档栏。缩放/导航/视图等独立设置保持在文档栏。新按钮必须显式分类。本轮随 Release 82 交付。

## 早期构建版本

### MovinkPad Release 候选版（2026-11-02）

Debug 与 Release 均使用 com.xnotes.movinkpad。当前 Release 候选为 0.9.6-movinkpad-rc1 / versionCode 68，可通过显式-PmovinkpadPreviewSigning=true 复用本机原 Debug 签名构建，以覆盖此前版本 67。这不是新的生产密钥；跨机器必须核对签名，不能假定默认 Debug 密钥一致。构建、覆盖验证与限制见 [Release 候选记录](releases/release-candidate-2026-11-02.md)。

### 独立双窗格测试版（2026-11-02）

使用 `-PsplitPreview=true` 构建时启用独立包名 `com.xnotes.movinkpad.splitpreview`，可与正式版并存；不传此属性保持既有包名。支持 PDF 笔记／普通笔记／无限画布混合并排，同一分页笔记可拆为两个独立阅读位置并共享标注与撤销。当前为 ARM64 Debug 测试版，尚未正式发布。

第二测试版新增直接选文件分屏、公共工具栏、单侧关闭、分隔线触控反馈与中心吸附，并修复共享文档缓存闪烁及四指手势识别。

第三测试版支持从当前文件夹开始逐层浏览与切换已授权位置，焦点标题改用名称下划线；图片／音频仍插入焦点文档。

第四测试版修复橡皮悬浮跨分割线与侧键持有交接；笔悬浮进入另一侧即同步工具并更新焦点。

第五测试版固定顶部通用工具栏，文档专属功能放在各自名称栏；支持长按名称拖动换位、保存最近分屏组合，以及主菜单往返。

使用方法、构建与验收见 [双窗格测试版记录](test-builds/split-preview-2026-11-02.md)。
