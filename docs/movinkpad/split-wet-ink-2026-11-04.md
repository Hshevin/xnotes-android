# 退出 PDF 分屏后的实时笔迹（2026-11-04）

**最新结论：用户于 2026-11-04 确认 Release 87 解决了原报告的半屏实时笔迹问题。有效修复为分页编辑器重挂时替换整个湿墨 View；精确到系统裁剪字段/GPU 状态的底层机制尚未直接取证。以下保留各阶段调查历史。**

用户反馈：PDF 分屏后取消分屏，原分屏区域的书写不能立即显示，提笔后才刷新。Release 80 的 View 失效调整未充分解决用户设备上的现象；不能将此前 JVM 测试结果视为显示问题已修复。

## 本轮源码调整

`GlWetPad.resize()` 原先销毁并重建 EGL，但仍复用同一个独立 Surface / SurfaceControl，只调整队列尺寸。尺寸正确、EGL 回报 SINGLE，并不能证明目标 GPU 的合成器正在读取新绘制的缓冲。本轮以该复用路径为疑点，Android 12 及以上在尺寸变化时同时重建独立层及 Surface 队列，再执行 EGL 单缓冲初始化。较旧系统继续使用 SurfaceView 管理的 Surface。

销毁 EGL 时推进回调代次，使旧层排队的隐藏、清空和关闭自动刷新操作失效，防止它们作用于新层。续写路径与新笔画路径一样检查 View 和 EGL 的尺寸一致性，不把新尺寸的笔迹送往旧缓冲。

现有笔迹移交仍通过 `onSurfaceLost` 退回普通画布；不修改文档、PDF 原始数据或笔迹简化逻辑。该改动同时涉及分页笔记与无限画布共用的湿墨层，需一并回归。

## 验证边界

JDK 21，使用仓库 Gradle wrapper 8.13、AGP 8.12.3、SDK 36 和本机 Build Tools 36.0.0：

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
ANDROID_HOME=/home/empathless/Android/Sdk \
./gradlew --offline --no-daemon -I tmp/server-build.init.gradle \
  :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin
```

构建成功，2558 项 JVM 测试通过，失败/错误/跳过均为 0；Android 测试源码编译通过。`tmp/server-build.init.gradle` 为本机忽略文件，仅指定 `android.buildToolsVersion = '36.0.0'`，避免 AGP 默认请求本机未安装的 35.0.0。`git diff --check` 通过。

`adb devices` 没有连接设备，因此未运行 Android 事件测试或 GPU 显示验证。JVM 结果不能验证 Surface 合成；本次尚未确认设备根因或实际修复效果。本轮不执行设备安装，不生成交付 APK，不推送。已检查 AGENTS.md，无长期约定需要变更；README.md 补充本轮状态及记录入口。保留工作区原有的构建/预览说明修改，不纳入本轮提交。

## 待目标设备验收

记录 Wacom MovinkPad Pro 14 的系统构建、Android 版本、GPU、刷新率及 Pro Pen 3 型号。对 PDF 分别执行单页书写、左右分屏、取消左/右窗格后持续书写，确认 MOVE 阶段就能看到笔迹；反复切换并检查原分屏区域和扩展区域。补测上下分屏、同 PDF 共享视图、不同 PDF、分页笔记及无限画布混合分屏、横竖屏和侧栏尺寸变化。

比较前缓冲开/关；检查快写、长笔画、荧光笔、取消事件、撤销、保存重开以及是否有闪烁、双重笔迹、错位或明显帧耗时增加。只有目标设备结果才能确认本轮是否解决用户报告的问题。

## Release 85 后的用户反馈与继续调查

Release 85 交付后，用户确认上述队列重建调整未解决问题，并补充更小的复现路径：打开 PDF → 打开分屏文件选择首页 → 不打开任何文件就退出分屏 → 笔迹进入原空窗格区域时不能立即显示，提笔后才刷新。用户要求先确定根因再修复。本轮曾准备的整层 SurfaceView 重建及配套测试已撤回；没有提交这些行为改动，也没有据此构建新交付 APK。

用户对照结果：

- 勾选「禁用前缓冲绘制」后正常。
- 随后重新开启前缓冲并再次走空分屏流程，用户确认从第一笔起也全部正常。这是一次恢复正常的观察，不能证明永久修复，也不能解释为「第一笔延迟、后续正常」。
- 无法将目标设备接入当前开发机的 ADB；本机 `adb devices` 无设备，亦未安装 Android 模拟器或系统镜像。本轮没有取得故障时的 GPU/合成器状态。

### 源码中已确认的流程

1. `MainActivity.onSplitDocument` 创建第二 Editor 并将其加入 `paneHomes`，不打开第二份文档。`SplitHost` 根据 `homes` 判断两侧可见，因此即便 `Editor.inSplit`（要求两个文档均已打开）为 false，实际也已经执行分屏布局。这条路径不经过第二份 PDF 的打开/共享。
2. `PaneToolbarAround` 在单文档时调用 `ToolbarAround`，分屏时切换到另一 Compose 分支；分页内容中的 `AndroidView.factory` 使用 `detached(editor.surfaces)`，明确把同一个 FrameLayout 从旧父节点移到新父节点。因此需要调查的不仅是 EGL 尺寸变化，还包括原 SurfaceView 的拆挂与系统绘制节点生命周期。
3. `GlWetPad.surfaceDestroyed` 排队销毁 EGL，清空 handler/thread 引用，`quitSafely()` 后只 `join(500)`，无论线程是否仍存活都调用 `closeLayer()`。下一次 `surfaceCreated` 会在同一对象上启动新线程，EGL、layer、target 等字段仍属于该对象。若旧线程未在 500 ms 内退出，代码没有提供阻止跨代资源访问的保证。这个风险可由控制流确认；目前没有故障设备日志证明它实际触发，更不能直接把它认定为半屏现象的根因。
4. `InteractionController.drawOverlay` 在 `frontInk.live == true` 时跳过普通画布的实时笔画。因此前缓冲接管成功但显示不完整时，普通窗口不会在 MOVE 阶段补画；提笔后正常提交路径仍可能显示完整笔画。这说明现象可以由显示移交产生，但不能区分未绘制、应用裁剪和系统合成裁剪。

### 尚未证实

- 故障时 `FrontInk` 的页面裁剪是否仍为半屏；或裁剪已为全屏、却受系统 SurfaceControl 父层裁剪影响。
- 故障时 EGL 宽高、实际缓冲内容、窗口/Surface 父层裁剪是否一致。
- 旧线程是否跨过 500 ms 等待界限，或旧的布局/渲染事务是否迟到并覆盖新状态。
- 前缓冲开关恢复正常究竟来自哪一级状态重建；不能据此判定重建整个 View 就是正确修复。

交叉核对了 [Android 15 SurfaceView 源码](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-15.0.0_r1/core/java/android/view/SurfaceView.java)：父层几何与裁剪由框架和 RenderThread 管理，并与窗口绘制事务合并；[HandlerThread 源码](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-15.0.0_r1/core/java/android/os/HandlerThread.java)中 `quitSafely()` 只发出退出请求。另有 [AndroidX 的 Compose/SurfaceView 裁剪问题讨论](https://github.com/androidx/media/issues/2158)，但平台版本与设备情况不同，不能作为本设备已命中同一系统缺陷的证据。

本阶段结论：问题已缩小到前缓冲启用时的显示/生命周期路径，具体根因尚未确认。保留 Release 85 源码行为，不继续套用重建方案；后续需要能区分上述原因的证据或可控复现。未运行新的通过性测试；中止了撤回方案的构建，不把它计为验证结果。

## Android 15 模拟器复现尝试（2026-11-04）

用户要求先尝试复现，确认原因后直接修复。本次在主机沙箱外发现可用 KVM/GPU，新增隔离模拟器；此前“未安装模拟器”的环境描述仅适用于之前阶段。

- Android Emulator 37.2.13，API 35 default x86_64，`-gpu host`；系统指纹 `Android/sdk_phone64_x86_64/emu64x:15/AE3A.240806.019/12368160:userdebug/test-keys`。
- JDK 21 / 仓库 wrapper，以忽略目录中的 init 脚本额外启用 x86_64，构建现有 Debug 源码成功。仅安装到新建模拟器，包名 `com.xnotes.movinkpad.standalone`。没有制作正式交付包、安装用户设备或修改产品源码。请求 2880×1800，但系统实际布局为 1920×1800；并非目标屏幕等效测试。
- 生成无用户内容的单页 PDF，实走导入、打开 PDF、打开右侧空分屏首页、直接关闭右侧首页恢复单文档；转储三个阶段的 SurfaceFlinger 层级和应用 GL 日志。
- 父 SurfaceView 层 ID 依次为 179 → 185 → 191，湿墨子层为 182 → 188 → 194。实际拆挂会重建父层，不能把此路径解释为“只重建子层、始终保留原半屏父层”。这不排除新父层收到错误裁剪或迟到事务，但目前没有这样的实测证据。普通文本转储未提供这些无缓冲层的有效裁剪值，不能声称已验证父层全屏裁剪。
- 独立 native EGL 探针检测：扩展列表没有 `EGL_KHR_mutable_render_buffer`；要求 `EGL_WINDOW_BIT | 0x1000`、GLES3、RGBA8 的配置数为 **0**。应用在上述三个阶段均记录 `wet pad unavailable: no mutable-render-buffer config`。因此这次执行没有进入真实前缓冲绘制，不能以界面正常证明问题不存在或已经修复。

结论：本次未复现用户报告的半屏湿墨故障，纠正了父层复用的推断；尚不满足“确认原因后修复”的条件，未提交推测性的行为改动。线程跨代访问仍为独立源码风险，尚未建立它与半屏现象的因果证据。下一步需要支持 mutable render buffer 的运行环境，或由目标设备导出故障时的图层/缓冲证据；仅伪造支持扩展或改用双缓冲不能作为原故障复现。

临时构建、模拟器、探针和原始日志均在忽略的 `tmp/refresh-repro*` 中；本记录保留可复核摘要。检查 AGENTS.md 后无长期约定变更；README.md 同步本次复现状态。

## 用户授权按现有推断进行候选修复

本次处理可由源码确认的跨代资源竞争，不将其宣称为半屏故障的已确认根因，也不手动覆盖系统父层裁剪。

- `surfaceDestroyed` 立即撤销前缓冲接管资格。EGL 销毁和独立 Surface/SurfaceControl 释放都由旧工作线程按顺序执行，主线程不再在 500 ms 超时后释放仍可能被使用的资源。
- 独立层销毁超时后保留旧线程引用；重挂等待旧线程真正退出再创建新 EGL 工作线程。等待期间使用普通画布，后续再次销毁会取消待执行的重建。使用 holder-owned Surface 的回退路径必须等线程结束才归还 Surface。
- 排队的渲染工作、截图完成、笔迹移交及 resize 的 UI 通知携带 Surface 生命周期代次，拆挂后的旧回调失效；销毁时重置 dirty，防止被丢弃的渲染任务阻塞后续请求。

验证：JDK 21 / Gradle 8.13，2563 项 JVM 测试通过；Android 15 x86_64 模拟器运行 49 项 Android 测试，0 失败，runner 报告 PASS。新增 `WetPadLifecycleTest` 用真实 HandlerThread 和锁存器强制超过 500 ms，验证立即重挂不会替换尚存活的工作线程、再次销毁后迟到重试不会重启。主机 GPU 模拟器启动异常后改用 SwiftShader 执行这组测试；此测试验证线程生命周期，不验证目标 GPU 前缓冲显示。

模拟器 Debug 应用及测试 APK 只用于本机回归，没有构建新的正式 ARM64 交付包。原始日志为忽略目录 `tmp/refresh-repro/fix-build.log`、`fix-instrumentation.log`。`git diff --check` 通过。真机重点验收仍是 PDF → 空分屏首页 → 关闭首页 → 在原空窗格区域持续书写，连续多轮，并检查取消、撤销、保存重开及前缓冲开关。AGENTS.md 的长期约定无需变更。

## Release 86 真机反馈与下一候选

用户确认 Release 86 完全未改善：退出空分屏后，仍只有原空窗格区域的笔迹需要提笔才显示。跨代线程保护不足以解决此故障，不能继续把它称为已确认的设备根因。

下一候选针对复用 SurfaceView/RenderNode 的路径：分页编辑器的 AndroidView factory 先拆下原容器，再替换为全新 GlWetPad 与 FrontInk，最后挂接到新宿主。旧 FrontInk 先归还暂存笔迹，旧 pad 的 onSurfaceLost 断开，新 pad 继承前缓冲开关；保留 CanvasView、文档模型、笔迹对象和页面缓存。适用于分页笔记及 PDF；不扩展修改无限画布路径，不手动写系统父层 crop。此前父 SurfaceControl 已重建的证据仍成立，此候选处理的是同一个 Java View/RenderNode 被复用，尚未证实目标设备命中该问题。

本候选验证：JDK 21 编译及 2563 项 Debug JVM 测试通过；新增 Android 测试确认连续替换湿墨 View 时，文档/页面/已有笔画/普通 CanvasView 身份不变，前缓冲开关继承，旧回调断开。首次复用模拟器数据执行 50 项测试，有一项既有 `sharedWorkspaceRestoresBothPositionsEvenWithSecondaryOwner` 会话身份断言失败；新增测试通过。保留失败日志后清空本任务隔离测试应用数据，同源码重跑 50 项全部通过。此结果不能证明会话测试重复运行问题已修复。日志在忽略的 `tmp/refresh-repro/view-fix-*`。`git diff --check` 通过；本轮没有新的正式交付包、用户设备安装或推送。检查 AGENTS.md 无长期约定变更，README.md 已同步失败反馈与候选状态。

## Release 87 用户真机确认：原问题已解决

2026-11-04，用户安装测试 Release 87 后反馈“解决了”，并要求记录原因。对应此前已确认的复现路径：打开 PDF → 打开空分屏首页 → 退出空分屏 → 原空窗格区域笔迹提笔前不可见。Release 85、86 均未解决；Release 87 的有效差异是 `7582216f` 中的整个湿墨 View 替换。

### 原因与有效修复

应用层故障点是跨宿主复用湿墨 SurfaceView 的显示生命周期：`PaneToolbarAround` 切换 Compose 分支，`AndroidView.factory` 将原编辑器容器移到新宿主，原实现连同 GlWetPad 一起复用。重新创建 EGL/Surface 队列和串行化线程销毁都不足以恢复该设备的完整实时显示；重建 GlWetPad 和 FrontInk、重新建立 View/RenderNode 与宿主关系后，用户确认恢复。

“只有提笔才显示”来自两条绘制路径的切换：FrontInk 接管时，普通画布跳过实时笔画；前缓冲显示范围异常时，缺失区域不会由普通画布补画。提笔提交后普通页面绘制仍能显示完整笔迹，所以表现为局部刷新延迟。这不需要第二份 PDF 参与，也没有证据表明笔迹数据丢失或 PDF 损坏。

最符合观察的底层解释是旧 View/RenderNode 复用后残留了不适合新宿主的裁剪或显示状态。此项是根据版本对照和源码作出的推断，不是已读取到错误 crop 值：整个 View 替换还会重置相关生命周期状态，不能仅凭修复成功认定某个 Android/GPU 内部字段是唯一根因。模拟器已观察到父 SurfaceControl 本身重建，不应再记录成“同一个半屏父 SurfaceControl 一直没重建”。Release 86 的线程保护保留为安全约束，但不是本次已验证的充分修复。

### 验收范围与维护要求

- 验收来源为用户真机反馈；目标上下文为 Wacom MovinkPad Pro 14 / Android 15 / Pro Pen 3，测试 APK 为正式 Release 87。此次未提供新的系统构建号、GPU/驱动、刷新率或笔固件，均记为未采集；不冒充 ADB 实测信息。
- 已确认原报告场景解决；未额外确认所有分屏方向、无限画布、取消/撤销、保存重开、长时延迟及其他设备均通过。
- 保留 `Editor.surfacesForAttach()`：先拆下旧宿主，再归还旧 FrontInk 暂存笔迹、断开旧回调、创建新湿墨 View 并继承前缓冲开关。保留普通画布、文档、笔迹对象和页面缓存。后续优化不能未经目标设备复测恢复跨宿主复用 GlWetPad。
- 相关源码测试与 APK 核验沿用 Release 87 记录；本次仅补充用户验收与原因说明，没有重新构建或运行代码测试。

## 真机验收（2026-11-08）

Release 98 在 MovinkPad Pro 14 / Android 15 / Pro Pen 3 上通过，见 [真机验收记录](device-acceptance-release98-2026-11-08.md)。
