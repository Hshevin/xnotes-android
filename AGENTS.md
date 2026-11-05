# MovinkPad preview development

- 2026-11-05 用户要求再次拉取远端 master 并打包，授权本地交付 Release 91：包含已在远端主分支合入的图层、通用按键及共享 PDF 存储，沿用正式包名和签名、递增版本。不安装用户设备、不推送或发布 GitHub Release；设备与真实 SAF 迁移验收仍单独记录。

- 分支测试包后续更新沿用各自包名与签名，递增 `branchTestVersion`。ARM64 交付与 x86_64 模拟器构建除输出目录外，还需用 `--project-cache-dir` 隔离 Gradle 项目状态，避免切换构建目录后 DEX 增量缓存残留旧路径。

- 2026-11-05 用户随后授权将当前主分支打包为正式覆盖更新 Release 90，沿用 `com.xnotes.movinkpad` 和历史签名，递增 versionCode；只本地交付，不安装、不推送、不发布 GitHub Release。图层和按键录入分支保持独立。

- 2026-11-05 用户要求拉取远端主分支与新增功能分支，分别本地打包独立测试 APK；旧 AI/分屏专用测试包不再维护，可清理旧测试产物。各分支使用独立工作树、构建目录及 `com.xnotes.movinkpad.branchtest.<id>` 包名，保留原签名，不覆盖正式版；交付时报告包名和源码提交。保留用户未提交修改，不将不同功能分支擅自互相合并。本次不包含设备安装、推送或 GitHub Release。

- Read `MOVINKPAD.md` and `README.md` before changing this fork. Read `docs/movinkpad/project-brief.md` and check `git status` before work. This fork is self-contained; the separate diagnostic application is not a module here.
- Preserve upstream licenses and third-party notices. Automatically commit completed, validated work locally; push to GitHub only when explicitly requested.
- Target Wacom MovinkPad Pro 14, Android 15, Pro Pen 3; product reference remains OneNote Android tablet.
- Notes remain local/offline. Optional Ask AI is user-authorized network access: send only selected screenshots and the active chat after Send; no account, sync or app backend. Keep API credentials encrypted in noBackupFilesDir, never in note exports or logs.
- Keep physical button numbering `0x20`, `0x40`, `0x04`. Detect edges from touch and hover buttonState, deduplicate independent button events, never hardcode device IDs.
- `pen/ProPenButtons` is the pure state machine; `pen/ProPenInput` adapts Android events for both editor surfaces. Preserve cancelled strokes and tool settings; do not change tools midway through a stroke.
- Build with JDK 21 and this repository's wrapper/toolchain. Deliver ARM64 APK without injected IDE/testOnly flags.
- Run relevant unit and Android event tests; hardware acceptance must separately record device/system/pen details. Do not claim hardware compatibility from emulator results.
- If a Python environment is needed, create it with uv.

- UI is black and white only: paper, panels and menus are pure white #FFFFFF / black #000000, inverted in night mode, and selection is full inversion. Since 2026-11-03 the user allows neutral grey steps (r = g = b) for hierarchy (secondary text, hairlines, fills, backdrop); still no coloured accents, gradients or decorative shadows. Ink colours and PDFs are content, not chrome.
- Tool chrome drawn on the page (marquee, lasso, handles, caret, guides, text-box border) must contrast with the page, not follow the theme: read `CanvasState.palette` / `TextEditHost.overlayPalette`, which turn the accent black on PDF or light pages in night mode (user request 2026-11-03). The infinite canvas mixes black paper with white images in one view, so `OverlayTessellator` lays a 1 px opposite-tone edge under every mark. Its dark tone is (1, 1, 1), because night ink repaints exactly (0, 0, 0) as white.
- User-visible text goes into string resources, with English in `values/` and the same Simplified Chinese in `values-b+zh+Hans/` and `values-zh-rCN/`. AI UI strings live in `ai.xml`; `ai/` backend messages go through `AiText.t(AiMsg…)` and `ai_messages.xml`; `storage/` messages go through `StorageText.t(StorageMsg…)` and `storage_messages.xml`. Persisted keys (AI turn states, default chat title) and model-facing prompts stay unchanged and are translated only for display.
- Chrome text sizes and gaps come only from `ui/theme/Tokens.kt` (`TextSize`, `Space`); controls keep a 48 dp touch target (`MinTouchTarget`). Add a new token there rather than a literal. Details: `docs/movinkpad/ui-monochrome-tokens-2026-11-03.md`. Full visual and target-device acceptance remains pending.
- PDF source and read-only imported originals must remain unchanged; annotations and disposable display caches are separate. Large scanned-PDF and tool-cursor work is planned in `tasks/plan.md` and `tasks/todo.md`, not completed.
- Notes must persist locally. Verify recovery and failure paths for storage, import, deletion and migration changes.
- Preserve raw button masks and axes. Compare buttonState transitions on MOVE/HOVER_MOVE and deduplicate independent button events; distinguish stylus, finger and cancellation.
- Use JDK 21, Gradle wrapper 8.13, AGP 8.12.3, SDK/Build Tools 36/36.0.0, NDK 27.0.12077973 and CMake 3.22.1. Do not import the diagnostic project's AGP 8.12.0 setting.
- Preserve user changes; automatically commit only changes related to the completed task after relevant validation. Do not include unrelated user changes. No push, remote or Git configuration changes unless explicitly requested. Temporary files belong in ignored `tmp/`, not source or permanent documentation.

- 用户于 2026-10-31 最新要求本次直接在本机编译，覆盖此前“不在本机编译”的约定；使用 JDK 21，遵守执行环境权限。
- 用户于 2026-11-03 要求：每完成一个功能阶段或修复并通过相关验证后，自动提交到本地 Git，无需再次询问；推送 GitHub 仍由用户手动操作或明确要求后执行。此约定覆盖此前“未经要求不提交”的限制，不授权自动推送、打包或发布。

## 文档维护

- 2026-11-03 用户要求合并分屏与主分支；本地 `master` 已快进合入 `codex/split-workspace`，不再维持主分支仅含分屏前功能的边界。原分屏分支保留；未经要求仍不推送。

- 每完成一个功能阶段、修复一组问题或准备交付时，检查并按需同步 `AGENTS.md` 与 `README.md`；长期任务在阶段完成时同步，不积压到最终交付。
- `AGENTS.md` 记录长期有效的协作约定、开发边界、工具链和验证要求；`README.md` 面向用户，保留功能、安装、使用和构建/测试入口，区分正式版与测试版。版本更新、开发历史和验收细节统一记录在 `docs/movinkpad/`。
- 详细变更和验收证据放入 `docs/`，入口文档保持简洁；明确区分已实现、已验证、待真机验证及讨论中方案，无变化时不为更新而改写。
- 外层工作区与 `xnotes/` 独立仓库各自维护对应说明；原型功能以 `xnotes/` 为准，涉及工作区入口或仓库分工时同步外层文档。

## 覆盖安装与 Release

- 2026-11-03 用户在本服务器提供原签名密钥，要求 Debug 与 Release 共用。通过忽略提交的 `keystore.properties` 配置两种构建；不要覆盖服务器默认调试密钥、提交密钥或密码。证书 SHA-256 为 `A0:45:3B:7B:E6:C0:C8:23:9E:86:16:20:86:12:C4:12:CF:50:1D:A8:89:8C:7A:04:50:CC:92:3C:8E:35:84:51`。此要求不改变各构建的包名，也不授权本轮打包或安装。

- 用户于 2026-11-02 确认当前分屏版本足够，授权打包覆盖早期 XNotes 的 Release；交付包使用 `com.xnotes.movinkpad`，独立测试包仍为 `com.xnotes.movinkpad.splitpreview`。不把打包授权扩展为 GitHub 推送或商店发布。
- 覆盖升级必须沿用已交付 APK 的签名、提高正式包 versionCode，核对签名与安装身份。当前通过 `-PmovinkpadPreviewSigning=true` 显式沿用历史本机 Debug 密钥；Release 仍启用 R8、资源压缩并关闭 debuggable。不要重新生成密钥或要求用户卸载旧版。

- 主菜单打开普通文档必须进入单文档模式；窗格内首页打开文件只替换所在窗格；只有显式打开/恢复分屏才恢复双窗格。替换工作区前等待保存完成，避免异步关闭回调误关新文档；未打开成功不得因预检查失败丢弃当前工作区。

- 2026-11-02 用户要求本轮退出分屏后的实时笔迹修复不要直接打包 APK；只修改与验证代码，后续交付构建需用户另行要求。涉及前缓冲/Surface 重挂的显示修复必须区分源码验证与目标 GPU 真机验证。

- 分屏文档的关闭/返回先显示该窗格的文件选择首页；关闭该首页才移除窗格。暂留文档通过已有保存/放弃保护处理替换与最终关闭；同文件共享视图关闭拥有者时，保留另一侧首页的物理位置。文件树只为已确认存在子文件夹的节点显示展开箭头。

- 用户随后于 2026-11-02 明确授权将上述修改打包并发布 APK，覆盖本轮先不打包约定。Release 80 沿用原应用身份和签名；发布说明必须保留实时笔迹修复尚待真机确认的边界。

- Ask AI development (2026-11-03): do not package APKs unless requested again. PDF, paged notes and infinite canvas screenshot actions must all support Ask AI. Real provider and target-device verification remain separate from JVM tests.

- 用户于 2026-11-03 随后授权导出 AI 独立测试 APK，使用 `-PaiPreview=true`，包名 `com.xnotes.movinkpad.aipreview`，不得覆盖正式版或双窗格测试版。此授权不包含提交、推送或设备安装。

- DeepSeek 联网搜索默认关闭，仅随显式发送执行。搜索密钥独立加密保存，只发送到官方 `https://api.deepseek.com/v1/tools/search` 与 `/v1/tools/fetch`（网页由 DeepSeek 服务端抓取，设备不直接访问模型给出的 URL），不得把其他聊天供应商密钥转发给 DeepSeek。搜索失败需明确反馈，不静默降级；网页资料为不可信上下文，保留来源。

- AI 流式正文须读取原始 JSON 类型，仅接受 String；禁止用 optString 强制转换 content。Android org.json 与 JVM 测试依赖对 JSONObject.NULL 的处理不同，此类回归需保留平台仪器测试。

- 用户可见的功能名称统一为“AI”，截图入口、浮窗和设置不再使用“问 AI”。

- AI 会话任务由应用级 AiSessions 管理，UI 关闭、页面切换、新会话不得取消生成；只有显式停止或确认删除才取消。生成状态与回答写入原会话，删除后迟到回调不得复活记录。2026-11-04 起删除即移到回收站（记录带 `trashedAt` 留在磁盘，与历史分开），在回收站恢复或永久删除，沿用笔记回收站的保留天数；回收站关闭（0 天）时与笔记一样直接永久删除。不再提供 8 秒撤销。
- AI 历史仅在 noBackupFilesDir 本地保存，密钥与历史分离。原子写入失败保留已有文件；损坏记录保留并提示。进程重启将生成中记录标为中断，不冒充完成；仪器测试与真机验证不得由 JVM 结果替代。

- AI 设置（2026-11-03）：模型列表仅在用户点击「从供应商获取」时向所配置供应商 `/models` 发送请求；自定义提示词与思考深度只用于回答请求（联网工具在回答请求内调用，同样使用会话设置），PDF 工具步骤使用 `AiConfig.forTools()`。会话内的模型/深度选择存于会话记录，缺省跟随默认。上限（如 200 个模型）须在添加时拒绝并提示，保存时校验失败，不得静默截断。本轮不打包 APK。

- 多模型供应商（2026-11-05，参照 DeepSeek Harness）：每个供应商有永久、不显示的内部 ID，聊天记录与默认值以「供应商 ID + 模型」引用；不要改成按名称或 URL 引用。旧单供应商配置迁移为固定 ID `default`，保存时顶层字段仍写默认供应商以便降级读取。供应商被删除的会话不得回退到其他供应商发送，须显示「选择模型」并拒绝发送。仅支持 OpenAI Chat Completions。详见 `docs/movinkpad/ai-providers-2026-11-05.md`。

- 联网搜索仅保留开关，不让用户填写检索词。2026-11-04 起按用户要求参照 DeepSeek Harness：开关打开时向回答模型提供原生 `web_search` / `web_fetch` 工具，由模型按需调用（有轮数与数量上限）；模型或接口不支持工具调用（请求被 400/422 拒绝）时不联网直接回答，并在该轮明确标注，不再回退到单独的检索词识别。网页资料以工具结果返回、标为不可信数据，不当指令或替代问题。

- AI PDF attachments: only explicitly selected PDFs are snapshotted under noBackupFilesDir; model reads are restricted to attachment IDs in the active chat. Never expose arbitrary filesystem access. PDF text/images go to the configured provider only after Send. Keep original PDFs unchanged; UI closure must not cancel app-owned generation.

- AI chat interaction (2026-11-03): drafts and ready attachments belong to a chat, not its window. Preserve per-chat search selection, keep imports running on navigation, and guard late import callbacks after deletion/replacement. Clear a submitted draft only after its question is saved, without erasing newer input. Generation progress is factual stage text, not invented percentages. This interaction stage is code/test only; do not package APKs without a new request.

- AI 未开始对话的输入区（2026-11-04）：关闭窗口或点击「新会话」时，只要尚无任何对话轮次且未在发送，就清空提示词、截图、附件及其导入任务，不留下或累积空白历史会话；不能用提示词是否为空判断。已有对话保留草稿与附件，关闭/新会话仍不取消生成。清空后必须拒绝旧文件选择器与附件导入的迟到结果。

- Document registry migration (2026-11-03, in progress): preserve legacy ink and original PDF payload bytes during import/format upgrade; this is not authorization to change ordinary stroke simplification. Migration must stage and verify before publication, never reuse model re-encoding to upgrade metadata. Keep document UUID separate from resource fingerprints. Registry/SAF recovery and ID-based split restoration are integrated in source, with device acceptance pending. Missing/duplicate IDs require explicit recovery, never arbitrary selection. Shared immutable PDF objects use indexed references, active leases and delayed collection; portable note bundles retain their embedded original. No APK/install/push for this stage; the user subsequently authorized local integration into master.
- 2026-11-03 用户授权将黑白层级与字号、间距规范打包为 Release 81（本地交付，同一身份和签名）；不包括推送或 GitHub Release。Release 构建的 buildDirectory 放到 iCloud 同步目录之外（见 Release 记录），避免出现 `… 2.*` 重复文件。

- 2026-11-03：插入空白在分页文档（笔记/PDF）分屏时属于顶部共用工具；工作区有画布时移至分页文档名称栏，画布不显示。布局按打开的文档类型分配，不随焦点切换。本轮只实现与验证，不打包 APK。

- 新增工具栏按钮必须在 `core/tools/ToolbarLayout.kt` 的穷尽分类中声明支持的文档类型及是否为独立文档设置，不允许给未知按钮兜底为通用。编辑工具在所有打开文档都支持时放共用栏，否则仅放支持它的文档栏；缩放/导航/视图/样式等独立状态始终留在文档栏。不要按焦点动态增删顶栏。添加/修改分类时补齐笔记、PDF、画布组合及不重复显示测试。

- 2026-11-03：用户要求将 AI 工作树合入主工作区，保留主分支已有 UI（灰阶、字号/间距 tokens、工具栏分配）；本轮不开始 UI 大改，不打包或推送。

- 用户随后于 2026-11-03 授权导出 AI preview3 覆盖测试包：`com.xnotes.movinkpad.aipreview` / versionCode 3，沿用 preview2 签名；不覆盖正式版，不包含设备安装或推送授权。

- 用户随后于 2026-11-03 授权制作正式 Release 82：包名 `com.xnotes.movinkpad`、versionCode 82，沿用正式版历史签名，启用 R8/资源压缩且关闭 debuggable。仅本地交付，不安装、不推送或发布 GitHub Release；继续保留现有 UI。

- 2026-11-03 用户在 UI 与翻译大改后授权代码检查并构建可覆盖安装的正式 Release 83；沿用 `com.xnotes.movinkpad` 和历史签名。仅本地交付，不包含真机安装、推送或 GitHub Release。

- 2026-11-03 用户在两轮提交核对后授权打包正式 Release 84，包含 AI 设置、新会话默认、默认提示词与侧栏修复；沿用 `com.xnotes.movinkpad` 和历史签名，仅本地交付，不安装、不推送或发布 GitHub Release。

- 用户随后于 2026-11-03 授权推送 `master` 并发布 GitHub Release 84（标签 `v0.9.7-movinkpad.84`）。此授权仅限本次，后续推送和发布仍需明确要求。

- 用户于 2026-11-04 授权本地打包正式 Release 85，包含退出 PDF 分屏的前缓冲调整及 AI 未开始对话输入区清理；沿用 `com.xnotes.movinkpad` 和历史签名，提高 versionCode 以支持覆盖安装。仅本地交付，不包含设备安装、推送或 GitHub Release；前缓冲效果及平台交互仍待目标设备验证。

- 用户随后于 2026-11-04 授权打包交付 Release 86，用于测试湿墨层生命周期候选修复；沿用正式包名和历史签名、递增 versionCode。仅本地交付，不安装用户设备、不推送或发布 GitHub Release；尚未确认候选修复消除目标设备半屏刷新现象。

- Release 86 真机反馈无改善后，用户于 2026-11-04 授权打包 Release 87，测试分页编辑器重挂时替换湿墨 View 的下一候选；沿用正式身份与签名，仅本地交付 APK，目标设备效果仍待确认，不扩大为 GitHub 发布授权。

- 用户随后于 2026-11-04 确认 Release 87 解决原 PDF 退出空分屏后的半屏实时笔迹问题。分页编辑器跨 AndroidView 宿主重挂时必须通过 `Editor.surfacesForAttach()` 替换 GlWetPad/FrontInk，先归还暂存笔迹并断开旧回调，保留文档、普通画布、缓存及前缓冲开关；未经目标设备回归不要恢复复用旧湿墨 View。具体 RenderNode/crop 残留机制仍为推断，不将本次用户反馈扩大为全部场景或其他设备验收。详见 `docs/movinkpad/split-wet-ink-2026-11-04.md`。

- 用户于 2026-11-04 在远端 AI 更新合并后授权本地打包 Release 88 测试；沿用正式包名及历史签名、递增 versionCode，保留 Release 87 湿墨修复。仅本地交付，不安装、不推送或发布 GitHub Release；多截图、浮窗历史及原生联网工具仍需真实供应商和目标设备验收。

- 用户随后于 2026-11-04 再次授权拉取远端并构建 Release 89，包含 AI 回收站及搜索栏更新；沿用正式身份与历史签名，仅本地交付，不安装、不推送或发布 GitHub Release。

- 2026-11-05 用户要求 PDF 日常只保存一份：分页笔记的内部 `.xnote` 使用身份元数据 v2 引用私有不可变 PDF 对象；分享／导出恢复为嵌入原 PDF 的便携包（身份 v1）。旧包按需打开迁移，先校验并持久保护原件，再逐条保留非 PDF 负载并安全发布；禁止经模型重新编码迁移笔迹。内部笔记单独复制不构成备份，完整备份必须经应用导出。共享原件不可由缩略图、合并或导出临时清理删除。此阶段不打包 APK、不安装、不推送；平台及目标设备验收仍待执行。详见 `docs/movinkpad/pdf-single-copy-2026-11-05.md`。

- 2026-11-05 用户授权通用手写笔按键录入：以实际 MotionEvent / KeyEvent 信号录入并绑定工具或操作，旧 `pro_pen_bindings` 不迁移、不读取，新 `pen_shortcuts` 从空列表开始。Pro Pen 3 的实测原始位仍为 `0x20`、`0x40`、`0x04`；`ProPenButtons` 运行时接收映射槽位，原始事件不改写。持久化使用设备描述符和事件身份，不持久化临时 deviceId。录入仅在设置页显式启动，不添加全局无障碍监听。用户授权本功能新建远端分支并推送；随后于 2026-11-05 确认合入本地 master，继续不打包 APK，本次合并不包含主分支推送。

- 通用按键映射复查（2026-11-05）：所有窗格交接（标题、触摸、悬停及关闭）均传递带输入身份和有效 HOLD 顺序的快照；继承的 SELECT 和已被手动工具选择覆盖的 HOLD 不得重触发。View 重挂与窗口失焦分开处理。笔画中的按钮转换仅在正常抬笔后提交，取消整笔丢弃全部转换，取消某键仅丢弃该次按压。录入时每种事件类型最多接受一个候选，禁止把多个实体键静默合并。

- 临时按住工具（2026-11-05）：HOLD 仅改变实际输入工具，工具栏高亮与切换动画使用独立的正式选择状态；SELECT 与手动选工具仍更新正式选择。分页、画布及共用工具栏遵循同一规则，不得为了稳定工具栏而取消实际临时功能或在笔画中切换工具。

- 分屏工具栏焦点（2026-11-05）：文档栏的编辑工具选中高亮仅在该窗格有焦点时显示；失焦后隐藏高亮及滑动标记，不修改后台工具状态。缩放锁定等独立文档设置仍显示各自状态，共用工具栏跟随当前焦点。

- 图层第一版（2026-11-05，`drawing-layers` 开发分支）：分页/PDF 按页、无限画布按文档管理 DrawingLayers。图层 ID 与对象归属必须在删除、移动、跨页转移、橡皮碎片和撤销重做中保持一致；保存快照冻结成员关系，不能读取后续编辑过的 layerId。默认单层沿用历史序列化形式；原 PDF/模板、PDF 文字标注和跨页排版正文仍为独立背景/排版内容。多层实时笔迹通过层序合成，不能直接放在顶层前缓冲；保留用户前缓冲设置及 Release 87 的湿墨 View 重挂规则。源码验证不能替代真机 GPU、视觉与性能验收。本次仅代码验证；用户授权将 `drawing-layers` 推送远端，不授权打包、安装或发布 Release。

- 图层交互（2026-11-05）：用户要求以绘画软件式侧栏为主要入口，不能恢复为图层列表弹窗。分页/PDF 放入文档侧栏的图层页签，无限画布使用同类常驻侧栏；拖动把手排序，落下形成一次撤销，取消不改模型。选择当前层不占用内容撤销记录；面板跟随当前页面/文档和撤销更新。

- 2026-11-05 图层交互参考 Krita；本阶段仅侧栏与缩略图，不扩展分组/透明度/混合模式。缩略图只显示单层自身内容，隐藏层仍有预览；PDF/纸张背景不混入。异步渲染只读取几何快照，翻页/关闭/版本变化须取消旧任务，预览缓存不得写入笔记格式。

- 2026-11-05 图层缩略图和名称区域均按下后移动即可排序（保留系统移动阈值防误触），不得要求先长按；点击选层、双击名称重命名继续保留。

- 2026-11-05 缩略图按显示范围切换：分页笔记/PDF 为不显示、已有笔记区域、整页笔记；无限画布仅不显示和已有笔记区域，不提供整页模式。内容模式按单层内容边界裁切，不显示时不计算预览边界、不生成位图；显示设置不进入文档撤销栈。

- 2026-11-05 用户授权将 drawing-layers 合入本地 master，包含图层侧栏、直接拖拽和缩略图范围模式；保留主分支单份 PDF、通用按键及焦点高亮逻辑。不包含打包、安装或推送授权。
