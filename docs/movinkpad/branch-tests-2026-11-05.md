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
./gradlew --no-daemon --max-workers=2 \
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
