package com.xnotes.storage

import com.xnotes.core.model.Document
import com.xnotes.core.pal.assetPath
import com.xnotes.kio.KFiles
import com.xnotes.kio.Sha256
import okio.IOException

/** Pure decoded-data checks. These issue no source version, adoption or write authority. */
object NativeDecodedContract {
    fun note(document:Document,info:ReferenceInfo,expectedId:String?,checkActive:()->Unit) {
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
        // Only a legacy note with no PDF contract may use its decoder-generated UUID unbound.
        // A matching PDF file never excuses a decoded identity pointing at another resource.
        val hasPdfContract=info.header.identity!=null || expected!=null || document.identity.pdfResource!=null
        if(hasPdfContract && document.identity!=info.header.identity && (expected==null || document.identity.pdfResource!=expected))
            throw ReferenceContractViolation(StorageMsg.PDF_RESOURCE_MISMATCH)
        val original=document.pdfFile
        if(expected==null) {
            if(original!=null) throw ReferenceContractViolation(StorageMsg.PDF_RESOURCE_MISMATCH)
            return
        }
        if(original==null) throw ReferenceContractViolation(StorageMsg.REFERENCE_METADATA_INVALID)
        val hash=try {KFiles.system.read(original.assetPath()) {
            val digest=Sha256();val bytes=ByteArray(65536)
            while(true) {checkActive();val count=read(bytes);if(count<0) break;digest.update(bytes,0,count)}
            digest.hex()
        }}
            catch(error:IOException) {throw ReferenceContractViolation(StorageMsg.REFERENCE_METADATA_INVALID,error)}
        if(hash!=expected) throw ReferenceContractViolation(StorageMsg.PDF_RESOURCE_MISMATCH)
    }
}
