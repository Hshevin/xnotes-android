package com.xnotes.storage

/**
 * User-visible storage messages (exceptions that reach dialogs and the message bar). [zh] is the canonical
 * Simplified Chinese text and the JVM default; the app installs an Android resource lookup at startup
 * (see ui/StorageMessageResources.kt). Persisted names, keys and file names are intentionally not here.
 */
enum class StorageMsg(val zh: String) {
    DUPLICATE_DOCUMENT_ID("文档 ID 存在多份文件，请先选择原件或为副本分配新身份"),
    PDF_RESOURCE_UNREGISTERED("PDF 资源未登记"),
    PDF_RESOURCE_MISSING("PDF 资源不存在"),
    MIGRATION_INTERRUPTED("升级曾被中断或文件已被外部修改；原笔记备份已保留，请选择恢复备份或保留当前文件"),
    MIGRATION_VERIFY_FAILED("升级写入验证失败"),
    MIGRATION_WRITE_INCOMPLETE("升级写入未完成，原笔记备份已保留。请恢复备份后重试"),
    NO_BACKUP_TO_RESTORE("没有可恢复的旧笔记"),
    RESTORE_VERIFY_FAILED("恢复验证失败，备份仍保留"),
    MIGRATION_RECORD_FAILED("无法完成迁移记录"),
    NOTE_UNREADABLE("无法读取笔记"),
    NOTE_NOT_WRITABLE("笔记不可写"),
    LEGACY_NOTE_READ_ONLY("旧笔记为只读，原文件未修改；请保存可写副本后重试"),
    FILE_READ_ONLY_UPGRADE("文件为只读，无法升级；请保存可写副本"),
    SPLIT_MISSING_DOCUMENT_ID("旧分屏没有文档 ID，请手动重新定位"),
    SEARCH_LIMIT_REACHED("已检查5000个条目，请缩小授权文件夹范围或手动重新定位"),
    DUPLICATE_DOCUMENTS_FOUND("找到多份相同ID的文档，请手动选择原件或将副本设为独立文档"),
    INVALID_NOTE_PATH("非法笔记路径"),
    PDF_RESOURCE_VERIFY_FAILED("PDF 资源校验失败"),
    PDF_RESOURCE_CORRUPT("此笔记引用的原始 PDF 不在本应用中，可能已被另一个安装版本迁移，或应用曾重装 / 清除数据。笔迹与批注未受影响：请选择同一份原始 PDF 重新关联，或在原安装版本中导出笔记"),
    PDF_RESOURCE_MISMATCH("所选 PDF 与此笔记的原始 PDF 内容不一致，未做任何更改；请选择当初导入的同一份文件"),
}

object StorageText {
    @Volatile var lookup: (StorageMsg, Array<out Any>) -> String = { m, a -> String.format(m.zh, *a) }
    fun t(m: StorageMsg, vararg args: Any): String = lookup(m, args)
}
