# 恢复资料只读预览与独立副本（2026-11-09）

用户要求：按计划继续思维导图与文件关系网络；补齐来源生产迁移所需的恢复正文读取及实际新建副本消费者，保存失败保留资料，不打包APK。
状态：基础解码与原字节副本已验证，真实消费者仍实施中，整体需求未完成；计划已独立审核并补齐失效与所有权边界。
本轮边界：源码、相关编译／测试、文档与本地提交；不打包、安装、推送、合并或发布。

## 可验收切片

从当前实际物理窗格的恢复资料入口，选择真实保存槽，读取只读预览，再以新文档身份创建独立副本。继续使用原生codec、CPU预览和NativeCreation发布，不安装可写Editor、不以缓存路径授原件写权限，不修改当前模型／dirty／来源绑定，不清原恢复资料。

1. WorkingSessionCache实际读取区分Absent、Cleared与Active；坏／未来／symlink／权限错误均不是Absent。Native head真实Absent才允许在既有LegacySessionQueue拥有者顺序内捕获旧正文。Cleared不回退。Bound／交接资料不降级为无来源；旧note与canvas同时存在时明确列出两份，不猜当前模式。
2. 只读准备拥有真实正文租约／私有snapshot、独立解码资产及PDF租约。Unbound不再只有元数据；但不签发NativeSourceVersion、EditSource、adopt、claim或普通原件保存权。完整包合同、页marker、PDF原件摘要与实际解码结果均核对；path为空。
3. 副本从准备中持有的原始bundle复制，使用NativeCreationIntent给定的新UUID。保留非身份条目的原始内容，有页marker时仅修改页ID literal与marker。预览decoder略过未知笔迹不影响副本；不得把模型重编码当作完整备份。
4. 真实Backstage入口捕获具体Editor／physical pane，不改用documentOwner。两窗格不会读错槽，不attach新owner。选择器绑定原preview、独立请求和新建intent；迟到结果零写入。关闭／销毁先失效请求，再取消／等待所有IO后释放资产；已接受的Legacy队列任务不随awaiter取消，须在IO外持有结果并等待完成后清理。
5. 创建只走prepareNewTarget→prepareCopy指定UUID→publishCreated。失败后不重用permit、不重新分配页ID，不删除provider返回目标。已stage恢复由现journal持有；本次明确继续仅调用completeCreation。重启后提供重新选择原目标的显式继续入口，不假称内存按钮可覆盖重启恢复。
6. 渲染按需只读当前页／画布，显示配置在请求边界捕获，不读取另一窗格的live状态、不临时替换Editor.document。无法渲染PDF时明确显示预览失败。大PDF不为每页重复复制整个包，不物化整包ByteArray；实际磁盘峰值和目标设备性能另验收。

验收覆盖真实消费者、两物理槽、所有非身份负载摘要、对象未知字段、旧／新页面合同、取消后后台snapshot清理、坏／未来／Cleared禁止回退、迟到picker、目标非空／权限失败、写后失败显式完成以及当前文档与原资料全过程不变。JVM与Android源码编译分开；仪器／真实SAF／MovinkPad未执行时明确标记。

本片不代替仍必需的native4统一producer、cold actualA固定B受控续写、跨Activity逻辑writer寿命、live另存持久交接及页身份激活。随后继续页面多来源、预览／状态／恢复、来源往返与反向引用、文件网络，以及A2剩余交互。

## 基础阶段证据

- `6c00efa0`：实际head读取区分Absent/Cleared/Active，保持旧nullable load；新 `BundleMigration.prepareCopy` 使用Creation给定的UUID，同snapshot完整校验，保留原始非身份负载。新增状态2例、指定ID副本7组。
- `8873f200`：`PreparedRecoveryPreview`由实际Legacy snapshot或Unbound held Read解码，持有私有资产与PDF租约，path为空；不签发来源写权限。副本始终读取原始body/SHA，改变预览模型不能影响副本。提取的纯Note合同与来源授权分离，旧来源页集合校验保持，预览另外核页顺序。新增legacy5、native5行为用例；旧Unbound调用改为显式关闭。
- API红：`tmp/recovery-preview-state-red.log`（30881退出1）、`tmp/recovery-preview-copy-red.log`（52141退出1）、`tmp/recovery-preview-decode-red.log`（61758退出1）。副本63项定向首次仅尾随JSON反例失败，查明legacy宽松parser忽略根后内容；新inspectStrict只用于恢复副本，旧读取兼容不改，无marker旧大字符串不引入1MiB token限，marker现有限制保留。修复后 `tmp/recovery-preview-state-copy-entry-focused.log` 63项通过、Android源码编译通过。
- `tmp/recovery-preview-decode-focused.log`：5套50项通过，覆盖实际解码、历史body抗新head与clear、PDF租约与取消、同UUID来源context拒绝借用恢复模型；独立窄复核通过。
- `tmp/recovery-preview-foundation-full.log`：完整322套3571项，0失败／错误／跳过，Android测试源码编译成功；JDK21、离线Gradle、独立构建及project cache。含Backstage sidebar/rail两个新增实际窗口测试，但均仅编译未执行。无APK、安装或推送。

此时Backstage只增加回调入口基座，尚未绑定真实预览host，不据此宣称用户恢复流程可用。待做实际physical-slot loader／队列取消资源交接、隔离live配置的渲染、选择器与CREATE失败的typed续写、重启后明确选择目标继续。来源和文件网络的其余需求仍保留。

## 实际读取、渲染与副本后端

- `be7bdb68`：创建首发和重试都绑定实际暂存负载；写后登记失败可识别本次保留事务，明确继续不会另造副本身份。真实首发替换反例已先失败后通过。
- `0e05feca`：持有正文按需渲染单页或画布，使用隔离的显示配置，限制输出尺寸；PDF渲染错误不显示成功空白。3项实际Editor主机渲染用例通过，设备包装仅编译。
- 新增实际物理槽读取，不跟随当前活动窗格或共享文档拥有者；只有新会话head确实不存在才显示旧笔记／画布选项。队列已接受任务取消时仍等待结束，再释放临时snapshot。可选旧标题元数据有界读取且不跟随链接，坏标题不损坏有效正文。完整正文校验支持取消。
- 副本动作固定一个新建身份，直接复制持有的原始包；保持预览和当前文档不变。失败分为本次事务已保留、无可认领事务两类；继续调用现有发布恢复，不删除选择器目标或原资料。
- `tmp/recovery-copy-stage-render-full.log`：325套3582项主机用例，0失败／错误／跳过，Android测试源码编译通过。
- `tmp/recovery-preview-slot-copy-focused.log`：6套25项通过，包含实际Editor物理槽6、可取消校验3、副本5、typed续写7、首发绑定1、渲染3；Android测试源码编译通过。
- 真实预览界面测试已加入，`tmp/recovery-host-consumer-api-red.log`按预期因尚缺Host／Controller与资源编译失败；实际界面正在接通。不能用后端通过宣称用户流程已完成。

仪器测试、系统文件选择器、真实SAF权限与目标设备渲染／笔输入／性能仍未运行。
