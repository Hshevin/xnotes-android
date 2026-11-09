# 来源往返的主窗口导图宿主（2026-11-09）

用户要求：继续思维导图与文件关系网络，保持全部已确认需求。本片为来源打开失败保图、返回原图及后续导图双窗格接通主窗口宿主，不把宿主改造当作完整来源往返交付。
状态：已实现／已验证（347 套件、3744 项全量主机测试及 Android 测试源码编译，独立复核通过）／待真机验证。
本轮边界：代码、编译／测试、文档和本地提交；不打包 APK、安装、推送、合并或发布。

## 必要性和范围

当前 `MainActivity` 的 `singleTask` 模式会在收到启动请求时清除其上方 Activity；因此不能先从独立导图 Activity 跳回 Main，再事后检查来源是否打开成功。[Android 官方任务栈说明](https://developer.android.com/guide/components/activities/tasks-and-back-stack)。另开一个 Editor 读取来源也会重复现有文档拥有者。

首页／窗格首页及「引用此页」的查看导图入口现在复用 Main 中的 `MindMapScreen`，仍通过 `MapDocuments` 的同一实际 `MapSession` 编辑和保存。独立 `MindMapActivity` 保留兼容。没有新笔迹引擎、WebView、文档格式或存储后端。

分页湿墨 `GlWetPad` 使用 `setZOrderOnTop(true)`。图显示时不挂载原来的 native Scaffold／SplitHost，不能仅用 Compose 盖板或透明度遮住湿墨 Surface。原 Editor、文档、图层、笔迹、撤销历史及阅读视口保留；恢复显示走既有 `surfacesForAttach()`，替换湿墨／前缓冲 View 并保留用户设置。导图自身普通 View 在相机恢复前的短暂透明仅用于避免首次 fit 闪动，不用于遮盖 native Surface。

## 切换和输入合同

`Editor.awaitHostParking()` 捕获全部物理窗格及实际拥有者。共享文档去重等待保存拥有者，两个物理输入面仍分别检查；独立的另一窗格也必须稳定。等待已接受的笔记／画布写入及会话编码，沿原有语义结束文本输入，等待笔、触摸、选择预览与表格事务；不自动取消笔画或确认选区，不强制保存原文件，不要求脏文档变干净。

最后返回仅供 Main 使用的只读 `NativeHostParking` 观察，核对实际 owner、模型、身份／路径、正文版本、写入任务、输入代次、布局、相机及 View／湿墨实例。宿主在同一 Main 调度内再次核实后才改变显示。该观察不授予保存、放弃或模型采用权限。取消切换只取消等待和未采用的图租约，不取消已经接受的写入或正在书写的笔画；等待期间不显示阻断确认输入的模态框。

导图在前时，全局记录按键、旧笔按键、音量键和四指调试手势不操作隐藏的 native 文档。处理原有四指余流后，触摸原样交给图，不调用 native 的侧键点击 latch 或旧 Samsung 动作改写。原始按钮位、压力、倾角、指针 ID 和事件源保留。进入图时归还隐藏文档的临时按钮状态。

native 专属对话框、保存／打开提示和 AI 浮窗只停显，不取消其已接受任务或 AI 会话生成。「引用此页」控制器与系统选择器继续存活，仅隐藏已添加提示。外部 PDF／Markdown 导入在图显示时保留请求，并提示关闭图后继续；可以取消该导入，不在隐藏的 native 工作区中启动保存／放弃对话框。

## 会话、生命周期和相机

`MapWorkspace` 只拥有一个宿主租约；成功读取新图并证明原输入可停放后，才替换显示入口。失败、取消或迟到结果释放自己的未采用租约，保留原图和原阅读文档；同一规范 URI 复用 Entry，不重新解码。隐藏与显示不写内容历史，不释放图会话；未确认的手绘／文本／新节点草稿不允许被隐藏。

实际 Main 用 `MapWorkspaceOwner` ViewModel 保留同一个 Entry、租约、会话和 presentation，配置重建不靠文件地址重新打开，也不丢失内容撤销、选择和相机。[Android 官方 ViewModel 生命周期说明](https://developer.android.com/topic/libraries/architecture/viewmodel)。进程死亡后的冷恢复只记录图地址／标题，不承诺跨进程恢复撤销栈。

每个宿主绑定独立 token。旧 composition 的解绑、打开与异步保存关闭回调不能取消或关闭新宿主。解绑会清除旧 native parking predicate，避免 ViewModel 长期保留旧 Main／Editor 闭包。最终 ViewModel 关闭才释放租约，并等待所有已经接受的打开任务完成取消清理；保存仍沿既有恢复日志和失败保护。

`MapPresentationState` 是每个显示宿主的运行时状态，不写 `.xmap`。相机绑定实际 Session、文档 UUID 和交互代次，保存实际尺寸与 density；等待 IME 和两帧尺寸稳定后恢复一次，不 fit、不整理、不修改内容历史／重做或新选择。正文 C1 不使相机失效，重新载入或换文档不能接收旧 View 的迟到快照。文本编辑和退出恢复期间由原文本事务管理相机，不能以临时编辑比例覆盖普通视图。来源面板节点保留；返回到具体来源条目的票据尚待下一片接通。

隐藏图时，三个系统文件选择器及原请求占位仍保持注册，只卸下实际 View、编辑主体、对话框和 BackHandler；不能因为暂时隐藏而让旧选择器结果绑到新图。

## 验证及尚待验收

最终 28971 结束码 0：347 套件、3744 项，失败／错误／跳过均为 0；Android 测试源码编译成功。日志 `tmp/map-main-host-final-full.log` 与同名 `-summary.json`。相关 66529 的 37 套件、344 项也全部通过，日志 `tmp/map-main-host-native-map-related.log`。生产与测试在最终全量前冻结，后续只更新文档和本地提交。

新增实际消费者：`EditorHostParkingTest` 6 项、`MapWorkspaceHostTest` 6 项、`MapPresentationStateTest` 4 项、`MainMapInputHostTest` 2 项。覆盖双物理窗格真实笔事件、取消与已接受 IO、晚输入／布局／任务失效、湿墨实例重挂和前缓冲开关、真实提供者读写失败／租约／日志、旧宿主晚解绑、相机数值与历史／选择，以及实际 Main Window 的原始触摸和按键。

`MainMapInputHostTest` 首次运行两项确实失败：图触摸消费了隐藏笔记的侧键点击候选，特殊动作流也被改成另一组动作。记录：`tmp/map-main-host-raw-input-red.log`。修复只在四指余流处理之后为导图直接交给原 Window 分发，保留原断言。

无 Recomposer 的主机测试须主动发送真实 Snapshot 通知。早先 5 项宿主等待超时来自测试泵缺少该通知，不计为产品反例；补通知后 7002 的 5 项通过。59861 中的一项额外读取在 81468 的实际堆栈确认为 `MapStore.save` 冲突检查，来自测试循环快速推进虚拟 350 ms；只改测试泵处理当前消息，不推进未来计时，不改变产品自动保存或放宽断言。临时诊断已移除；18 项新增用例全部在最终全量中通过。

新增 Android 源码场景：实际 `MapWorkspaceHost` 的显示／隐藏重挂与相机／重做保持两项，以及真实 Main Activity 重建保持同一会话／历史／来源面板一项，均登记至 `PenTestRunner`。旧手绘、文本、节点选择和关系编辑的四套 Activity 测试改为读取实际租约的 `documentSession`，不再通过 Activity 的另一 ViewModelStore 创建未打开的第二个 MapSession。

Android 仪器、真实 Activity 重建、DocumentsUI／SAF、MovinkPad Pro 14／Android 15／Pro Pen 3、笔掌冲突、Surface 前缓冲与目标 GPU 显示、性能和真机视觉未运行。主机的 View 换实例断言和测试源码编译不能证明真机 Surface 已正确合成，也不宣称黑区或全部显示场景解决。

## 下一片立即接通

在现来源列表加入具体来源打开入口，复用来源寿命票及 Main 的实际 native owner。已开模型按文档 UUID／永久页 ID 定位，活页已删除就显示删除状态；未打开时按登记位置读取实际身份，不能按文件名／显示页码猜，也不能经通用 ensure 为身份缺失的文件补编号。保护打开必须在首次读取、晚重读及最终采用前核实目标，来源变化允许查看当前内容，失败仍保持当前图。

随后接运行时定位轮廓、具体来源返回票／原图视口和选择，再接真正的导图双窗格与反向引用。区域／画布入口、失效／权限／身份修复、预览后续、文件关系网络及无中心／同级插入／边缘平移等需求全部保留；本片未实现来源打开按钮，不把隐藏／恢复测试当作来源往返交付。
