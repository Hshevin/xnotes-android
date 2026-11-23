package com.xnotes.format

import com.xnotes.kio.Uuids
import com.xnotes.storage.StorageMsg
import com.xnotes.storage.StorageText

expect class BundleIdentity(documentId: String = Uuids.random(), pdfResource: String? = null, externalPdf: Boolean = false) {
    val documentId: String
    val pdfResource: String?
    val externalPdf: Boolean
    companion object { val ENTRY: String }
}

internal fun BundleIdentity.withDocumentId(id: String) = BundleIdentity(id, pdfResource, externalPdf)

internal fun checkBundleIdentity(documentId: String, pdfResource: String?, externalPdf: Boolean) {
    require(Uuids.isValid(documentId) && documentId == documentId.lowercase()) { "Invalid document UUID" }
    require(!externalPdf || pdfResource?.matches(Regex("[a-f0-9]{64}")) == true) { StorageText.t(StorageMsg.PDF_RESOURCE_CORRUPT) }
}
