# MovinkPad 适配概览

本仓库是 [XNotes Android](https://github.com/shardulvs/xnotes-android) 的 fork，起点为上游提交 `26f42b29`（0.9.6），之后定期合入上游更新。上游 MIT 许可证、署名和第三方声明全部保留。

## 目标

- 设备：Wacom MovinkPad Pro 14，Android 15，配套 Pro Pen 3。只交付 ARM64。
- 产品参照：OneNote Android 平板版的功能与交互。
- 数据：笔记全部离线保存在本地，无账号、无同步、无应用后端；AI 与 Zotero 文库是用户自行配置、主动操作时才联网的可选功能。
- 界面：只用黑白和中性灰阶。

## 与上游的主要差异

- 手写笔按键录入与绑定（Pro Pen 3 原始按钮位 `0x20`、`0x40`、`0x04`）。
- 图层、画布内思维导图、双窗格、AI、Zotero 文库。
- 文档身份登记、页面永久编号、受保护的保存与恢复流程。
- PDF 原件只在应用内保存一份，导出时再打包。
- 包名 `com.xnotes.movinkpad`，与上游应用并存；分支测试版使用独立包名。

## 文档入口

- [README](README.md)：面向用户的功能、安装和构建。
- [AGENTS.md](AGENTS.md)：长期有效的开发规则。
- [项目说明](docs/movinkpad/project-brief.md)：目标、设备事实与对齐范围。
- [文档索引](docs/movinkpad/README.md)：按主题整理的开发、发布与验证记录。

模拟器和 JVM 测试结果不代表目标设备上的表现；真机验收在各记录中单独标注。
