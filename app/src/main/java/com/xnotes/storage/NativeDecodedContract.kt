package com.xnotes.storage

import com.xnotes.core.model.Document
import com.xnotes.format.BundleMigration
import com.xnotes.format.BundleReferenceInfo

/** Pure decoded-data checks. These issue no source version, adoption or write authority. */
internal object NativeDecodedContract {
    fun note(document:Document,info:BundleReferenceInfo,expectedId:String?,checkActive:()->Unit) {
        if(expectedId!=null && document.identity.documentId!=expectedId)
            throw ReferenceContractViolation(StorageMsg.REFERENCE_IDENTITY_MISMATCH)
        val anchored=info.header.anchors!=null
        if(document.hasReferenceAnchors!=anchored) throw ReferenceContractViolation(StorageMsg.REFERENCE_METADATA_INVALID)
        val actual=document.pages.map {checkActive();it.referenceIdentity.id}
        if(anchored) {
            if(actual.size!=info.pageIds.size || actual.toSet()!=info.pageIds)
                throw ReferenceContractViolation(StorageMsg.REFERENCE_METADATA_INVALID)
        } else if(actual.any {it!=null}) throw ReferenceContractViolation(StorageMsg.REFERENCE_METADATA_INVALID)
        val expected=info.originalPdfHash
        // A legacy body may have no identity; no source binding is inferred from its decoded ID.
        if(info.header.identity!=null && document.identity!=info.header.identity && (expected==null || document.identity.pdfResource!=expected))
            throw ReferenceContractViolation(StorageMsg.PDF_RESOURCE_MISMATCH)
        val original=document.pdfFile
        if(expected==null) {
            if(original!=null) throw ReferenceContractViolation(StorageMsg.PDF_RESOURCE_MISMATCH)
            return
        }
        if(original==null) throw ReferenceContractViolation(StorageMsg.REFERENCE_METADATA_INVALID)
        val hash=try {BundleMigration.hash(original) {checkActive();false}}
            catch(error:java.io.IOException) {throw ReferenceContractViolation(StorageMsg.REFERENCE_METADATA_INVALID,error)}
        if(hash!=expected) throw ReferenceContractViolation(StorageMsg.PDF_RESOURCE_MISMATCH)
    }
}
