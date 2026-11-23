# 编辑器同源打开与采用（2026-11-08）

用户要求：按新渲染架构继续思维导图和文件关系网络，保留已有进度、可靠来源身份和恢复路径；允许编译/测试，不打包 APK。
状态：R2b2b2b 已实现；294 套件 / 3200 项主机测试通过，Android 测试源码编译成功，独立复核通过；真实平台/目标设备未运行。
本轮边界：源码、验证、文档、本地提交；不打包、安装、推送、合并或发布。

## 现状与阶段依赖

NativeSourceSnapshot/Version、WorkingSessionCache、PageReferenceCopy 与 NativeCreation 已具备验证基础，但 Editor 仍 registerForOpen 后另从 URI 直接 decode。读取身份和实际模型可来自不同字节；分页/PDF 回调的 managed PDF 没有显式读取→采用租约，canvas 打开也缺少取消结果清理。实际 SessionStore 仍是旧正文+sidecar，尚未接事务组件，不能据此声称来源恢复/另存流程安全。

下一片先把真实打开路径接到同源快照，不同时猜定旧缓存、放弃或另存采用的产品语义。此片不会把普通保存改成预期版本写入；版本凭据只是为之后的 native/cache 接线建立真实来源，不从 FileStamp 或旧 JSON 签发。

## R2b2b2b 输入、输出与所有权

- registerForOpen 继续处理已有迁移、PDF资源与重复身份；成功后用 NotebookStorage.openSource 在 IO 得到真实完整快照，从该快照 decode，不再从原 URI decode。来源种类、UUID、marker、page集合和实际 PDF SHA 与 source.verify 一致才允许采用。中途原URI变化不把不同版本拼接成一个模型；本片返回的版本始终属于所读快照，不承诺之后来源未变。
- 新增 PreparedOpen 负责同源 decode/verify 与未采用资产/租约所有权。source.verify 只是身份契约，不能证明任意外部 model 的正文来自该 source；因此绑定签发限定在 PreparedOpen 内部实际调用原 codec 得到模型的私有路径，不提供 forLoaded(source, arbitraryModel)。新的内部 NativeSourceBinding（名称可按实现调整）只可从此路径的 source 与已 verify 模型建立，绑定模型 owner token、UUID、kind 与真实版本。对另一活模型、换 UUID、错种类或迟到上下文拒绝，不提供 JSON/任意 tuple 恢复构造。writer snapshot 可以携带相同 owner 身份，但绑定采用只针对实际读出的模型。以后保存回执的更新与会话恢复另设入口，不能拿此片接口为旧缓存背书。
- 分页嵌入 PDF 继续使用 source.admitOriginalPdf，shared external PDF 由读取租约保护；不要重新 admit/删除受管原件。离开 source.use 前取得独立 handoff lease，IO→主线程期间持续保护。确认模型将被采用前在 IO 建立持久 note pin，再释放 handoff。失败/取消只清理由本次 decode 创建且尚未采用的图片、音频、模板与非受管 PDF，不删除源文件或共享 PDF；codec 已负责 decode 抛错前的临时文件。
- openSingleFromHome 当前 peek 后先 detachWorkspaceForOpen/abandonSecondary，再 ensure/decode；本片必须改成 PreparedOpen 完整成功之后才执行保存保护、同步 detach 和采用。peek 不能代替完整验证，也不能先关闭旧双窗格。窗格内打开同样复用准备/采用流程，保留共享拥有者与物理窗格位置。
- 读取与采用之间若取消、当前打开请求已失效、另一文档已替换或保存保护未完成，不能替换工作区；释放 handoff 和 transient 模型。第一次失败不丢弃已有文档、选择或双窗格。保留主线现有 View/湿墨层替换，不改 renderer/GL/front buffer。
- 分页原有 opening 防重复机制保留，笔记与画布的打开要使用同一个可取消请求代次，不能一个迟到任务关掉另一任务的 opening 状态。canvas现无opening gate，统一简单OpenRequest/代次是必要的；忙时忽略第二次打开，全部替换/关闭使旧请求失效，自身采用通过显式request提交避免误取消。withContext在IO产出后返回派发取消也可能不交付result，必须有IO外可见的资源拥有者和try/finally回收，不能只在拿到doc后检查；不能把普通窗口失焦当取消内容。
- 显式Discard授权从实际按钮回调捕获具体owner/model及bodyRevision，不能只传Editor集合；读取期间新增内容使旧授权失效。对在途或已产生待恢复记录的保存不借本片决定“放弃已写入版本”的含义，仍保留并阻止此次替换（相关用户问题尚待答复）。
- PreparedOpen为本次decode建立独占临时资产子目录，失败/取消可完整清理该目录及未用资产；采用后由活模型继续使用。启动清理仅递归识别本组件严格命名的资产目录，不删除共享PDF或任意目录。
- 为这次采用记录的 source binding 在 replaceDocument/openCanvasDocument 的实际模型上生效，不绑定窗格标题或 URI 字符串。共享笔记以 documentOwner 访问；旧模型在途结果不能给新模型换版本。旧 session restore、新建、导入、fork/merge 不能自动继承该打开凭据。

## 验收

- 同一次真实源快照 decode/verify/Binding，原 URI 之后同 UUID 改动仍不影响已读模型与版本的配对；不同 UUID/marker/page/PDF 型不匹配拒绝。
- 绑定拒绝其他相同 UUID 的独立模型、错 kind、换 UUID；创建/恢复旧缓存不能借路径制造绑定。
- 取消、decode失败、保存保护失败（含decode期间旧分页新增内容后写失败、双窗格先保存一侧等待另一侧又变脏）、IO之后/采用之前失效时，旧工作区保留，本次临时资产/租约释放，managed原件保留；lease释放异常不覆盖主失败。
- 实际 Editor 笔记/画布打开路径及 openSingleFromHome 仪器测试覆盖成功、取消/拒绝、peek通过但完整decode/verify失败时保留双窗格；本轮只编译时必须明确未运行，JVM组件测试不能代替实际平台。
- 分页 view重挂、GL/GPU/Pro Pen 3本片不改动，也不宣称真机通过。实际 native保存receipt、session事务适配、Save-As/copy持久切换、页激活/来源界面/跳转/反向索引/网络和A2仍保留。

## 已实现与验证边界

PreparedNativeOpen 是原 DocumentCodec/CanvasCodec 的实际输出拥有者，没有引入编辑引擎或重编码笔迹。私有构造限定实际解码路径；sourceVersion 只为已采用的同一模型、种类、UUID 和规范路径返回原始读取版本。相同 UUID/页面的独立模型不能借此取得绑定；该版本不会因旧普通保存自动变成新保存回执。

NotebookStorage.prepareOpen 在 source.use 内解码、verify、取得独立 PDF handoff；即使 source.close 失败也回收已产出的准备结果。PreparedOpen 独占 native-open-UUID 资产目录，包含被 codec 略去而未进入模型的资产；未采用时整体清理，已采用时只归还租约。原件由既有 PdfResources 管理；当前读取关闭不删除 managed PDF。准备与编解码读流接取消检查，primary error 的清理异常保留为 suppressed。

Editor.openAsync/openCanvasAsync/openSingleFromHome 共用一次请求：完整准备之前不 detach；原生打开登记改用 ensureForOpen，在同一存储锁内拒绝待恢复记录，不由打开操作自动发布未知拥有者的旧保存。失败诊断也是一次 IO 产出后才在主线程检查请求并写恢复提示，避免迟到对话框。准备返回采用外部 AtomicReference，覆盖 withContext 返回派发取消；离开时用 NonCancellable IO 释放，成功 ack 再核对当前实际模型/URI/种类。

采用前对当前 documentOwner 去重等待保存，并最终无挂起核对所有相关 pane/owner/model、dirty、在途写与整个工作区笔接触。显式放弃只认提示时的模型/bodyRevision/规范位置；待恢复保存不猜定放弃含义。移除 secondary、拆共享关系、新建、实际替换和关闭均使旧请求失效；旧关闭回调核对具体模型，不能关闭后来的新模型。

MainActivity 的 Save 与 SaveAs 续接在异步完成后复查实际模型且clean。SaveAs picker暂仍使用旧底层保存，但结果在打开输出流前先核对原模型/版本；同一个选择器只允许一个未完成请求，证明和continuation存于同一对象，取消/结果/启动异常释放占用，不被第二次请求顶替。此守卫不等于完整候选发布、copyScope 或会话持久切换已接通。

8 项新增主机测试覆盖同源读取、actual model/canonical URI 绑定、note/canvas、独占资产取消与采用、嵌入 PDF handoff/pin、失败清理；另 1 项普通保存回归覆盖先前无 pending、随后出现 SAVE journal 时 ensureForOpen 零写/保留。首次缺 API 红灯为 tmp/verified-native-open-red.log；组件首次通过为 tmp/verified-native-open-first.log。

新增 VerifiedNativeOpenTest 8 项实际 Editor 仪器场景：成功主页单文档、peek 可过但严格解码失败保留双窗格、读取期间新增笔迹后保存失败、newNote使旧canvas请求失效、过期Discard、Save迟到ack换模型、移除空窗格、迟到诊断。已有 SplitEditorTest 改用实际模型授权。所有仪器用例仅编译，未执行；不把源码复核或主机结果写成这些 Android 行为已通过。picker并发占用、IME/笔接触、withContext精确返回派发窗口、双窗格先存一侧等另一侧时再变脏，尚未实机运行验证。

独立复核发现并修正：保存 continuation 迟到、secondary释放遗漏、诊断迟到写UI、pending预查竞态和picker请求/continuation覆盖。最终验证 tmp/verified-native-open-resources-final.log 为 294 套件 / 3200 项，失败/错误/跳过 0，:app:compileDebugAndroidTestKotlin 成功。

本片不改 GL、Surface 或前缓冲，也不宣称 MovinkPad / Android15 / ProPen3 验收。生产 native保存receipt、来源版本与cache捕获时序、旧缓存恢复/冲突、Save-As/copy持久切换、fork/merge、页激活与页面来源界面、跳转/反向索引/文件网络及A2全部继续保留；整体未完成。
