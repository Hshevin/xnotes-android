# 保存后重新核实来源再采用（2026-11-09）

用户要求：引用与来源打开保护当前内容，替换前等待保存并防止迟到读取回退已写版本；沿新渲染架构继续完整来源闭环，不打包 APK。
状态：已实现／已验证；8 项真实 Editor 主机场景及 Android 测试源码编译通过，独立源码复核通过；实际平台和大型文档性能待验收。
本轮边界：真实 Editor／现有 storage 接口、主机与共享事件测试、Android 测试源码编译、文档和本地提交；不打包、安装、推送或发布。

## 直接修复规格

1. 保留完整 prepare 失败时不替换当前工作区的流程。settle 成功后，在 Main 固定所有相关实际 owner／模型的 bodyRevision、路径与 native write job 身份；最后还核原 request、runtime owner、slots、dirty、在途编码和活动输入。
2. IO 复用 NotebookStorage.openSource，取得同一实际不可变来源的 kind／UUID／canonical location／SHA。与原 Prepared 相同则保留原解码；同 kind／UUID／location 的内容版本改变则从本次 source 真正解码并保护新的 Prepared，用其模型及版本，不能仅给旧模型换 version。身份或类型更换拒绝自动采用，保留旧工作区。
3. 返回 Main 后上述 stamp 与 ready 条件仍须匹配，随后无挂起地采用。验证期间又编辑并保存，即使 dirty 已恢复 false 也拒绝旧请求。取消／失败／验证变化均清理未采用的 prepared 资产与临时租约，不清当前文档或原件。
4. 覆盖同URI、canonical alias、另一窗格同源与画布重开；长读取期间再次保存／新建／取消不回退当前模型。大型 PDF 的相同版本不再解码第二次；本片仍需一次实际来源核实，不使用名称或 mtime 代替版本。

审核落实：刷新 B 完全复用 created/path/displayName/dirty 设置；B 在转交 holder 前由局部 finally 拥有，失败／取消和 source.close 均不漏资产；转交后 Main 从 holder 重新取 Prepared，旧 A 清理失败也保留 B 的最终清理路径。stamps 覆盖所有相关 owner 的两个 native job 身份，并重核所有实际窗格 runtime owner；相同 SHA 仍关闭本次 source 及租约。

本片不激活 native4／新工作会话，已开始 native IO 的跨 Activity 全链采用屏障和复制／另存持久切换继续后续，不把来源重核视为这些流程已经完成。

## 实施与证据

openNative 在旧拥有者 settle 后记录实际模型 revision／路径和两个 native job 身份，IO 重新观察当前原生来源；内容版本变更时从该 source 真正读取 B，并保持独立资产／原件租约。最终 Main 从 holder 读取当前 Prepared，核实 runtime owner／stamps／slots／ready 后同步采用，避免 A 的模型与 B 的版本错配。

`tmp/source-reopen-baseline-red.log` 在已提交的旧 Editor 上执行同 URI 笔记／画布、canonical alias、首页另一窗格四例：全部在磁盘 B 检查通过后，以“采用模型应含 1 笔、实际为 0 笔”失败。临时测试源在 finally 自动恢复，并逐字节核对恢复结果。

新实现 `tmp/source-reopen-focused.log` 的 8 例全部通过（0 失败／错误／跳过），`:app:compileDebugAndroidTestKotlin` 成功。除上述四个真实保存／重开正例，还覆盖身份替换拒绝、验证期间实际保存 C 且恢复 clean 后仍拒绝旧 Prepared、同 SHA 保留确切候选资产目录，以及取消最后核实后保留已写 B 并清未采用目录。负例用受控回调、latch 和真实 codec／storage／SQLite，无 sleep 猜测。

同 SHA 不二次解码的目录 oracle 使用普通无 PDF 笔记；实际仍增加一次来源 snapshot／散列核实，不声称零额外大包 IO。大型 PDF 内存／性能、真实 SAF 后续外改、实际 Android 仪器和 MovinkPad Android 15／Pro Pen 3 尚未运行。本片没有新增 Surface／前缓冲／GPU 路径，也不提供跨 SAF 外部写入的原子 CAS。
