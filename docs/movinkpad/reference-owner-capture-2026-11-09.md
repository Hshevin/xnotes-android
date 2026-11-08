# 编辑拥有者的稳定保存（2026-11-09）

用户要求：持续实现思维导图与文件关系网络，沿新渲染架构复用原生编辑、保存与身份能力；按既定完整范围推进，不打包 APK。
状态：已实现／已验证；基线 bfb82c4b，实际保存／会话／关闭接线经独立复核，完整主机测试 3483 项通过，Android 测试源码编译成功；待仪器与目标设备验证，引用闭环未完成。
本轮边界：源码、编译、主机事件测试、Android 测试源码编译、文档和本地提交；不打包、安装、推送、合并或发布。

## 生产合同

自动保存不提交或取消活动笔、选择变换、样式／填色及表格预览。文字只在有实际未确认内容时阻塞；键盘可见、flow active、导航、选择框和未入模型的多边形草稿不是永久阻塞条件。显式保存、暂停、关闭按现有 blur 语义确认文字／flush flow，然后等待其它活动事务。不能用 IDLE 或 reset 冒充确认。

输入、文字和预览的结束通知只标记／唤醒，统一 Main post 后检查，避免在历史、dirty、自动切工具或 ProPenInput 抬笔处理尚未结束时同步捕获。共享分页文档检查两个实际输入面。旧 write 先完成，随后在同一不挂起 Main 调用中重核 owner、模型与位置并固定 writer 视图；IO 不遍历活模型。原位／放大导图已采用自己的事务，本片处理其来源编辑器。

## 会话与替换

旧 SessionStore 和 CanvasSessionStore 暂保留磁盘格式，不引入第二套编辑器或半启 NativeWorkingSession。目录级 LegacySessionQueue 只协调运行时 FIFO：已登记 close/clear 必须排在新 restore 前；新 Editor 该槽的恢复／采用意图只退休旧实例后来的请求，不丢弃此前已接受的操作。队列结果 Deferred 的取消不取消实际 IO。load/exists 也经同一 FIFO；异步恢复采用前核本地请求号和 runtime owner，不能在迟到 IO 结束后再重新认领。

未采用的 secondary 与 missingDocument 占位不写也不清旧槽。实际采用／新建解除占位并允许持久化；共享文档保留 primary 会话及各窗格视口的既有规则，不简单把会话转发给 documentOwner。

note 的正文复用只凭本 runtime 同 owner、模型 token 和 bodyRevision 的成功保存；元数据更新不能在正文缺失时偷偷编码一个空模型，须返回 NeedsBody 后重新核实并固定正文。canvas 的 lastView 在正文里，本片先始终写固定正文，不仅按 bodyRevision 判断视口复用。store 返回明确保存结果，失败不推进成功依据；正文编码失败不删旧文件。正文／sidecar 两文件的崩溃原子性仍为旧格式边界，不授 native 保存权限。

关闭／替换不得请求一个延后的保存后立即换模型。需等待已确认旧内容及编码任务，最终仍为同模型、无新 dirty／活动事务时才采用／退出。未完成的新建／另存 copy scope、原生回执交接和页身份激活保持后续完整范围。旧临时 PDF／图片不得在编码中被换模型清理或因 Activity 重建再次 purge。

## 表格 UI

预览以实际 Document＋FlowTable＋原 snapshot 签发事务 token。取消、无变化完成、dispose 都显式释放；迟到 token 不修改新文档。编辑模式打开本身不阻塞保存，只有未确认的预览变化才等待。填色结束只按所见确认，不把 null 颜色当成纯 flush。

## 验证

已接入的实际入口包括分页及画布 debounce／flush、显式保存、暂停会话、新建、关闭／全部关闭及原生打开前的 settle。旧 native3 保存与旧会话格式暂保留，使用固定 writer 视图，不激活 NativeWorkingSession 的保存权限。表格预览在实际 undo／redo 前确认；等待预览或落笔结束时不显示阻断操作的保存 modal，真正捕获并开始 IO 后才显示。替换不补发第二次旧文档保存，明确放弃凭据沿新建／菜单关闭传递；会话恢复的分页文档实际可见。旧 Editor 退役唤醒保存等待，不取消已接受的会话 IO。

临时资源只在当前进程第一次使用该应用目录时清理，界面重建不删除仍在使用的 PDF／图片。共享视图回收临时 PDF 前等待两侧已经登记的编码任务；受管共享原件仍按原引用／租约规则回收。

覆盖笔和选择预览期间请求保存、UP/CANCEL 后继续；文字／标签草稿和组合输入；flow active 时仍可保存；无修改／取消预览解锁；固定捕获后再改 live item 的字节不变；写入排队期间换模型／URI／窗格或产生新 dirty；旧 write→clear→新 restore；元数据缺正文、保存失败、未采用 secondary；资源在途寿命。

输入接口新增测试的缺 API 编译红为 `tmp/content-transaction-red.log`；队列 API 缺类编译红为 `tmp/legacy-session-queue-red.log`。初轮实际接线的 `tmp/editor-owner-capture-focus.log` 定向 32 项及 Android 测试源码编译通过。真实 producer 测试 `tmp/editor-owner-producer-first.log` 为 14 项中 1 项表格撤销失败，其余通过；修复后 `tmp/editor-owner-capture-reviewed-focus.log` 定向与 Android 测试源码编译通过。新增真实 Editor 场景核对 persist 前后实际会话字节、笔 UP／CANCEL、填色预览、未采用 secondary、表格撤销、共享关闭、明确放弃新建、恢复可见性、等待阶段 modal 与退役唤醒；不以单个 await API 代替实际保存验证。

最终 `tmp/editor-owner-capture-full.log`：JDK 21、Gradle 8.13、离线本机构建，隔离输出 `/private/tmp/xnotes-mind-map-build/` 和项目缓存 `/private/tmp/xnotes-mind-map-cache`；`:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin` 成功。JUnit XML 为 313 套件、3483 项，0 失败／错误／跳过，相比基线新增 47 项：输入契约 6、画布宿主 4、实际 Editor 17、目录队列 9、checked store 11。两个中文资源一致，`git diff --check` 通过。

独立复核的样式／表格预览历史顺序、画布历史 UI 刷新、等待阶段 modal、共享全部关闭、明确放弃新建、恢复可见性、关闭最后捕获、共享临时原件寿命及退休 producer 均已对应修正。退役通知锁内恢复问题另有 `tmp/legacy-retirement-lock-red.log` 单项红例；锁内先登记 owner／fence，再锁外唤醒，仅暴露不可取消的 select clause，完整回归及最终只读复核通过。已接受操作的取消、失败、退役和跨 owner 清理顺序保持。

新增的 15 个实际 Editor 及 6 个输入共享 Android 场景、已有的画布宿主场景已登记设备 runner，但仅编译，没有运行仪器。真实 SAF、MovinkPad Android 15／Pro Pen 3、界面窗口、GPU 与性能均未验收；本机结果不能作为这些项目通过的依据。按仓库规则本地分阶段提交，未打包、安装、推送或发布。

本地代码提交：`662a2762` checked store、`fef07d7c` 目录 FIFO／退役唤醒、`47cc562e` 输入事务及历史顺序、`19574fe1` 实际保存与替换接线及真实 Editor 场景。

## 保留的后续范围

本片不完成 native4 生产接管、原生成功回执与同 head 会话交接、已开始原生 IO 的跨 Activity 采用保护、cold／live 恢复权限迁移、新建／另存 copy scope 持久切换。旧会话正文和 sidecar 的崩溃原子性仍是已知限制，checked 成功不代表取得 native 保存权限。未保存画布没有目标时拒绝直接关闭，仍需明确的保存／放弃交互。

页面身份启用、从文档页面／区域添加来源、预览与失效恢复、来源往返、反向引用、文件网络及多选／无中心画布继续保留，不将本片视为整体完工。
