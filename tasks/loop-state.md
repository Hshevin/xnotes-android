# 自主推进恢复点

目标：按 docs/movinkpad/graph-network-2026-11-06.md 全部阶段持续实现直至完整验收。整体 complete=false，goal active。
授权：实现、验证、文档和本地提交；不打包、安装、推送、合并、发布。
当前阶段：B1a/b 源码与主机验证完成，实际 Compose／IME／目标设备验收 pending；B1 总体 in_progress，接下来 B2。
本阶段：原位文本复用既有 TextEditorOverlay/EditingField，草稿 token 与组合输入、旧值校验／字段合并、确认一步历史与取消、相机避让和退出恢复；保留全部节点属性入口。独立代理 review_input_plan 已复核三项必修修复，无仍需先处理的确定性代码错误。
证据：tmp/text-stage-verified.log 全量2770 tests，0 failures/errors/skipped，compileDebugAndroidTestKotlin成功；最后仪器断言加强后的 tmp/text-host-compile-final.log 编译成功；资源语言键、双中文和diff检查通过。MapTextEditingHostTest 实际输入连接／resize／重建测试只编译，未执行。
运行作业：无已知Gradle作业；最后36774与43940已成功，不要重复启动。
源码：当前B1b diff已验证，待本地提交；上阶段HEAD dd5714b5。提交后读取git log取得最终提交号。
下一步：B2a 先设计／独立审核并实现稳定页／对象身份的原始JSON字节保真升级基础，再接编解码、快照、复制与后继映射；随后 C1来源入口／预览、C2导航返回／反向引用／恢复、D网络。A2无中心、连接入口／端点、多选等继续保留。
未执行：真实仪器／模拟器／设备、真实SAF、GPU／延迟、无障碍与软键盘验收。未确认文本草稿仅在ViewModel存活，不宣称异常退出已保存。整个引用闭环未完成，未出现 blocker，不标记 complete/blocked。
