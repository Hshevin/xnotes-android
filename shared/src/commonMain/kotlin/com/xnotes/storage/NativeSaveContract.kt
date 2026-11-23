package com.xnotes.storage

import com.xnotes.core.util.DocumentKind

/** Ordinary existing-file saves cannot stand in for creation, copying or raw metadata upgrades. */
object NativeSaveContract {
    fun verify(original:ReferenceInfo,candidate:ReferenceInfo,current:ReferenceInfo,
               expected:DocumentKind,atLocation:ReferenceExpectation?,byIdentity:ReferenceExpectation?) {
        if(expected!=DocumentKind.NOTE && expected!=DocumentKind.CANVAS) reject()
        if(listOf(original,candidate,current).any {it.kind!=expected}) reject()
        ReferenceContract.verifyWrite(atLocation,byIdentity,current.header,candidate.header)
        val id=original.header.identity?.documentId
            ?: throw ReferenceContractViolation(StorageMsg.REFERENCE_IDENTITY_MISSING)
        if(candidate.header.identity?.documentId!=id) throw ReferenceContractViolation(StorageMsg.REFERENCE_IDENTITY_MISMATCH)
        if((original.header.anchors!=null)!=(candidate.header.anchors!=null)) reject()
        if(original.header.identity!=candidate.header.identity || original.originalPdfHash!=candidate.originalPdfHash) reject()
    }
    private fun reject():Nothing=throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
}
