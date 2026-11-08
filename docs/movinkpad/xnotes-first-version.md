# XNotes 第一版：Pro Pen 3 输入与自定义按键

日期：2026-10-30。状态：第一版预览已实现，构建与模拟器验证已完成，尚未通过真机验收。

## 范围与依据

按用户要求克隆 https://github.com/shardulvs/xnotes-android，在其基础上开发；原 `app/` 继续作为独立诊断工具。第一版聚焦可持久化的手写笔记与三个侧键自定义映射，不代表全部 OneNote Android 功能或千页 PDF 规划已经完成。

基准：`project-brief.md`、`pro-pen-3-button-mapping.md`、`pen-input-analysis-2026-10-30.md` 及 `../../tasks/plan.md`。按钮设置是用户明确要求的设备扩展；OneNote Android 对应版本及侧键设置行为尚未取得实测证据，不宣称完全一致。

## 第一版验收约定

- 用户编号保持第一键 0x20、第二键 0x40、第三键 0x04；三个按键初始均不绑定。
- 每键独立选择动作；工具动作支持按住临时启用（松开恢复）与按下切换（松开保持）。撤销／重做等一次性动作每次按下只执行一次。
- 从笔 MotionEvent 的 buttonState 比较边沿，包括 MOVE、HOVER_MOVE 与独立 BUTTON_PRESS/RELEASE；同一按住状态不重复触发。
- 手指和鼠标的同名按钮位不得触发笔映射，不硬编码 deviceId。保留原始位掩码语义。
- 工具临时切换不覆盖其颜色／粗细；用户明确选择新工具后，旧按键释放不得回退覆盖新选择。
- 取消、失焦、离开编辑页时清理临时状态。多键模拟验证释放顺序，不宣称硬件支持组合。
- 设置在本地保存，重启后恢复。新增功能不需要网络、账号或云端。
- 书写保留压力输入；手掌不产生笔迹；取消的未完成笔迹不提交。书写中切换工具应保证一条笔迹的工具语义明确。
- 设置沿用黑白界面要求；已有 XNotes 其余界面如有差距须记录，不冒充全局主题完成。

## 真机操作清单（待执行）

记录设备型号、Android 构建号、配套笔型号及本版 APK 标识。

1. 新建笔记，轻重书写、快速连笔，退出重开及强杀后重开检查已保存笔迹。
2. 各键分别绑定橡皮，测试悬停按下／释放、按住后落笔、落笔中按下／释放；两种模式都执行。
3. 各键分别绑定撤销／重做，持续按住并移动，确认只触发一次，释放后再次按下可再次执行。
4. HOLD 期间手动选择其他工具，释放旧键不覆盖新选择；离开画布、打开设置、切后台后不残留临时工具。
5. 手掌接触与笔写同时进行；手指／鼠标操作不得触发笔按键映射。
6. 修改三个映射后退出并强杀重开，检查设置恢复。下拉菜单未选定新值时不改变已有映射。

参考 API：[Android MotionEvent](https://developer.android.com/reference/android/view/MotionEvent)、[高级触控笔输入](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features)。

## 实现与验证记录

- 上游仓库：`https://github.com/shardulvs/xnotes-android.git`；提交 `26f42b2945df85087ab8d36a5344aa0e0c949707`（0.9.6）。完整克隆位于 本仓库根目录，保留 MIT 许可与第三方声明，上游历史保留；MovinkPad 修改在 fork 中继续维护。
- 设置入口：Preferences → Input → Pro Pen 3 buttons。三个按键分别支持无动作、笔、荧光笔、橡皮、平移、矩形选择、套索、撤销、重做。工具支持 HOLD / SELECT；笔尖接触期间触发的动作延迟到抬笔执行，取消笔迹时丢弃待执行命令。
- 分页笔记和无限画布共用事件适配器；配置采用本地原子替换，失败时保留旧设置并提示。
- 2386 项单元测试通过；包括三键状态机、设置读写与失败恢复，以及原始真机日志中的 3907 条笔事件回放。
- API 32 ARM64 模拟器上 6 项真实 MotionEvent API 测试通过，包括事件去重、接触期间延迟动作、取消、手指／鼠标过滤、悬停转接触和状态重置。
- 模拟器手工检查：启动、映射下拉框、配置强制停止后恢复；新建笔记、模拟笔书写、返回保存、强制停止后重新打开仍显示笔迹。导出的 `.xnote` 含一条 pen stroke、35 个采样点。此验证不代表真实压感或笔延迟合格。
- lint 未通过：243 个错误；对照相同提交的未修改上游，错误种类、消息及出现次数一致，无新增错误。未将上游问题标记为已修复。
- 黑白调色板已接入，PDF 与保存笔迹不改色；全界面视觉验收仍待完成。
- 目标 MovinkPad 真机目前未连接，Android 15 / Pro Pen 3 的实体按键、压感、倾斜、悬停、防误触与书写性能均待以上清单验收。

构建记录位于忽略目录 `tmp/xnotes-build.log`、`tmp/xnotes-package-final.log`，lint 对比为 `tmp/xnotes-lint-comparison.txt`。本机 Maven TLS 下载异常通过校验官方仓库 SHA-1 后的临时本地依赖缓存解决，未把镜像地址写入项目。最终打包期间临时 dex 输出读取停顿，保留到 `tmp/xnotes-stalled-dex-output` 后重新生成。

## 交付包

- APK：`app/build/outputs/apk/debug/app-debug.apk`（从仓库根目录构建生成，不随源码提交），ARM64，调试签名。
- 应用：XNotes Pen Preview；包名 `com.xnotes.movinkpad`；版本 `0.9.6-movinkpad-preview1`（67）。
- SHA-256：`04d243d7d7a096e8681c9475d7fd797ed7e2afe148102d93e5a77d5ad7cf9f60`。
- 最终包已用 `adb install -r` 安装成功（不需要 `-t`）；重新运行 Android 测试得到 `Tests: 6, failures: 0 / PASS`。

## 真机验收（2026-11-08）

Release 98 在 MovinkPad Pro 14 / Android 15 / Pro Pen 3 上通过，见 [真机验收记录](device-acceptance-release98-2026-11-08.md)。
