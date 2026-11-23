# 双页码版本启动崩溃修复（2026-11-02）

用户要求：未记录（本文为双页码版本启动崩溃修复）。
状态：已修复；验证见正文。
本轮边界：未记录。

根因：Editor 的 init 调用 rebuildPdfSource → loadBodyStartFromPdf → numberingVersion++，但 numberingVersion 的 Compose 状态委托声明位于 init 之后，此时尚未初始化，导致 NullPointerException。自动页码初始值同样需要在 init 前完成初始化。

复现：API 32 ARM64 模拟器安装上一版 APK 后启动，crash buffer 捕获 Editor.getNumberingVersion → loadBodyStartFromPdf → rebuildPdfSource → Editor.<init>。原始日志：外层 tmp/startup-before-crash.log（本地临时证据）。

修复：把三个页码状态字段移到调用 rebuildPdfSource 的 init 前。不改变包名、签名配置、笔记格式，不清空任何数据。

交付回归门槛：单元测试及构建后，必须在 Android 运行时覆盖安装、冷启动、检查进程存活及 crash buffer；am start 返回 Status: ok 不能独立证明启动成功，本次旧包也返回该状态。模拟器结果不替代 MovinkPad 真机验收。

验证完成：独立临时构建目录中完整单元测试与 APK 构建通过；API 32 ARM64 模拟器从故障包覆盖安装成功，启动后进程存活、crash buffer 为空，进入首页并新建笔记成功。跳转弹窗确认当前页起始按钮可见，点击并按正文页码跳转无崩溃。MovinkPad 真机验证待执行。交付包：外层 build/xnotes/XNotes-2026-11-02-startup-fix.apk。
