package com.xnotes.storage

import com.xnotes.format.BundleReferenceInfo

/** Ordinary existing-file saves cannot stand in for creation, copying or raw metadata upgrades. */
internal object NativeSaveContract {
    fun verify(original:BundleReferenceInfo,candidate:BundleReferenceInfo,current:BundleReferenceInfo,
               purpose:MigrationPublication.Purpose,atLocation:ReferenceExpectation?,byIdentity:ReferenceExpectation?) {
        val expected=when(purpose) {
            MigrationPublication.Purpose.SAVE_NOTE -> "xnote"
            MigrationPublication.Purpose.SAVE_CANVAS -> "xcanvas"
            MigrationPublication.Purpose.CREATE_NOTE,MigrationPublication.Purpose.CREATE_CANVAS,MigrationPublication.Purpose.ACTIVATE_PAGES -> reject()
        }
        if(listOf(original,candidate,current).any {it.format!=expected}) reject()
        ReferenceContract.verifyWrite(atLocation,byIdentity,current.header,candidate.header)
        val id=original.header.identity?.documentId
            ?: throw ReferenceContractViolation(StorageMsg.REFERENCE_IDENTITY_MISSING)
        if(candidate.header.identity?.documentId!=id) throw ReferenceContractViolation(StorageMsg.REFERENCE_IDENTITY_MISMATCH)
        if((original.header.anchors!=null)!=(candidate.header.anchors!=null)) reject()
        if(original.header.identity!=candidate.header.identity || original.originalPdfHash!=candidate.originalPdfHash) reject()
    }
    private fun reject():Nothing=throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
}
