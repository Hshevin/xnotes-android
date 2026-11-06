# 自主推进恢复点

目标：按docs/movinkpad/graph-network-2026-11-06.md全部阶段持续实现直至完整验收。整体complete=false，goal active，无已确认blocker。
授权：实现、验证、文档和本地提交；不打包、安装、推送、合并、发布。
当前阶段：B1a/b源码与主机验证完成，实际Compose／IME／设备pending；B2a和B2b1源码／主机／独立复核完成，B2总体in_progress。
B2b1成果：Page/七类CanvasItem的ReferenceIdentity一次性线程安全容器；显式模型开关，snapshot冻结与独立deepCopy；Doc/Canvas codec可选ID+marker，pending直接带ID、raw→实际成员完整对账、未知/缺资源/缺ID/版本异常拒绝部分可写模型；本read临时文件失败清理，不删借入/受管PDF；精确版本及PDF完整性/引用式夹带PDF歧义保护。readInkCopy私有sourceID映射先对账后独立doc/member身份，不reset、不复制点数组；legacy恢复仍legacy，Editor唯一入口已更新并本地化错误。
尚未激活：普通文档打开/登记/SAF的anchor升级、新文档创建开启模式、raw包复制marker重绑定与可信expectedAnchored记录，均为下一B2b2；B2c碎片/后继未做。不得因模型源码存在就宣称引用闭环可用。
证据：tmp/anchor-codec-final.log全量2808 tests，0 failures/errors/skipped，compileDebugAndroidTestKotlin成功；diff检查通过。新ReferenceCodecAndroidTest已加入PenTestRunner，真实PNG/平台身份仅编译未运行。详细源码边界在docs/movinkpad/reference-identity-model-2026-11-07.md。
独立审核：review_input_plan计划/实现多轮只读复核，PDF缺失/Double近1/再次快照/引用式夹带PDF等必修均有RED重现并修复；最终针对复核无剩余确定性必修，见tasks/review.md。
作业：无已知Gradle作业，最后93454成功，不要重复启动旧作业。
提交：B1b c5d0d4ac、B2a c93f8129已提交；B2b1随本恢复点同次本地提交，以git log读取最终号。不推送合并。
下一步：先制定并独立审核B2b2安全存储激活/新建/复制/expectedAnchored状态/损坏与失败恢复计划，再实施；然后B2c对象确定后继，再C1来源入口/预览、C2往返/反向引用/恢复、D文件网络；A2无中心/边缘连线/端点/多选等继续保留。
未执行：真实仪器/模拟器/设备、真实SAF、GPU/延迟、无障碍/软键盘、完整引用链验收。整个目标未完成，不能标complete/blocked。
