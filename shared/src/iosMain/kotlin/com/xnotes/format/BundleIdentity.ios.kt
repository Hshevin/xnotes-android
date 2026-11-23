package com.xnotes.format

actual data class BundleIdentity actual constructor(actual val documentId: String, actual val pdfResource: String?, actual val externalPdf: Boolean) {
    init { checkBundleIdentity(documentId, pdfResource, externalPdf) }
    actual companion object { actual const val ENTRY = "xnotes-identity.json" }
}
