package com.xnotes.ui

import android.content.Context
import com.xnotes.R
import com.xnotes.storage.StorageMsg
import com.xnotes.storage.StorageText

/** Exhaustive so every message keeps a resource under R8 shrinking and new entries fail to compile. */
internal fun StorageMsg.resId(): Int = when (this) {
    StorageMsg.DUPLICATE_DOCUMENT_ID -> R.string.storage_msg_duplicate_document_id
    StorageMsg.PDF_RESOURCE_UNREGISTERED -> R.string.storage_msg_pdf_resource_unregistered
    StorageMsg.PDF_RESOURCE_MISSING -> R.string.storage_msg_pdf_resource_missing
    StorageMsg.MIGRATION_INTERRUPTED -> R.string.storage_msg_migration_interrupted
    StorageMsg.MIGRATION_VERIFY_FAILED -> R.string.storage_msg_migration_verify_failed
    StorageMsg.MIGRATION_WRITE_INCOMPLETE -> R.string.storage_msg_migration_write_incomplete
    StorageMsg.NO_BACKUP_TO_RESTORE -> R.string.storage_msg_no_backup_to_restore
    StorageMsg.RESTORE_VERIFY_FAILED -> R.string.storage_msg_restore_verify_failed
    StorageMsg.MIGRATION_RECORD_FAILED -> R.string.storage_msg_migration_record_failed
    StorageMsg.NOTE_UNREADABLE -> R.string.storage_msg_note_unreadable
    StorageMsg.NOTE_NOT_WRITABLE -> R.string.storage_msg_note_not_writable
    StorageMsg.LEGACY_NOTE_READ_ONLY -> R.string.storage_msg_legacy_note_read_only
    StorageMsg.FILE_READ_ONLY_UPGRADE -> R.string.storage_msg_file_read_only_upgrade
    StorageMsg.SPLIT_MISSING_DOCUMENT_ID -> R.string.storage_msg_split_missing_document_id
    StorageMsg.SEARCH_LIMIT_REACHED -> R.string.storage_msg_search_limit_reached
    StorageMsg.DUPLICATE_DOCUMENTS_FOUND -> R.string.storage_msg_duplicate_documents_found
    StorageMsg.INVALID_NOTE_PATH -> R.string.storage_msg_invalid_note_path
    StorageMsg.PDF_RESOURCE_VERIFY_FAILED -> R.string.storage_msg_pdf_resource_verify_failed
    StorageMsg.PDF_RESOURCE_CORRUPT -> R.string.storage_msg_pdf_resource_corrupt
    StorageMsg.PDF_RESOURCE_MISMATCH -> R.string.storage_msg_pdf_resource_mismatch
}

/** Routes storage messages through Android resources so they follow the app locale. */
fun installStorageText(context: Context) {
    val app = context.applicationContext
    StorageText.lookup = { m, a -> app.getString(m.resId(), *a) }
}
