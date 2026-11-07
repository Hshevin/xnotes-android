# 导图适配渲染基线与页面引用续作（2026-11-08）

用户要求：渲染重构基本完成，先检查新架构和接口，再继续思维导图与文件关系网络；保留已有进度及已确认需求。明确授权同步 master 54f05b0f（包括主线已有 AI、画布更新）。
状态：实施中；主线同步验证与独立复核进行中，完整引用闭环未完成。
本轮边界：实现、验证、文档及本地提交；仅本次将指定 master 基线同步至 codex/mind-map，不打包、安装、推送或发布，不合入其他开发分支。

## 核实的新接口与适配原则

采用 master 已回迁并经后续修复的接口，不移入原 codex/render-lifecycle 的完整 Session/SceneStamp/DrawReceipt 方案：

- `CanvasScene.upsertImage` 入队捕获 `ImageDrawSnapshot`；图片绘制不读取可变模型。`syncChanged` 单独发布录音元数据；场景 revision 随可见元数据变化。
- `SceneUpdateQueue` 单生产者暂存完整批次；嵌套失败不发布，afterCommit 发生在提交后。`CanvasScene.batch` 用于 reset/items/order 一起发布，但不代表模型历史或 GPU 帧的原子事务。
- `DecodeMailbox` 绑定来源身份及请求；`CanvasScene.reset(documentBinding)` 区分换文档与同文档重建。CPU 位图领取与释放有单一所有者，context 重建不等于文档关闭。
- `Editor.surfacesForAttach` 和 `InfiniteEditor.surfacesForAttach` 继续负责真实宿主替换；不复用旧 renderer 的帧回调。`PaneChromeLayout` 稳定工具栏尺寸，不代替重挂保护。
- 现有导图 `MindMapView`/`MapPainter` 使用 Android Canvas 与不可变 MapDocument，不依赖 CanvasScene/GL；继续复用原生笔迹几何和 TextEditorOverlay/TextEditHost。没有必要为了“使用新架构”把导图改成第二套 GPU 编辑器。新增异步来源预览必须冻结输入并按文档/来源版本拒收迟到结果。
- 原位与放大编辑继续共享 InkGestureController、工具和 MapHistory；笔落下锁定节点及变换。文本覆盖层的受控草稿、IME composition、光标反馈、视口恢复不得被主线旧版本覆盖。
- 无限画布在主线已限定独立草稿模式，不重新开放分屏；后续导图窗格集成单独实现。

## 本次同步的冲突处理

MainActivity 同时保留导图与画布的分屏预检，拒绝时不创建新窗格；“在旁边打开”排除两类文档。PenTestRunner 合并两侧仪器测试注册。待办及授权历史保留双方记录。自动合并后复核快照再次冻结图层、readInkCopy 与遗漏提示、TextEditorOverlay 草稿接口。

## 按现有需求继续的阶段

1. **R0**：固定主线同步、冲突与回归验证，独立复核后本地保存。
2. **R1**：执行页面级引用决定。撤除所有逐对象身份及画布锚点状态；分页只校验页面 UUID，页面复制产生新 UUID，移动/重排保留。原字节升级只向直接 pages 写入编号，拒绝损坏/重复页面身份；不重编码笔迹。坏对象沿既有容错读取，独立抢救副本保留遗漏计数。整包另存/导出副本也要重铸页面 UUID，原 B2b2a“保留成员 ID”计划由此替代。
3. **R2**：接通安全激活与登记/保存/pending/SAF 恢复。保留不可变快照、源摘要、PDF SHA-256、租约与失败恢复要求；用户文件未完成原字节升级之前不能由模型 writer 偷偷启用身份。
4. **R3**：从页面缩略图/当前页添加多来源；列表与主要预览、按需刷新；来源跳转及返回图。页面为最小单位，区域只作 .xmap 中可选提示，不跟随对象。
5. **R4**：反向引用、页面删除/文件移动及权限恢复；导图与分页文档双窗格集成。引用失效保留节点正文和最后预览。
6. **R5**：局部/筛选/全库网络，内容引用、显式关联、可选同图出现分别建模；多来源概念不制造文件间依赖。继续补齐 A2 的无中心、多选、拖动连线等交互。

B2c 对象后继取消；其余已确认需求仍在 tasks/plan.md、todo.md 与 graph-network-2026-11-06.md，未因本次分阶段调整而删除。

## 验收边界

每阶段运行相关 JVM 行为回归及 Android 测试源码编译；对象数不再占页面锚点限额。页面重排/删除/复制、快照、旧字节保真、多来源、保存失败和冲突必须有行为断言。Android 实际事件/IME、真实 SAF、双窗格窗口、EGL 和 MovinkPad Pro 14 / Android 15 / Pro Pen 3 真机另列；主机通过不代表真机通过。

## R0 实际验证与复核

固定基线合并后，四个冲突按上述原则解决。新渲染器的 `WET_KEY` 匿名 CanvasItem 暂补一个不分配 UUID 的 ReferenceIdentity 容器以满足本分支旧接口；R1 撤除对象身份时一并删除。

- JDK21/既有 Android 工具链，独立输出 `/private/tmp/xnotes-mind-map-build`、项目缓存 `/private/tmp/xnotes-mind-map-cache`，离线执行 `:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin` 通过。
- 日志 `tmp/render-baseline-verify.log`；当前全量 **273 套件、2964 项**，失败/错误/跳过均为0。首次编译发现上述匿名对象接口缺失，修正后重新完整执行通过。
- 独立只读复核确认文本草稿/光标接口、再次 snapshot 图层冻结、readInkCopy 及遗漏提示保留，三处分屏预检保留。主代理修正唯一编译适配项并验证。
- 未运行 Android 仪器、EGL、窗口、真实 SAF 或目标设备；不据此确认原黑块、前缓冲、笔延迟或 IME 真机行为。
