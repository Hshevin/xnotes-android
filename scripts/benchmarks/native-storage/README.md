用户要求：用大于 50 MB 的真实原生笔记，对比保存、打开和持锁时长，先测量再优化。

状态：本目录保存可重复运行的主机基准配方；原始数据与结论见 `docs/movinkpad/mind-map-merge-fixes-2026-11-10.md`。

本轮边界：JDK 21 / Robolectric / 本机文件支持的外部 ContentProvider。只测存储与解码，不测渲染；真实 SAF、Android 与 MovinkPad 性能待测。

## 来源与隔离

默认基线固定为：

- master：`fdc1f0fe17415788cecfe63b08945f50031d325f`。
- 优化前分支：`a571080c3512ee6bc6888423551608a1a97ce76b`。首次测量归档还有当时的其他问题修复；`baseline-source-manifest.json` 已逐项证明实际计时的主要六份存储／编辑器源码与该提交一致。

在仓库根目录执行：

```sh
python3 scripts/benchmarks/native-storage/prepare_archives.py
```

脚本以 `git archive` 建立 `tmp/native-storage-benchmark/master-fdc1f0fe` 和 `branch-before`；既有目录须明确加 `--replace` 才能重建。它只接受仓库 `tmp/` 下的简单目录名，不跟随目录链接，不修改原工作树，也不复制签名文件。`local.properties` 仅复制 SDK 路径。

每份归档只编译同一 `fixture.kt` 与各提交原有的 `Fakes.kt`。`helper.kt` 只安装到这些生成的归档；它包住实际的 NotebookStorage monitor 和 Editor.writeNoteSafely monitor，跳过同一 monitor 的重入计数。`init.gradle` 为两边统一启用 Android 测试资源、3 GB 测试堆和显式参数传递。正式 `app/src/main` 没有计时 hook。

## 运行

先从仓库根目录保存绝对路径，再进入要测量的归档；每个归档使用独立 Gradle 项目缓存与自身构建目录：

```sh
benchmark_repo="$PWD"
cd tmp/native-storage-benchmark/master-fdc1f0fe
JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew --no-daemon \
  --project-cache-dir "$benchmark_repo/tmp/native-storage-benchmark/cache-master" \
  -I "$benchmark_repo/scripts/benchmarks/native-storage/init.gradle" \
  -Dxnotes.storageBenchmark=true \
  -Dxnotes.storageBenchmark.root="$benchmark_repo/tmp/native-storage-benchmark/results" \
  -Dxnotes.storageBenchmark.label=master-fdc1f0fe \
  :app:testDebugUnitTest --tests com.xnotes.storage.NativeStorageBenchmarkTest
```

分支测量替换归档目录、项目缓存目录和 label，保持同一 results 路径。普通单元测试不启用此基准；没有显式 opt-in 时该测试跳过。不生成或安装 APK。

优化后的可提交版本用准确提交号创建，例如：

```sh
python3 scripts/benchmarks/native-storage/prepare_archives.py --name branch-after --ref HEAD
```

测试尚未提交的候选时可加 `--overlay-worktree`。脚本只覆盖当前有变更的 `app/src/main/` 文件，并在 `benchmark-source-manifest.json` 记录提交、覆盖清单、计时 hook 前后各文件 SHA 与配方 SHA。采用候选后，须核对 hook 前的 SHA 与最终提交一致。

## 数据与读法

首次生成的笔记包含 20 张逐行生成的有效 PNG（合计 62,955,500 字节）与 256 个文本框（清单约 8 MB），整个 `.xnote` 为 68,879,811 字节。所有版本复用同一 fixture，其 SHA-256 为 `93b7f9312ee2bdeaba464c301efe1245687ffe15dcc42dd9732931dc4a2f45df`。图像生成与测试设置不计时，不分配巨型位图。

每项先暖机 1 次，再记录 3 次。保存实际调用各版本的私有 Editor.writeNoteSafely：master 必须走原有 manifest splice 快路；分支必须走已观察来源版本的完整发布。master 另测完整候选发布作解释基线。打开包含各版本实际的登记／来源准备及 DocumentCodec 解码。每次重开核对页面、图片、文字数量、刚保存的正文与文档身份。

结果为 `results/<label>.jsonl`。`total_ms` 包含该操作与其清理，不包括探针收尾等待；`storage_lock_held_ms` 为实际外层存储锁各段持有总和；`storage_lock_wait_ms` 为操作线程自身的入锁等待；`storage_lock_probe_wait_ms` 为竞争线程在首段存储锁到达时的实际等待，不能当成打开操作所有阶段的最大可能等待。Editor 保存锁另计。主机操作系统页缓存和 JVM 暖机都会影响数值，三次样本的中位数不是设备性能承诺。

```sh
python3 scripts/benchmarks/native-storage/summarize.py
```

汇总排除暖机，输出中位数并拒绝比较不同 fixture SHA。原始测量数据位于 `docs/movinkpad/benchmarks/native-storage-2026-11-10/`；保留原始每次数据，不能只存推算的复制次数或一个平均值。
