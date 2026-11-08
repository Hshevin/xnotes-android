# 分页另存与页启用原字节证明（2026-11-09）

用户要求：继续思维导图与文件关系网络，先补引用所需的安全文件出口，再接永久页面来源；不打包APK，可编译、测试并本地提交。
状态：实际分页另存入口及原字节验证器已实现；331套3618项主机测试、Android测试源码编译和独立复核通过；页面启用的存储发布及来源界面仍未接通，平台与真机待验收。
本轮边界：源码、相关编译／测试、文档及本地提交；无APK、安装、推送、合并或发布。

## 实际另存闭环

主界面首次保存／另存先结束文本／flow草稿并等当前笔输入、旧写入稳定，核原owner／模型／身份／路径后才固定最新revision的目标选择器请求，避免IME／onPause收尾立即使请求过期。不再先打开可截断的输出流。旧Editor.save(OutputStream)已移除。显式另存走saveAsToThen，即使provider返回原文件URI，也必须通过NativeCreation空目标核实；普通保存同目标仍走原来的受校验发布，不能据此宣称所有外部同UUID内容冲突已解决。

主线程等旧native写入与已接受会话前缀，在输入稳定后准备PageReferenceCopy和固定正文。新目标以NativeCreation发布，成功后才核原owner、模型、路径、身份、scope、PDF文件指针与来源epoch，再采用复制scope及实际PDF元数据。副本的文档UUID独立，已启用引用时页UUID也独立；未启用的legacy不借另存分配页编号。原文件及旧引用保持原处。Page对象、历史、笔迹和视口保留。晚到的正文修改在采用前按revision判断，继续保持dirty；末次awaitNoteCapture先结束阻断modal，不将预览或未完输入误作保存完成。

新PDF原件使用admitHeld覆盖编码及发布期间，发布成功才由新文档永久引用保护，不提前留下虚假的永久note owner。当前文档同时合入实际受管PDF文件和v2身份，不删除旧输入。写后登记失败保持原模型的路径／scope／内容，保留同一候选；可从「恢复资料→继续未完成的副本…」显式选该目标完成，之后手动打开；该独立完成不偷偷采用当前模型。

NativeDestinationPicker每次启动使用新的运行时注册键，只有一个待结果回调，先消费／注销再调用固定闭包。启动失败与旧重复结果不影响新请求；重建不会把旧requestCode转交新请求。AndroidX1.10.1本机依赖的CreateDocument合同只设置action、MIME和title；初版测试错误要求OPENABLE分类，核实依赖实现后移除该无产品依据的断言，保留真实Intent解析与全部回调隔离断言。

## 页启用的原字节证明

BundleAnchors.verifyActivation仅接受实际NeedsActivation原件：已有有效文档UUID、无marker、无任何直接页编号。orphan／部分／重复身份留给明确修复，不借启用重解释。原件和候选均走完整容器／PDF合同检查；候选只能增加canonical marker及直接页编号字段。用候选ordered IDs回放原manifest的插入位点，核其余UTF8片段及所有非manifest条目的原始摘要；不随机重prepare，不解码模型再编码。

该证明也适用于保留候选的重启核验，但不授URI、版本或模型写权限。实际Storage启用事务仍须绑定A/B快照、摘要、位置、UUID和发布记录；本轮尚未接入该消费者，PAGE入口继续保持未启用。既有prepare／inspectCopy兼容行为不变。

## 验证及修复证据

- `tmp/editor-page-copy-behavior-red.log`与XML：70480真实复现旧另存空目标失败，非空目标拒绝覆盖／C1保全例通过。17763仅为初次包名filter错误，不算行为红。
- 初版候选在codec关闭底层流后fsync，抛SyncFailedException；`tmp/editor-page-copy-exception-diagnostic.log`定位后沿用SessionStore的非关闭bulk wrapper，未把真实错误当主机差异绕过。
- `tmp/page-activation-verifier-api-red.log`、`tmp/native-destination-picker-api-red.log`、`tmp/explicit-save-as-api-red.log`记录三个缺API阶段。
- `tmp/editor-page-copy-fsync-and-pdf-contract.log`：Editor4例通过；独审补入的无效PDF容器启用反例真实失败，随后补inspectStrict。包括缺原件、错误has_pdf类型、未声明原件、external与embedded矛盾。
- `tmp/page-copy-pdf-adoption-red.log`及XML：实际PDF保存的v2／hash／字节／lease／永久引用均正确，但live文件仍指旧temp的反例真实失败；已修并增加末次PDF指针不变检查，防止覆盖IO期间更换的原件。
- `tmp/page-copy-pdf-final-focused.log`：6套41项全过，Android测试源码编译成功。Editor6例覆盖独立页身份、原URI拒绝、C1／历史／视口、SQLite写后失败与只登记续完、PDF共享对象入库及抗GC；格式11例及旧格式22例、Picker2例均通过。
- `tmp/page-copy-pdf-reviewed-full.log`：PDF采用修复后331套3618项；随后补选择器启动前的输入稳定等待，`tmp/page-copy-pre-picker-input-focused.log`的SaveGate17／Editor6／Picker2共25项及Android编译通过，新增Main顺序独立复核通过。
- `tmp/page-copy-ui-input-final-full.log`：最终331套3618项，0失败／错误／跳过，Android测试源码编译成功。JDK21、离线Gradle、独立构建与项目缓存。三语资源齐备，两份中文相同，git diff --check通过，独立窄复核通过。

原字节证明已提交`1fe1286c`，选择器组件已提交`929ab4df`，实际另存入口与测试已提交`47c4cc49`。

SplitEditorTest中两条旧直接输出流调用已迁异步真实文件保存，保留共享文档／撤销重做／视口／关闭借用窗格及PDF原件断言；新异步夹具使用独立会话／登记／缓存目录并恢复存储单例；`tmp/page-copy-isolated-device-tests-compile.log`编译通过，本轮未执行仪器。系统DocumentsUI、真实SAF权限、PDFium／旋转、MovinkPad Android15 ProPen3、GPU及大文件性能未验收。新增PDF主机用例检验存储而不伪造渲染引擎结论。

## 下一步

尚须将Main的文件副本、createNoteFile和fork／merge出口接入同样的目标与复制身份保护，再接实际页启用发布／明确恢复、fresh catalog选择PAGE来源、保存重开。来源预览／状态／刷新／修复、跳转返回／反向引用／文件网络及A2剩余交互继续保留；本轮不代表完整引用闭环已完成。
