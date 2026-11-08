# 原生保存到工作会话的持久交接（2026-11-08）

用户要求：继续思维导图保存/重开闭环；保留已写入版本，只丢之后修改；不要打包APK。
状态：整体交接计划已独立审核；首片 v4 持久拒写保护已实现；完整 JVM 300 套件 / 3293 项通过，Android 测试源码编译成功，独立源码复核通过；实际阶段证明/prepare→commit也已实现并独立复核；完整协调交接尚未实施。
本轮边界：源码、测试、文档、本地提交；不打包、安装、推送、合并或发布。

## 要解决的真实窗口

e9c4c582已实现严格keepWrittenSave，8b09a40c已实现画布连线。普通保存仍不能直接切到新producer：native写成B并清journal后，主线程还没accept回执/写新cache时若退出，旧cache仍记来源A。即使其正文是用户最新C，严格恢复也不能猜测A→B合法。另放一份独立sidecar会重现两份资料不同步的问题。

本阶段在WorkingSessionCache同一原子head的native metadata内引入受核实的保存交接信息（native metadata写schema2、读schema1/2，通用WorkingSessionCache envelope仍v1，**不改变.xmap或.xnote版本**）。它与现有body/metadata一次发布，不另存一个可与正文错配的来源sidecar。只做普通同文档native save；复制A→新UUID B的copy pending、新建、显式PDF/页锚点升级、legacy/unbound目录与生产Editor的统一接线仍保留，不偷偷降级为普通保存。

## 合同与顺序

1. **实际stage证明**：把现同步版本保存分成IO prepare-stage与IO commit两段，prepare完整验证A/候选B并持久stage，返回由实际storage issuer签出的私有StagedSave/PreparedPublication；绑定真实A/B SHA、完整原identity/PDF/anchor契约和record，不从任意tuple/JSON签发。候选stage绑定预检实际SHA。旧native-only publish仍可经该两段同步完成，行为保持。协调stage单独写native journal v4（保留原SAVE purpose、额外持久handoff ID/needs-ack意图，不是新sidecar/owner UUID）；旧native-only仍v3。普通ensure/publish/resume对v4无ack一律拒绝，进程内与重启后均不能抢先写B。
2. **post-stage当前capture是因果屏障**：B固定stage后才由新的协调publish入口切回owner主线程，以协调入口每次回主线程时新签的、一次性私有capture permit重新capture当前live C与actual source proof，不能回传旧Write或另一次permit的结果。callback自身不授予成功，结果必须带该次permit的private关联。新proof必须属于原请求的实际owner token、同一expected对象/epoch；旧snapshot的revision不背书现body。原snapshot会共享Stroke等状态，故仅给旧C0附record可能把已写S1回滚S0，本片明确禁止。
3. **等待主线程时不持storage/cache锁**：prepare-stage返回前释放NotebookStorage.lock。主线程capture及cache持久完成后，commit再取storage锁核对同一stage/record/A/B摘要及实际目标，才发布B。cache锁内不回调storage/PDF/外部validator；验证在锁外，锁内只内存状态与小head CAS，双线程latch覆盖反向等待。
4. **普通latest reservation即可**：post-stage C+handoff作为正常最新cache请求发布；正文版本变了编码，未变且有当前runtime成功证明才复用。不为旧writer做复杂handoff-only rebase，旧请求照latest规则失效。post-stage注册的同owner intent必须被之后capture继承；其它owner/模式/clear不能移除未完成intent。cache正文/视口与handoff一次提交，不用独立sidecar，不保存整页ID列表。
5. **受控反向ack**：新协调入口封装prepare→私有post-stage capture→C+handoff成功确认→commit，不暴露可空/空Unit回调。Publication(false)/stale不能准写B；若更晚同owner请求已成功持久同一handoff，可核验当前head后取得绑定该stage的私有ack；否则失败保留stage/缓存。commit在ack后还要复核stage证据，attach后stage被换不能写目标。旧native-only入口不因此自动获得cache能力；v4 gate从API传到core journal，只有绑定该stage的ack才可继续。可显式恢复已有v4+已持久T的固定候选B（不写新C）；T尚未持久则不能从旧head猜ack，保留资料等待明确恢复，不自动续写。
6. **完成native与缓存**：成功实际Receipt才推进EditSource。Receipt关联本次handoff ID，capture的实际proof继承已接受handoff ID；尤其A/B字节相同的no-op也不能靠SHA让旧epoch提前清交接。主线程捕获当前live C来源B后，正常C head与移除handoff同一提交；cache失败保留交接，不回滚B、不发伪成功。
7. **恢复只认实际B**：本片只对实际来源==B（含A==B no-op、原native pending已显式处理）核实并解码cache C，再按cache自身页集/实际原PDF/anchor/完整identity准备该实际模型的source上下文。实际A且无pending不能推断用户保留A——可能B成功后外部回退A；本片对未解决A一律拒绝自动重基，后续须把显式A-resolution与actual storage结果绑定到同head。外部C/身份/PDF不符均保留资料。data load仍能保留不同PDF等工作C，不能因其不给普通source grant而删除。
8. **认领不伪造正文版本**：只有同adapter/read、未关闭且head未变的实际Prepared恢复/采用能认领新runtime owner；认领只授权handoff归属，跨重启保守首次重写正文（claim不建立body复用proof），不把live当前revision或旧head.counter给C0背书。恢复C0后编辑C1再claim也必须新body。任意同UUID模型不能替代；未采用资料不能被clear/新mode抹掉。
9. **失败边界**：cache交接未持久，native目标不得写；未知/未来metadata、owner/source不符、权限/损坏均保留。B已写但后续失败可先keepWrittenSave再恢复；部分写入不冒充B。正文/文件/PDF验证均锁外且可取消，最后check与SAF外改仍非跨provider原子。

## 验收

- 真实SaveRequest/NotebookStorage prepare-stage完成时断言目标仍A；交接先落盘后目标写B，重开实际B+旧来源A工作C可恢复C并继续严格保存；必须覆盖cache先编码Stroke S0→live变S1/dirty++→旧native snapshot稍后编码B含S1→post-stage当前capture→重启不得回滚S0；从未提交/另一同UUID owner/错误storage proof不能附加或认领。
- 中断点覆盖stage前后、交接head失败、target复制后抛错、登记失败、native回执接受前后、post-native cache失败；B保留、C不丢，不发伪receipt。
- cache writer与post-stage交接交错：新body/视口不被旧head覆盖，交接不被旧writer丢掉；false publication/空或旧capture不给ack，stage证据变更拒写；v4 stage在T未持久时，当前进程及重启后的旧ensure/native-only发布/恢复均零写；未知head/未来record/cleared/其它owner拒绝；clear与模式切换不得抹未完成交接。
- actual A无pending不得自动恢复，no-op需stage ID/epoch而非hash清记录；恢复C0后编辑C1再claim不能view-only复用；双线程验证无storage↔cache/Main死锁。
- 真实PDF便携cache/external native、原字节/页面UUID保持、data load与source grant区别、lease/GC/取消及失败清理；不为每卡复制/解码整份PDF。
- Android JSONObject/原子head/实际provider仪器用例保留，本轮仅源码编译时明确未运行；完整JVM/已有事件回归与独立复核后本地提交。

## 后续生产接线不能省略

所有native请求先等旧写完成再owner capture；replace/close/newNote等待写，不在flush前retire；主线程dirty与后续编辑分别记账；同文件准备读取后若自己flush改变源须重新准备，不能采用旧A再写B。统一NOTE/CANVAS会话，旧独立sidecar只能保留为无写权草稿；未采用secondary资料不能被clear。new/Save-As/fork/merge需新Doc/page scope与同head copy pending，不能把本片同UUID保存交接套到新UUID。source PDF插入/替换和原字节页激活仍是显式事务。完成这些后再激活生产producer/恢复、页面多来源、跳转返回、backlinks及文件网络。overall complete=false。

计划独立复核已据实修订：post-stage fresh owner barrier、prepare/commit释放锁跨主线程、普通latest请求替代复杂rebase、私有ack、no-op epoch/ID、A fail-closed与跨重启首次正文重写；已补v4 durable needs-ack gate与一次性post-stage capture permit后可实施；旧native-only v3兼容不扩大。SAF别名按canonical location核对，不以URI字符串/名称相等代替身份。

## 首片实施：持久拒写保护

MigrationPublication stage新增可选handoff ID，仅接受canonical UUID、SAVE_NOTE/SAVE_CANVAS与两份已预检SHA；非法参数在复制恢复文件前拒绝，实际原件或候选摘要变化则不发布marker。journal v4保存同一A/B摘要、SAVE purpose和交接ID，不改变笔记或缓存格式。普通publishValidated在任何target读取、保护/验证/登记回调之前拒绝v4；重启和URI别名也不能绕过。尚无颁发ack的API，生产路径仍使用原v3。

显式restore/typed keep保留原有契约，额外暴露交接ID数据，不授予发布回执；实际已写B仍可走keepWrittenSave保留B。未知版本、坏UUID、创建purpose或额外字段不会降级为legacy keep。完整producer、缓存metadata2和认领仍待后续，不把本片gate当作保存闭环已完成。

进一步源码核实：Document.copyWriteSnapshot共享item；CanvasSelection.applyLive/resizeLive及跟随连线可在确认前修改几何、取消后还原。主线程fresh capture和bodyRevision本身不证明内容已稳定。协调交接实施前必须纳入活动内容事务屏障/稳定writer状态验证，不能复用未确认变换的旧正文证明；本片不修改渲染或笔迹采样。

首片验证：tmp/native-handoff-gate-red.log 的先行4个核心用例中2项失败；实现后 tmp/native-handoff-gate-focused.log 定向回归通过，tmp/native-handoff-gate-full.log 完整300套件3293项全过（0失败/错误/跳过），Android测试源码编译成功。新增owned provider重建storage场景仅编译；未执行Android仪器或MovinkPad/Android15/ProPen3验收。无前缓冲、Surface或GPU修改。

## 下一片规格：实际阶段证明与释放存储锁

输入为已签发SaveRequest/NativeSourceVersion，输出为同storage私有issuer绑定的一次性StagedSave，不接受任意tuple作为来源证明。NotebookStorage准备阶段在锁内核实实际A/B原生kind、UUID、canonical key、完整页/PDF契约，并以两个实际SHA固定journal；返回前释放锁，目标仍为A。证明绑定持久record摘要、两份实际version及handoff ID；原生-only路径继续v3并同步prepare→commit，协调prepare生成新UUID写v4。

commit只接受同storage证明，一次尝试；重新核对同record摘要、retained A/B与实际target和registry契约，才沿原发布路径写入/回读/登记，登记使用事务内observed契约。v4仍无ack入口、默认拒写；本片不实现cache交接或source自动重基。record被替换/target外改/源epoch撤销/取消均保留资料、不签成功receipt。prepare不是可写source上下文。

SaveRequest封装实际编码、PDF lease和候选临时文件，stage成功由持久migration pin保护依赖后才释放临时lease；准备结果私有配对原请求/确切storage/阶段，只有其实际成功commit才签回该请求Receipt，不能用另一请求的stage签回执。主线程获得证明后无需持storage锁等待；双线程latch覆盖prepare返回后进入Main/另线程读取不会互等。

验收：真实NOTE/CANVAS/PDF prepare不写目标、预检摘要与固定候选一致；原生v3 commit一步成功、不可重复/跨storage使用、候选临时更改不影响固定B；v4 prepare返回后旧ensure及其commit拒写；record/retained/target/registry改变及取消/登记失败保留恢复资料；原请求失活后不写；双线程锁释放；现有真实SaveRequest/PDF/创建/恢复与全量回归。平台provider例保留编译/运行边界，不打APK。活动编辑事务屏障、cache schema2/ack、重启认领、生产接线仍未完成。

第二片独立计划审核通过，补充绑定：StagedSave绑定确切storage issuer、canonical location与实际journal路径/record摘要；另一相同内容journal不能替换。原请求epoch/取消检查必须贯穿commit验证、大包hash之后的实际写前、读回和登记，而非仅入口；补失活发生在commit验证期间的反例。stage被其它恢复入口消费后，旧handle不因target=B而签Receipt。

## 第二片结果

NotebookStorage.StagedSave已绑定实际storage issuer、canonical journal路径、本次stage写入字节的摘要及实际A/B版本；一次commit重新检查恢复资料、目标、kind/UUID/页面/PDF/登记契约。SaveRequest.Receipt.Staged私有配对原请求与确切storage，prepare封装候选编码及PDF lease，实际commit才签发receipt。已有同步native publish走v3两步；prepareForHandoff生成v4，尚无ack权限、仍默认拒绝commit。返回Main前不持storage锁。prepare并非保存成功，也不授予恢复模型保存权限。

独立复核发现并修复两项：stage之后读取现存marker可能签下替换record；大包retained散列未接core取消。tmp/native-staged-save-review-red.log两个反例先失败，修复后tmp/native-staged-save-review-fix.log定向通过。stage直接返回本次immutable字节SHA，expectedRecordDigest在任何callbacks前核验，handle自身交接ID再默认拒4。默认journal及NotebookStorage的实际retained/unchanged/复制/目标读回逐块检查取消与源epoch；原Function1散列仅保留内部测试seam适配。取消不吞异常；写后失活保留实际B与pending，不发伪receipt，可显式keepWrittenSave。

最终tmp/native-staged-save-full.log：完整301套件3312项全部通过（0失败/错误/跳过），Android测试源码编译成功。新增19个JVM行为例，含record降级/散列中取消、来源失活、target/retained/registry变化、精确journal/跨storage/重复或被恢复消费、NOTE/CANVAS、PDF migration pin间隙和锁释放；新增真实owned provider两阶段场景仅编译，未在Android执行。未打包、安装、推送或发布。无渲染/GPU改动。

后续仍需：稳定内容capture及活动事务屏障、同head cache schema2/ack和重启认领、生产Editor/旧session迁移、新建/另存copy pending、页面来源/预览/往返/backlinks/网络；整体未完成。不得只凭本片阶段证明清空旧工作缓存或采用陈旧预读来源。
