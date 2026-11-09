# 原生大笔记存储基准与事务复用（2026-11-10）

用户要求：B1 先使用超过 50 MB 的原生笔记，实测分支与 master 的保存、打开、持锁时长，再优化；保留版本核对、原 PDF 不可变与恢复路径。

状态：已完成同一 JVM 主机夹具的 master、分支优化前、优化后三组测量；已减少活跃 staged save 的重复检查。394 套 3984 项 JVM 测试通过（零失败／错误／跳过），Android 测试源码编译通过；最终提交与合并验证见[本轮合并修复记录](mind-map-merge-fixes-2026-11-10.md)。

本轮边界：JDK 21、Robolectric API 35、本机文件支持的外部 ContentProvider。未测应用自有文件后端、真实 SAF 提供商或 MovinkPad；不把主机结果当成真机性能或书写延迟结论。

## 实测结果

所有数值为毫秒、排除 1 次暖机后的 3 次样本中位数。每组使用同一份 68,879,811 字节 `.xnote`：20 张有效 PNG 合计 62,955,500 字节，256 个文本框的清单为 8,416,561 字节；包含真实图像字节与大清单。夹具 SHA-256：`93b7f9312ee2bdeaba464c301efe1245687ffe15dcc42dd9732931dc4a2f45df`。

| 场景 | master `fdc1f0fe` | 分支优化前 | 分支优化后 |
| --- | ---: | ---: | ---: |
| 实际 Editor 保存 | 254.782 | 1975.977 | 1399.874 |
| 保存期间 NotebookStorage 持锁总时长 | 0.000 | 1711.327 | 1152.785 |
| 保存竞争探针实际等待 | 0.000 | 1711.316 | 1152.773 |
| 保存编码（Editor 整数计时） | 244.000 | 258.000 | 249.000 |
| 保存发布（Editor 整数计时） | 6.000 | 1711.000 | 1152.000 |
| 打开（含登记与解码） | 246.628 | 612.251 | 583.521 |
| 打开期间 NotebookStorage 持锁总时长 | 107.476 | 432.293 | 418.483 |
| 打开首段锁的竞争探针等待 | 107.471 | 74.709 | 68.077 |

master 的实际 Editor 保存确认走原有 `writeNoteInPlace` manifest splice（实际 `lastSaveAssetsMs=0`）。另测其完整候选发布作为解释基线：总计 **720.083 ms**，NotebookStorage 持锁 **452.162 ms**，探针等待 **452.156 ms**。没有把这条额外对照当成 master 的普通保存结果。

分支实际保存由 **1975.977 ms 降至 1399.874 ms（减少 29.16%）**，全局存储锁持有总时长减少 **32.64%**。编码时间接近，变化主要在发布阶段。优化后仍约为 master 普通 splice 保存的 **5.49 倍**、master 完整候选对照的 **1.94 倍**；分支保留完整事务，B1 的额外成本没有全部消除。

本轮没有更改打开路径；打开从 612.251 ms 到 583.521 ms 的差异按主机缓存／调度／JVM 波动记录，**不宣称优化了打开**。NotebookStorage 仍使用原有全局锁；没有改成每 URI 一把锁，也没有恢复无事务的原地写入。

## 实际路径与计时方法

- 保存真实调用各版本的 `Editor.writeNoteSafely`。master 的返回值与分支的来源版本 API 用反射适配；分支获取实际 `openSource` 版本后执行完整候选发布，后续保存沿用本次成功回执。
- 打开沿各版本实际路径：master 为 `ensure` 后经 provider 流进入 `DocumentCodec.read`；分支为 `ensureForOpen`、`prepareOpen`、`PreparedNativeOpen.read`。每次重开核对页数、图片数、文本数、刚保存的正文和文档身份。
- 两份生成的源码归档安装相同计时 hook；只包住实际 NotebookStorage monitor 与 `writeNoteSafely` monitor，跳过同一锁的重入计数。正式应用源码没有 hook。
- 持锁值是实际外层 monitor 各段时长总和；操作线程自身入锁等待在本轮约为 0—0.001 ms。探针线程在首段存储锁已持有时尝试进入同一实际 monitor，记录实际等待。master 打开计 1 段存储锁，分支打开计 2 段；首段探针等待不是整次打开的最大可能等待。
- fixture 生成、Editor 初始化和探针收尾不计入操作时长；正文编码、实际发布／读取与操作自身的资产清理计入。测试使用同一组主机解码 fakes，不计像素解码或绘制。操作系统页缓存和 JVM 暖机不能等同于冷启动或设备储存。

## 本轮优化与保留的证明

优化限于拥有本次实际 `StagedSave` 的发布：stage 私持原始 A 与候选 B 的两份 `NativeSourceVersion.Inspected`。Publication 在本次调用里实际读取并核对 retained A/B 的完整哈希与实际 marker 摘要，只有与同一 storage issuer 的已消费 stage 完全一致时，才能复用原来解析出的身份／页／PDF 契约。随后仍执行当前登记表与 `NativeSaveContract` 核对。没有按路径、时间戳、dirty 状态或外部文件对象缓存事实。

以下保护保留原路径：

- stage 对可变 source/replacement 的复制、落盘及再次哈希。
- target 实际版本与 A/B 匹配；写前 target 再读核对。
- 回调后和发布后的 retained A/B/marker `unchanged` 全量核对。
- 写入实际目标后的读回哈希、登记、最终目标读回及清日志条件。
- PDF 原件／引用契约与保护、失败保留恢复资料、取消和一次性 stage 消费。
- 冷恢复或没有本次 stage 的调用继续完整 inspect，不借历史 memo 授权。

新增两项真实 IO 边界测试：完整读取 target fingerprint 后，将 retained 候选替换为同身份、不同正文的有效 ZIP并恢复原 mtime，仍须在写 target 前拒绝并保留日志；在同一边界取消，须保持 target、retained A/B 和 marker 原字节。原有跨 issuer、重复消费、caller 临时候选改变、登记表改变、stage／target 篡改与恢复测试继续覆盖。两项新增用例及394 套 3984 项 JVM 全量用例已经通过，Android 测试源码编译通过；实际 Android 与真机未运行。

## 源码与原始证据

[可重复运行配方](../../scripts/benchmarks/native-storage/README.md)包含夹具、同一计时 helper、资源与参数配置、受限归档生成和中位数汇总，不依赖一次性的临时脚本。

- [master 原始 JSONL](benchmarks/native-storage-2026-11-10/master-fdc1f0fe.jsonl)。
- [分支优化前原始 JSONL](benchmarks/native-storage-2026-11-10/branch-before.jsonl)。
- [分支优化后原始 JSONL](benchmarks/native-storage-2026-11-10/branch-after.jsonl)。
- [基线源码摘要](benchmarks/native-storage-2026-11-10/baseline-source-manifest.json)：master 固定为完整 `fdc1f0fe` 提交，优化前 native 管线主要六份源码与 `a571080c` 一致，包含实际逐文件匹配证明。
- [优化后源码摘要](benchmarks/native-storage-2026-11-10/branch-after-source-manifest.json)：以 `3877c4bb180828d74ea935a2fb1079d075ebd84b` 加当时已冻结的源码覆盖构建；记录 hook 前后各文件 SHA、覆盖清单和配方 SHA。全部六份 hook 前摘要已与冻结工作树逐项一致，实际 `writeNoteSafely` 入口与优化前逐字符相同。最终 B1 提交需由主记录核对这些摘要后绑定。
