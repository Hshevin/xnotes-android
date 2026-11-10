# 导图与全局入口的文件身份修复（2026-11-11）

用户要求：修复导图无法打开、文件关系网络误报引用变化、原 PDF 原件库无法进入，并评估本地存储与会话架构；另请 Claude 独立审查。
状态：源码已修复；主体修复 JVM 4,783 项通过，审查后 14 项定向回归通过，Android 测试源码编译通过；Android 运行时及目标设备待验；Claude Opus 5.5 独立审查未发现阻止提交的问题。
本轮边界：修复、测试、文档和本地提交；不打包、安装、推送或发布。

## 原因与修复

普通首页的三个入口是已有能力，不能归为未实现。它们都通过 `ColdPresentationCoordinator.prepareDeparture` 和 `PreparedHomeGate` 写入最后呈现记录；失败时导图／网络会把公共门禁失败显示为内容变化，原件库调用方此前忽略失败返回值。Map＋NOTE 和 Map＋首页布局中的全局网络／原件库完整往返仍是 R1a 缺口，本轮未实现该能力。

`LastPresentationStore` 曾使用 `fileKey + creationTime` 作为文件与目录身份。Android 的 Unix 文件属性在不支持 birth time 时以 mtime 返回 creationTime，自己创建 stage 会改变父目录时间，自己写入 stage 会改变 stage 时间，因此正常写入可以令自己的身份校验失败。`MapStore` 在打开已发布到原件但仍标为 pending 的恢复日志时，也会比较写入前后的 creationTime，产生 `Checkpoint stage changed`。

修复把文件实体与内容版本分开：实体使用非空 fileKey；内容仍核对大小、SHA-256 和读取前后的修改时间。保留固定私有路径、无符号链接读取、目录替换核验、实际 stage 归属、串行票据、业务最终检查、原子替换、未知记录保留和取消清理。没有绕过保存门禁，没有改写笔迹或原 PDF，没有迁移文件格式。

原件库打开失败且请求仍属于当前工作区时，补充明确反馈；已有更具体的错误提示保留。导图／网络的错误分类仍较粗，不能把其“变化”文案作为外部修改的证明，后续应独立区分读取、持久化、输入未稳定和真正版本冲突。

平台依据：Android 15 `UnixFileAttributes.creationTime()` 在无 birth time 时返回 lastModifiedTime，`fileKey()` 为设备号与 inode；见 [Android 官方实现](https://android.googlesource.com/platform/libcore/+/refs/heads/android15-release/ojluni/src/main/java/sun/nio/fs/UnixFileAttributes.java)。

## 验证

- 新增 `PresentationFileIdentityContract`：主机以 Android 的 creationTime 回退语义运行，Android 子类使用真实文件系统，无时间替身。
- 修复前 5 项中 4 项失败：目录普通修改、布局记录正常写入、失败清理、已写入 pending 导图重开；实际错误含 `Checkpoint stage changed`。目录实体被替换的拒绝用例通过。
- 修复后 46 项定向测试通过：文件身份、LastPresentationStore、导图恢复日志、发布回执、恢复检查点。既有同字节换 inode、stage 篡改／符号链接、原件冲突、写后失败与取消用例仍通过。
- `ColdMainPresentationHostTest` 接入相同的 Android 时间语义，新增经过真实协调器的“原件库→网络→导图”连续宿主用例，通过且原导图字节不变。它是 Robolectric 宿主测试，不是设备上的 MainActivity 点击验收。
- 全量 JVM：4,783 项，失败／错误／跳过均为 0。Android 测试源码编译通过，新平台契约已注册到 `PenTestRunner`。
- 命令：JDK 21，`./gradlew --offline --no-daemon --max-workers=2 -I <构建目录初始化脚本> :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`；输出位于同步目录之外。
- 本地原始证据：`tmp/presentation-identity-red.log`、`tmp/presentation-identity-green.log`、`tmp/presentation-identity-regression.log`、`tmp/presentation-identity-review.log`。无连接设备，未运行 Android 仪器测试、SAF／Surface／GPU／笔输入验收；未声称修复 test6 的全部 76 项平台失败。

## 架构判断与后续门槛

当前不能称整体牢固。文档所有者、保存权限、不可变快照、恢复先行和取消保护有保留价值，但不同模块重复实现平台身份判断，基础入口共同依赖的呈现记录又缺少真实 Android 文件系统验证，错误向上被压成模糊“变化”，导致可诊断性差。上述是本轮实际链路的结论，不是已完成全仓库审计。

下一步应先执行新增 Android 存储契约与三个真实入口，定位并关闭相关平台失败，再继续 R3a 等新增功能。不能以 JVM 数量替代用户流程验收，也不应直接删掉门禁或整体重写保存体系。
## Claude 独立审查与收尾

Claude Opus 5.5 只读审查当前源码 diff，认为修复对准根因，未发现削弱身份、保存或恢复保护的修改，也未发现阻止提交的新 bug。它建议保留现有事务协议，以小范围统一文件观测和类型化失败结果改善架构；不因 Editor/Main 文件较长就整体重写。

审查提出的相关边界本轮补充处理：

- 新增连续编辑、两次以上本地恢复检查点、只读身份观察及重开契约；原件字节不变，适用于模拟 Android 属性语义和真实 Android 文件系统两种运行方式。
- 新增未来版本布局记录的宿主回归：启动识别 Invalid，离开门禁拒绝，记录原字节保留。普通首页的三个入口提前显示“先查看恢复资料，再重置工作区记录”，不自动重置或绕过门禁。
- Map＋首页与 Map＋NOTE 的网络／原件库入口明确显示当前布局限制，仍保留原拒绝条件，R1a 未冒充完成。计划补充 Map＋首页范围。

审查补充完成后，`PresentationFileIdentityTest` 6 项与 `ColdMainPresentationHostTest` 8 项全部通过，Android 测试源码再次编译通过；未重复运行与补充变更无关的全量测试。

更广泛的类型化 departure 结果、跨模块文件观测工具统一留作后续有边界的改进。本次没有把所有布尔有效性检查改造成新协议。修复前后测试证据不覆盖未执行的 Android 仪器测试。
