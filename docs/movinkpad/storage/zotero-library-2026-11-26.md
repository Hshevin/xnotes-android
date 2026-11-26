# Zotero 文献库：浏览并打开为批注笔记（2026-11-26）

用户要求：在 Zotero 里添加文献后，可以直接用 XNotes 阅读和批注。确认方案为「在 XNotes 里直接打开 Zotero 文献库」：填一次密钥，按分类浏览、搜索，点一篇打开 PDF；再点回到同一本笔记；支持 Zotero 自带存储与 WebDAV。批注写回 Zotero 留待以后。
状态：已实现；已验证 JVM 单元测试全部 4435 项通过（含 `ZoteroClientTest` 18 项、`ZoteroStoreTest` 5 项、`ChromeTokensTest`），Debug 编译通过；本机无安卓模拟器，界面未在屏幕上走查；真实 Zotero 账号、WebDAV 服务器与 MovinkPad 真机未验证。
本轮边界：本地提交；未打包、未安装、未推送、未发布。

## 使用流程

- 首页侧栏新增「Zotero」。未连接时显示密钥说明、打开 zotero.org 新建密钥页的按钮、密钥输入框和可选的 WebDAV 地址／用户名／密码；点「连接」先向 Zotero 核实密钥（需有文库读取权限；未配置 WebDAV 时还需文件权限）再保存。
- 连接后：有群组文库时可切换文库；分类菜单含「全部文献」和按层级缩进的分类；搜索按标题、作者、年份匹配。列表按添加时间从新到旧，滚动加载更多；打开过的文献带书本图标。
- 点文献：多个 PDF 时先选择；已有对应笔记且仍在工作区（不在回收站）就直接打开；否则下载、核对 md5，导入为工作区顶层 `Zotero` 文件夹里的批注笔记并打开。进度框可取消。
- 列表旁有刷新按钮，在电脑上新加文献后可直接刷新。WebDAV 地址末尾多填的 `/zotero` 会自动去掉（Zotero 自己会加这一层）。
- 右上角设置可改密钥与 WebDAV，或断开连接（只删密钥，笔记与对应关系保留）。

## 实现

- `zotero/`：`ZoteroClient`（Web API v3、分页、文件下载与跳转、WebDAV zip、md5、错误分类）、`ZoteroConfigStore`（AndroidKeyStore 加密）、`ZoteroLinkStore`（原子写入，损坏文件改名保留）。后端由主代理定接口与测试，Codex（gpt-6.2-astra）实现，主代理审读。
- `Editor.importZoteroPdf` 复用 `createPdfNoteFile`，取得新笔记的文档 UUID；`Editor.zoteroNoteLocation` 经登记表找回位置并排除 `.xnote` 侧边目录（回收站）。
- 界面 `ui/ZoteroPane.kt`，文案在 `zotero.xml`（英文与两份简体中文）。文案与交互经 Gemini 复核：采纳了文案改写、刷新按钮、多 PDF 选择显示文件名、切换文库时清空分类、先检查笔记文件夹、群组文库缺文件不提示 WebDAV、WebDAV 地址去掉多余 `/zotero`；未采纳保存时预检 WebDAV（服务器各异，下载时会明确报错）、密码显隐切换和下载分阶段提示。zotero.org 密钥页只要求勾选 Allow library access（文件权限是否单独勾选未能核实，文案不再点名）。

## 已知边界

- 链接文件（linked file）只存在添加它的电脑上，无法下载，会提示原因。
- Zotero 里 PDF 更新后，已导入的笔记不会自动换底稿。
- 群组文库始终走 Zotero 自带存储（Zotero 本身不支持群组用 WebDAV）。
