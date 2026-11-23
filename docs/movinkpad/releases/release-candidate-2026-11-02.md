# 可覆盖预览版的 Release 候选包（2026-11-02）

用户要求：尝试 Release 打包，并确保可直接覆盖此前 Debug 安装；不卸载、不清除笔记。
状态：已构建 Release 候选包。
本轮边界：本地 Release 打包，不发布。

## 构建身份

- applicationId：com.xnotes.movinkpad（Debug 和 Release 共用，避免回到上游 com.xnotes）。
- versionCode：68（此前交付为 67）。
- Release versionName：0.9.6-movinkpad-rc1。
- Release 保持 R8 代码优化、资源压缩，显式关闭 debuggable；仅 ARM64。
- 使用 -PmovinkpadPreviewSigning=true 显式选择本机现有 Debug 密钥。这是 Release 构建类型＋历史预览签名，不是新生产密钥签名，也不是已完成真机验收的稳定版。
- 不传该选项时仍使用已有 keystore.properties 配置；没有配置则生成未签名包，不能交付安装。不同机器的默认 Debug 密钥通常不同，构建成功不意味着能够覆盖。

旧交付包证书 SHA-256：a0453b7be6c0c8239e8616208612c412cf501da8898c7a0450cc923c8e358451

## 覆盖条件与边界

同包名、匹配的签名及不降低的版本号是更新条件；本次使用相同密钥，不引入签名轮换。密钥必须妥善保管，不要删除或重新生成。全新密钥不能直接替代现有签名；签名轮换需要单独规划验证。该候选包用于现有预览用户本地安装，未上传应用商店或远程发布。来源：[Android 更新机制](https://developer.android.com/google/play/app-updates)。

## 构建

JDK 21：../gradlew --no-daemon -PmovinkpadPreviewSigning=true :app:testReleaseUnitTest :app:assembleRelease

本机工作区生成缓存曾阻塞文件读取，本次通过临时 Gradle init 文件把 buildDirectory 定位到/private/tmp/xnotes-release-candidate/，不把临时路径写入工程配置。

## 验收要求

检查 APK 签名、包名、版本、debuggable/testOnly，真实执行 adb install -r（不加 -d/-t）；核对升级前后的应用 UID 和测试笔记 SHA-256，冷启动、重开已有笔记，并检查 crash buffer。Release 下 R8 优化可能暴露不同于 Debug 的问题；MovinkPad 实机、笔输入、大型 PDF 验收仍需单独完成。

## 验证结果

- Release 构建成功，2396 项 Release 单元测试通过（0 失败／错误／跳过），lintVital 发布检查通过；仍存在上游弃用 API、SDK 元数据及 C 编译警告，并非零警告构建。
- APK 证书 SHA-256 与旧包完全相同；包名 com.xnotes.movinkpad，版本 68 / 0.9.6-movinkpad-rc1；Manifest 无 debuggable/testOnly 标记。
- API 32 ARM64 模拟器：从 Debug 67 标准 adb install -r 升级成功，UID 始终为 10153；没有卸载或清除数据。
- 升级前后 untitled_1.xnote SHA-256 完全一致：545d6d4dc287bd1c4e28c952160cb181fe9f242395a35e3d179f299041de09a4
- Release 冷启动成功，crash buffer 为空；原 App storage 工作区保留，列表显示旧笔记，点击后正常进入该笔记编辑器。
- 交付：外层 build/xnotes/XNotes-0.9.6-movinkpad-rc1-release.apk。R8 mapping 和 APK SHA-256 保存在外层 build/xnotes/release-rc1/。
- 以上保证的是本机此前交付同签名 Debug 包的覆盖链；不同签名、不同包名或更高版本的安装不在已验证范围。
- 尚未执行目标 MovinkPad 真机覆盖、真实手写、大 PDF 和全部功能的 Release 回归；当前为候选版。
- 新版本号为 68，旧版 67 不能按普通更新直接回退。需要回退时应以相同签名和更高版本号重新构建已知良好源码，不能要求用户卸载含笔记应用。
