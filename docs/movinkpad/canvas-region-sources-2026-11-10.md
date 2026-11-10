# Canvas 固定区域来源完整流程（2026-11-10）

用户要求：继续完整思维导图需求，接上 Canvas 实际区域选择／套索→已有或新图／概念→保存重开→裁切预览→临时定位与返回。
状态：源码／相关主机测试与 Android 测试源码编译已 verified，独立复核通过；整体需求未完成。
本轮边界：基线 4f413d16，本树实现、相关编译／测试、文档、本地提交；不打包 APK、安装、推送、合并或发布。

## 可观察结果

Canvas 的已完成选区／套索与截图区域菜单提供“引用此区域”。入口固定实际世界坐标矩形，随后复用已有挑图、新图、已有概念／新引用节点、一次导入撤销与保存失败只重存流程。成功保留原阅读画面，用户主动查看图才切换。引用只存文档 UUID 与固定范围，不存对象编号或跟随关系，不改变原 Canvas 内容。

重开 `.xmap` 后仍保原矩形及最后预览；刷新只显示此矩形中的实际已保存画布内容，矩形外内容不入图。空区域仍可定位，标为当前为空，不当删除。点击具体来源或单来源引用节点双击，在单文档 Canvas 实际挂载稳定后定位固定范围并显示短暂对比轮廓；新的笔／触摸输入退休延迟定位。返回同一图、相机、选择与来源条目，沿上一阶段 Canvas 返回策略保未保存内容。

## 范围与兼容契约

- 新增独立 `SourceScope.CANVAS_REGION`：文档 UUID＋必填有限、正宽高的世界 Rect（允许负坐标，沿现坐标上限），pageId／lastKnownPage 为空。FILE 仍禁止 region，PAGE 规则不变；文件卡片身份来源仍只允许 FILE。
- 当前来源或 lastPreview.target 含新 scope 时写 `.xmap` v5，其他继续 v3/v4；v5补齐组织／文件卡片字段，旧v1—4显式拒绝新scope。预览保实际描绘的旧target；改范围不把旧PNG冒称新范围。
- 打开请求携带明确的 requiredKind=CANVAS，首读、最终读核及 live 模型都验证；不得通过放宽无page region把普通NOTE整文件送到首页。Canvas仍不进入分屏，沿严格完整Canvas参考解码、UUID/位置/源版本/原字节保护。
- 预览按原固定矩形裁切，不按内容重新取景、不截短到内容边界；只在严格完整解码后证明区域当前为空。隐藏图层按实际可见状态绘制；失败／取消保旧PNG，不把网格当内容。

## 实现切片

1. 模型／编解码／预览：MapSource、MapSourceTarget、MapCodec/MapGraphJson、SourcePreviewBackend与NativeDocumentPreview扩展，三套范围文字一致。以真实PNG和raw JSON验证，不另建渲染引擎。
2. 来源准备与共用导入：PageReferenceRequest保原具体类型，加入小SourceCreationRequest适配；CanvasRegionReferenceRequest独立持实际InfiniteDocument／会话／身份／路径和固定Rect。Controller/Flow共用目标选择、导入历史和保存重试，不伪造Page。清洁来源只读核实际已保存版本；有尚未保存内容时明确要求保存。已知完整来源可由显式按钮用实际 expected 版本严格原位保存后继续，绝不走弱 stamp 或 fork；普通读取仍宽容，但只有实际完整解码的采用版本、或同一完整模型固定快照成功发布的版本，才能建立 CanvasSourceObservation；丢项模型不授新来源保存权限。观察记录捕获 revision，晚到成功B只更新原owner/model/identity/path的B基线，C1仍dirty需重试。无路径或无可信保存基线经专用选择器显式“保存副本并继续”：NativeCreation 核可读空目标、固定候选与新 UUID；实际发布成功且原请求／owner／模型／路径／身份仍有效、输入稳定后，同 Main 采用同一模型的新路径／身份，保留撤销及晚到内容，再按原固定 Rect 签新请求，重新选择目标图。identity 导致的 revision 增量精确换算，仅 B 取得保存观察、C1 仍 dirty。取消或无法采用只保独立文件，不自动采用、不删 provider 目标；写后失败保同一 typed pending，显式续完不重编码。已知丢项模型仍拒绝此分支。确认加入前重新核源与目标原票，取消／迟到选择器不得重绑。
3. 定位与实际入口：Canvas菜单从真实selection.box.corners或captureRect获取worldRect，绝不从selectionMenuRect反算。新CanvasReferenceLocator复用viewport、retained toolOverlay与样式token，等待真实GL/overlay宿主、初始视口及两帧稳定，定位不改正文／选择历史；卸载/新输入/换模型分别退休，不复用旧GL回调。

## 验证与代码风格

沿现 Kotlin 不可变目标、typed ticket、Main同步采用与IO读取边界：`MapSource(..., scope=SourceScope.CANVAS_REGION, region=fixedRect)`，正文／笔迹不为引用重新编码。新字符串走values/values-b+zh+Hans/values-zh-rCN，间距字号token与48dp规则沿用。

相关验证包含：v5往返和旧版本拒新scope、Android JSON平台语义；负坐标／旋转选区固定范围、真实PNG内外内容与图层／空区／未知对象；已有/新图与既有概念、多来源正文、一步undo/redo、失败retry不重复、迟到picker；实际cold/live区域打开、Window挂载／两帧／首次新笔取消、返回同图／源字节保护。保原PAGE与整文件Canvas相关回归，不重复未变全仓验证。

本机命令沿隔离输出与缓存：

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ANDROID_HOME=$ANDROID_HOME ./gradlew --offline --no-daemon --project-cache-dir /private/tmp/xnotes-mind-map-region-continue-cache -I tmp/build-mind-map-region-continue.gradle :app:testDebugUnitTest --tests '*CanvasRegion*Test' --tests com.xnotes.pen.PageToMapFlowConsumerTest --tests com.xnotes.pen.NativeCanvasReferenceHostTest :app:compileDebugAndroidTestKotlin
```

真实Android／SAF、配置重建／杀进程、MovinkPad Pro 14／Android 15／Pro Pen 3、掌触／Surface／GPU／性能未运行，单独保留验收。

## 原范围保留

本片先接Canvas区域完整往返。分页区域入口／范围调整、导图来源打开／真正导图双窗格、来源精确恢复、完整引用备份、主要预览／页目录缩略图、同级插入／边缘平移及其他原需求继续；整体 active，不以单阶段替代完整目标。

## 实际验证与审核

- 新树 `<工作树 mind-map-region-continue>/xnotes`，分支 `codex/mind-map-region-continue`，从 `4f413d16` 创建；45 个当时未提交文件经 SHA-256 校验复制，原树原样保留并在最终再次核对。
- 32941：生产／Android 测试源码编译通过，208 项中 206 通过；两项新夹具假设错误（PNG 场景未启用真实光栅模式、错类型被拒时本就不会尝试渲染），修正后在 68191 的三个流程／预览用例通过。原产品边界断言保留。
- 独审发现并修复两个实际入口问题：Canvas 无路径保存误入 NOTE 专用另存入口；已经进入宿主的 pending 定位在卸载后复活。后者将旧 Locator 临时恢复，68191 精确新增用例真实失败；立即恢复修复版，最终 6 项 Locator 全通过。
- 78922：251 项中 250 通过，Android 测试源码编译通过；1112 个 Kotlin／Java／资源与构建输入运行前后全同。唯一超时是后加持久化流程把轮询 delay 放入夹具不推进未来回调的 Main；只移回测试 worker，未改产品或降低断言。
- 81852：仅重跑该一项，终态成功。按逐 case 替换合并 **251 项，0 失败／错误／跳过**；复核清单 1112 个输入与当前全同，其余有效结果未重复运行。证据在忽略目录 `tmp/canvas-region-{final,recheck,verified}-summary.json`、相应日志及 `-recheck-inputs.json`。
- 计划、副本安全采用补充边界、实现及证据由独立代理复核通过，两个 Required 均闭合。额外交叉检查了实际 Main 菜单与 picker consumer、分页默认路径、三个 locale。

实际覆盖：负世界坐标／旋转选区与截图范围、旧格式 raw 门禁、真实 PNG 裁切和隐藏层／网格／空区／未知项、原位严格保存与同身份外改拒绝、B＋晚到 C1、完整／宽容丢项观察、typed 空目标副本和同候选续完、取消／旧 picker／换模型拒采用、已有概念追加与一次撤销、新图保存重试不重复、正式预览刷新保存冷重开、实际来源打开与同图返回、真实 Window 两帧和输入／卸载退休。

Android 新增 2 项 raw 格式语义与 2 项菜单事件已登记 Runner，**仅编译，未执行**。主机 Window／光栅与真实 picker contract 不能代替 Android Compose 重组／系统选择器／SAF、配置重建与杀进程、MovinkPad Pro 14／Android 15／Pro Pen 3、掌触／Surface／GPU／大图性能验收。未生成 APK、安装、推送、合并或发布。

本地提交：`60dfacfa`（格式／预览／完整读取）、`c711b11b`（实际入口／保存／副本采用／定位返回）、`8c787750`（主机与Android事件测试源码）。
