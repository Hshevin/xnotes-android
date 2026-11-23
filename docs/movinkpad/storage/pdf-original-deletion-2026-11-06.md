# 关闭或切换文档时误删私有 PDF 原件（2026-11-06）

用户要求：所有 PDF 笔记退出几分钟后再进入都提示 PDF 找不到；希望 PDF 丢失时仍能导出笔迹。
状态：已实现；JVM 单元测试 2707 项通过、Android 仪器测试编译通过；待真机验证。
本轮边界：只本地提交，不打包 APK、不安装、不推送。

## 原因（源码确认）

`Editor.adoptOpenPdf` 在打开另一文档、关闭或交接窗格时，会删除上一文档的 `openPdfTemp`。单份 PDF（`02b9fa11`，Release 91 起）之前它总是 `pdfsrc/` 中的解包临时文件；之后打开的笔记 `pdfFile` 改成 `noBackupFilesDir/ai-pdfs/<SHA-256>.pdf` 这一唯一原件，因此等待中的保存结束后原件即被删除。注册表引用仍在，下次打开时 `PdfResources.resolve` 找不到文件，报“PDF 资源丢失或损坏”。

影响：Release 91–93 中打开过、随后关闭或切走的所有已迁移 PDF 笔记。笔记文件中的笔迹、批注与页面不受影响；尚未在新版本中打开的旧笔记仍内嵌 PDF，未受影响。前一记录 `pdf-relink-2026-11-05.md` 推断的“其他安装身份”只是次要场景。

## 修复

- `adoptOpenPdf` 不再删除 `PdfResources.isOwned` 的受管原件，受管原件只由引用与租约回收处理。
- 重新关联除原始 PDF 外，也接受仍内嵌原件的旧 `.xnote` 或导出包（读取 `assets/source.pdf`），同样必须 SHA-256 一致。
- 恢复对话框新增「只打开笔迹（不含 PDF，另存为副本）」：以无 PDF 目录读取，页面显示为空白背景；新文档身份、无保存路径、标记为未保存，不写原笔记文件，以后仍可重新关联。可用现有导出或另存为保存笔迹。

## 验证与待办

- 新增 JVM 测试 `missingReferencedPdfStillYieldsInkWithoutPdfDirectory`；仪器测试 `missingPrivateOriginalOnlyRelinksIdenticalPdf` 仅编译。
- `adoptOpenPdf` 的修复没有自动化测试（依赖 Editor 运行时）。待真机验证：打开 PDF 笔记→关闭／切换→等待数分钟→重开仍正常；重新关联 PDF 或旧笔记包；仅笔迹副本的显示、导出与另存为。
- 已被删除的原件无法由应用找回，只能依靠用户保留的原 PDF、旧笔记副本或导出包。
