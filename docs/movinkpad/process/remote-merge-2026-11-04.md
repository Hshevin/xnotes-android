# 远端更新合并（2026-11-04）

用户要求：未记录（本文为 origin/master 更新合入本地）。
状态：已合并；验证见下文。
本轮边界：未记录。

- 将 `origin/master` 的 6 个提交（截至 `3ea4f87f`）合入本地 `9537ceca`，保留本地 11 个提交的历史。
- README 同时保留 Release 85 空白会话清理与远端多截图、浮窗历史、原生联网工具说明；AGENTS.md 自动合入远端联网工具约定，保留 Release 87 真机反馈边界。
- AiSessions 冲突按组合行为解决：新会话/关闭结束截图模式并归还工具；空白会话清理所有截图、PDF 和导入任务，重置跨文档关联，继续拒绝迟到文件选择器结果。新会话来源按当前窗格文档解析，避免从其他文档加入的会话继承错误来源。
- 更新旧单截图测试接口，覆盖多截图清空、截图模式中新会话恢复工具，以及跨文档新会话清理。保留 Release 87 湿墨 View 重挂修复、versionCode 87 和共用签名配置。
- 合并前的 3 个未提交文件单独暂存，合并提交后恢复，不纳入合并提交。

## 验证

JDK 21，仓库 Gradle 8.13 / AGP 8.12.3，使用已有 `tmp/server-build.init.gradle` 指定服务器安装的 Build Tools 36.0.0：

```sh
../gradlew --offline --no-daemon -I tmp/server-build.init.gradle :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin
```

2585 项 JVM 测试通过，失败/错误均为 0；Android 测试 Kotlin 编译通过；`git diff --check` 通过。未连接设备，本次未执行 Android 事件仪器测试、目标设备或真实供应商验证。未打包、安装或推送。
