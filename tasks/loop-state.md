# 自主推进恢复点

目标：按 docs/movinkpad/graph-network-2026-11-06.md 全部阶段持续实现直至完整验收。整体 complete=false，goal active。
授权：实现、验证、文档和本地提交；不打包、安装、推送、合并、发布。
当前阶段：B1a/b源码／主机验证完成，实际Compose／IME／设备验收pending；B2a源码／主机验证与独立复核完成，B2总体in_progress。
B2a：format/AnchorManifest.kt原始UTF8片段插入reference_id；BundleAnchors.kt独立快照、marker+expectedAnchored、双ZIP视图、逐负载摘要和本地原子候选发布；JsonPull opt-in严格词法／深度／token／消费位置，默认旧解析不变。尚未接Page／CanvasItem、编辑器、打开／保存、登记或SAF，不能宣称引用闭环完成。完整计划及独立审核发现/修复见tasks/plan.md、tasks/review.md。
证据：tmp/anchor-stage-verified.log 全量2789 tests，0 failures/errors/skipped，compileDebugAndroidTestKotlin成功；diff检查通过。内部升级源码与失败边界在docs/movinkpad/reference-anchors-2026-11-07.md。独立代理review_input_plan已最终复核无剩余确定性必修。
运行作业：无已知Gradle作业；最后38902已成功，不要重复启动。
提交：B1b c5d0d4ac 已提交；B2a当前diff已验证待本地提交，提交后读git log获取号。
下一步：B2b先制定并独立审核模型／编解码／快照／复制／登记／安全存储接入计划，再实现。B2c对象确定后继另审。随后 C1来源入口／预览、C2导航返回／反向引用／恢复、D网络；A2无中心、边缘连线／端点、多选等继续保留。
未执行：真实仪器／模拟器／设备、真实SAF、GPU／延迟、无障碍／软键盘。未确认文本草稿仅在ViewModel存活。整个目标未完成，无blocker，不标记complete/blocked。
