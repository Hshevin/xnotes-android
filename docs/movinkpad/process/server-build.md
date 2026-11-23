# Linux 构建与仓库结构

正式原型仓库：https://github.com/Hshevin/xnotes-android 。本仓库根目录就是 Android Gradle 工程，`app/` 是正式 XNotes 原型；无需再进入 `xnotes/`。原独立诊断工具留在本地外层工作区，本仓库不包含其源码或原始设备日志。`docs/movinkpad/` 和 `tasks/` 是 fork 的需求与计划入口；上游文档和许可证继续保留。

上游基线：`26f42b2945df85087ab8d36a5344aa0e0c949707`。保留完整历史；后续从 upstream 获取更新，从 origin 推送到个人 fork。默认分支沿用 master。

安装 JDK 21、Android Command-line Tools，并安装以下 SDK 组件：

```sh
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" "ndk;27.0.12077973" "cmake;3.22.1"
```

按提示阅读并接受所需许可证。设置 JAVA_HOME 指向 JDK 21、ANDROID_HOME 指向服务器 SDK 后：

```sh
git clone https://github.com/Hshevin/xnotes-android.git
cd xnotes-android
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
../gradlew :app:testDebugUnitTest :app:assembleDebug
```

使用已有 Gradle Wrapper 8.13 / AGP 8.12.3，不安装全局 Gradle。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。预览限定 ARM64；不要使用 `android.injected.build.abi`。Linux 主机架构需兼容指定 SDK/NDK 主机工具。

源码不包含构建缓存、本机路径、签名密钥或 APK。服务器首次 debug 构建通常产生不同签名；需要覆盖现有安装时，安全复用原 debug 密钥，不要为绕过签名冲突卸载包含笔记的应用。

既有验证见 MOVINKPAD.md；迁移并不代表完成目标真机验收。

## 2026-10-30 fork 迁移验证

本次在 macOS / JDK 21 上执行 `../gradlew --offline :app:testDebugUnitTest :app:assembleDebug` 成功（可能复用已有增量缓存）；JUnit 报告共 2386 项，失败 0、错误 0。Linux 服务器构建与目标真机验收本次未执行。
