# 分支独立测试包（2026-11-05）

用户授权拉取远端主分支和新增功能分支，分别本地打包测试；不再构建旧 AI / 分屏专用测试包。第二次 fetch 新发现按键录入分支，也纳入本次交付。各分支按独立提交构建，没有互相合并功能；主工作区已快进到 `origin/master` 的 `c82591b5`，原有三个未提交文件保留。

## 包身份

| 分支 / 源码提交 | 应用名称 | 包名 |
| --- | --- | --- |
| `master` / `c82591b5` | XNotes master 测试版 | `com.xnotes.movinkpad.branchtest.master` |
| `drawing-layers` / `9d114702` | XNotes 图层测试版 | `com.xnotes.movinkpad.branchtest.layers` |
| `codex/recordable-pen-shortcuts` / `76d6c228` | XNotes 按键录入测试版 | `com.xnotes.movinkpad.branchtest.penshortcuts` |

三包均为 Debug、versionCode 1、ARM64，应用 APK 不含 `testOnly` 标记。签名沿用原证书 SHA-256 `A0:45:3B:7B:E6:C0:C8:23:9E:86:16:20:86:12:C4:12:CF:50:1D:A8:89:8C:7A:04:50:CC:92:3C:8E:35:84:51`。可同时安装并与正式版共存，设置、权限和 AI 历史各自独立。不会自动迁入其他包的数据。

交付文件位于忽略提交的 `build/branch-tests/2026-11-05/`，文件名包含分支、源码提交及 `test1-arm64`。后续同包名更新应递增测试 versionCode，并沿用签名。

## 功能差异

- master：包含 AI 多供应商配置与会话模型选择更新。
- 图层：分页/PDF 按页、无限画布按文档管理图层，支持显隐、锁定、层序、对象移动及撤销。尚未合入 master 的多供应商提交。旧版不能保留多层文件的层结构，不要用旧版回存需要保留图层的测试笔记。
- 按键录入：实体按键录入、命名及 HOLD/SELECT 映射，包含失焦与重复事件修复。旧 Pro Pen 3 映射不读取，需重新录入；尚未包含多供应商或图层功能。

## 构建复现

每个分支使用独立 Git worktree，拥有独立 `build/`、`app/build/` 和 `app/.cxx/`。本次工作树位于忽略的 `tmp/branch-tests/{master,drawing-layers,pen-shortcuts}/`。不要在同一工作树内并发修改包身份或切换源码。

使用 JDK 21、Gradle wrapper 8.13、AGP 8.12.3、SDK/Build Tools 36/36.0.0、NDK 27.0.12077973、CMake 3.22.1。在每个工作树配置忽略的 `keystore.properties` 和原密钥路径；不复制或提交密码。初始化脚本只覆盖测试包身份，不改分支业务源码：

```sh
# 在独立工作树中执行；将路径替换为主工作区的实际路径。
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/home/empathless/Android/Sdk
./gradlew --no-daemon --max-workers=2 --project-cache-dir tmp/gradle-arm64 \
  -I /home/empathless/Documents/xnotes-android/scripts/branch-preview.init.gradle \
  -PbranchTestId=master '-PbranchTestLabel=XNotes master 测试版' \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

另两包分别使用 `layers` / `XNotes 图层测试版` 和 `penshortcuts` / `XNotes 按键录入测试版`。构建输出是各工作树的 `app/build/outputs/apk/debug/app-debug.apk`。未使用 IDE 注入 ABI 或 testOnly 参数。`assembleDebugAndroidTest` 生成的仪器 APK 仅供测试，不是交付应用。

旧 AI / 分屏独立交付 APK 本地未发现；未删除正式 Release 85–89 APK、远端分支或历史源码。本次不安装用户设备、不推送、不发布 GitHub Release。

## 验证

原始构建日志保存在忽略目录 `tmp/branch-tests/logs/`。

| 分支 | JVM（失败 / 错误 / 跳过均为 0） | Android 15 x86_64 模拟器 |
| --- | --- | --- |
| master | 2607 | 50 项，PASS |
| drawing-layers | 2609 | 51 项，PASS |
| codex/recordable-pen-shortcuts | 2618（含 17 项 Robolectric Android 事件契约） | 67 项，PASS |

三包的 JVM、ARM64 应用和仪器 APK 构建全部成功。应用包经 `aapt` 核验 applicationId、名称、versionCode、仅 ARM64 及无 testOnly；`apksigner verify --print-certs` 与 `zipalign -c -P 16 4` 均通过。校验输出和 SHA-256 清单位于交付目录。

模拟器专用包另存各工作树 `build/emulator-app/`，只把 ABI 改成 x86_64，未作为交付 APK。测试系统：本地 emulator-5580，Android 15 / API 35，`Android/sdk_phone64_x86_64/emu64x:15/AE3A.240806.019/12368160:userdebug/test-keys`，SwiftShader，显示尺寸 1600×1000，无实体笔。模拟器启动期间一次安装因 package service 尚未就绪失败，待系统启动后安装和回归正常。

MovinkPad Pro 14 / Android 15 / Pro Pen 3 的实体按键录入、多层实时遮挡、湿墨层 GPU 效果，以及真实 AI 供应商请求仍待用户验收；不能从 JVM、Robolectric 或 x86_64 模拟器通过推断目标设备兼容性。

## test2：远端分支更新

用户再次要求拉取远端分支并打包。此次 fetch 只有两个功能分支更新，master 没有新提交；主分支正式包仍为 Release 90。两工作树均快进更新，没有合并不同功能分支。

| 分支 | 源码提交 | 包名 | versionCode | APK |
| --- | --- | --- | --- | --- |
| drawing-layers | `8e58f396` | `com.xnotes.movinkpad.branchtest.layers` | 2 | `XNotes-drawing-layers-8e58f396-test2-arm64.apk` |
| codex/recordable-pen-shortcuts | `142ce7d3` | `com.xnotes.movinkpad.branchtest.penshortcuts` | 2 | `XNotes-pen-shortcuts-142ce7d3-test2-arm64.apk` |

交付目录：`build/branch-tests/2026-11-05/test2/`。均保留 test1 的应用名称、包名与原签名，ARM64 Debug、无 testOnly；用于覆盖各自 test1。没有安装用户设备、推送或发布 GitHub Release。

- 图层新增常驻侧栏、拖动排序、单层内容缩略图、两档预览大小及双击重命名等交互。
- 按键录入修复分屏和关闭窗格时的身份交接、笔画/按键取消处理、多键录入约束，以及 HOLD 临时工具不改变工具栏正式选择高亮。
- 本次初始化脚本新增 `-PbranchTestVersion=2`，默认仍为 1，显式校验 Android versionCode 范围。后续同包更新须递增该参数。
- 首次构建两个分支均在 `dexBuilderDebug` 遇到旧 `build/emulator-app/.../R.jar` 路径残留。保留失败日志后执行 `:app:clean`，并给 ARM64 加 `--project-cache-dir tmp/gradle-arm64`；模拟器使用 `--project-cache-dir tmp/gradle-emulator`，输出仍为独立的 `build/emulator-app/`。不修改业务源码。
- 原始日志保存在 `tmp/branch-tests/logs/test2/`；交付目录保留 APK 核验和 SHA-256。最终验证结果如下。

最终验证：

| 分支 | JVM | Android 15 x86_64 仪器测试 |
| --- | --- | --- |
| drawing-layers | 2615，失败/错误/跳过均为 0 | 53 项，失败 0，PASS |
| codex/recordable-pen-shortcuts | 2641，失败/错误/跳过均为 0 | 90 项，失败 0，PASS |

- 两包 ARM64 构建成功；模拟器包独立构建成功。沿用上述 Android 15 / SwiftShader / 1600×1000 本地模拟器，各包 `adb install -r` 成功后清理模拟器测试夹具再运行完整仪器测试。没有连接或安装用户设备。
- 与实际 test1 APK 比对，包名不变、versionCode 1 → 2、原签名一致；`apksigner`、`zipalign -c -P 16 4`、ARM64 与无 testOnly 核验通过。设备覆盖升级和目标笔/GPU 效果仍待真机验收；模拟器不能证明实体笔按键、侧栏触控/动画或密集图层性能。
- 图层包 53,592,384 字节，SHA-256 `5567ae5b5a85743494dc424dcbf48247ce9830cb535625243ea91b858f8ef74f`。
- 按键包 53,533,264 字节，SHA-256 `86e742dc4b74bfabed130ae0337ddb6fca1f8ce4e12f9499e98afe91a881ee2d`。
