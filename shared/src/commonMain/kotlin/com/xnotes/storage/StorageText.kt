package com.xnotes.storage

import kotlin.concurrent.Volatile

/**
 * User-visible storage messages (exceptions that reach dialogs and the message bar). [zh] is the canonical
 * Simplified Chinese text and the JVM default; the app installs an Android resource lookup at startup
 * (see ui/StorageMessageResources.kt). Persisted names, keys and file names are intentionally not here.
 */
enum class StorageMsg(val zh: String) {
    MIGRATION_RECOVERY_PENDING("上次的恢复还没处理完，请先处理，再保存。"),
    MIGRATION_RECOVERY_INVALID("恢复资料缺失、损坏或无法识别，已全部保留。请先检查当前文件，再选择恢复方式。"),
    MIGRATION_INDEX_INCOMPLETE("这份文档还没在应用中登记完成。当前文件和恢复资料都已保留，请重试。"),

    NATIVE_CREATION_TARGET_INVALID("所选位置不是新建的空文件，没有覆盖它。请重新选择一个新文件。"),
    REFERENCE_SOURCE_CHANGED("原文件在别处被改动过，为防止覆盖没有保存。你的修改仍在，请检查原文件或另存副本。"),
    REFERENCE_WRITE_CONTEXT_INVALID("这次保存和原文件的类型、页面链接或原始 PDF 对不上，没有覆盖原文件。请改用另存副本、恢复或升级。"),
    REFERENCE_METADATA_INVALID("无法确认这份文档的标识或页面链接信息，原文件没有改动。请检查原文件，或使用恢复资料。"),
    REFERENCE_IDENTITY_MISMATCH("这个文件已不是原来打开的那份文档，为防止覆盖没有保存。请找到原文档，或把这个文件作为另一份文档打开。"),
    REFERENCE_IDENTITY_MISSING("现有文件缺少文档标识，没有覆盖它。请先恢复原文件，或升级这个文件后再保存。"),
    REFERENCE_PAGE_METADATA_MISSING("文件里的页面链接信息丢失了，为防止覆盖没有保存。请恢复原文件后重试。"),

    DUPLICATE_DOCUMENT_ID("有多个文件是同一份文档。请先选出原件，或把副本设为独立文档"),
    PDF_RESOURCE_UNREGISTERED("应用里没有这份笔记原始 PDF 的记录"),
    PDF_RESOURCE_MISSING("找不到这份笔记的原始 PDF"),
    MIGRATION_INTERRUPTED("上次升级被中断，或文件在别处被改过。升级前的备份已保留，请选择恢复备份或保留当前文件"),
    MIGRATION_VERIFY_FAILED("升级后的文件校验未通过"),
    MIGRATION_WRITE_INCOMPLETE("升级没有写完，升级前的备份已保留。请恢复备份后重试"),
    NO_BACKUP_TO_RESTORE("没有可恢复的旧笔记"),
    RESTORE_VERIFY_FAILED("恢复后的文件校验未通过，备份仍保留"),
    MIGRATION_RECORD_FAILED("无法保存升级记录"),
    NOTE_UNREADABLE("无法读取笔记"),
    NOTE_NOT_WRITABLE("无法写入这份笔记"),
    LEGACY_NOTE_READ_ONLY("旧笔记是只读的，原文件没有改动。请另存一份可编辑的副本后重试"),
    FILE_READ_ONLY_UPGRADE("文件是只读的，无法升级。请另存一份可编辑的副本"),
    SPLIT_MISSING_DOCUMENT_ID("这个旧分屏文件没有记下文档标识，请手动找到文档"),
    SEARCH_LIMIT_REACHED("已查找 5000 个项目仍未找到。请缩小授权的文件夹范围，或手动找到文档"),
    DUPLICATE_DOCUMENTS_FOUND("找到同一份文档的多个副本。请手动选出原件，或把副本设为独立文档"),
    INVALID_NOTE_PATH("笔记路径无效"),
    PDF_RESOURCE_VERIFY_FAILED("原始 PDF 校验未通过，可能已损坏"),
    PDF_RESOURCE_CORRUPT("找不到这份笔记使用的原始 PDF。可能是另一个安装版本迁移了它，或应用重装、清除过数据。笔迹和批注都还在：请重新选择当初那份 PDF，或仍包含它的旧笔记文件、导出包来重新关联"),
    PDF_RESOURCE_MISMATCH("所选文件和这份笔记的原始 PDF 不是同一份，没有做任何更改。请选择当初导入的那份 PDF，或包含它的旧笔记文件"),
}

object StorageText {
    @Volatile var lookup: (StorageMsg, Array<out Any>) -> String = { message, _ -> message.zh }
    fun t(m: StorageMsg, vararg args: Any): String = lookup(m, args)
}
