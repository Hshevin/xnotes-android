package com.xnotes.storage

import com.xnotes.format.BundleHeader
import java.util.UUID

/** Persistent evidence is separate from the current URI and from editable content versions. */
data class ReferenceExpectation(val documentId:String,val requiresPages:Boolean) {
    init {require(UUID.fromString(documentId).toString()==documentId)}
}

internal class ReferenceContractViolation(val reason:StorageMsg,cause:Throwable?=null):IllegalStateException(StorageText.t(reason),cause)

/** Header preflight only. Full page identities and source/candidate digests need separate checks. */
internal object ReferenceContract {
    /** Both expectations are explicit so a moved file cannot bypass UUID-level evidence. */
    fun verifyObserved(atLocation:ReferenceExpectation?,byIdentity:ReferenceExpectation?,header:BundleHeader) {
        for(expected in listOfNotNull(atLocation,byIdentity)) {
            if(header.identity?.documentId!=expected.documentId) throw ReferenceContractViolation(StorageMsg.REFERENCE_IDENTITY_MISMATCH)
            if(expected.requiresPages && header.anchors==null) throw ReferenceContractViolation(StorageMsg.REFERENCE_PAGE_METADATA_MISSING)
        }
    }
    /** Existing-file overwrite only. Creation, legacy upgrade and explicit source replacement are separate operations. */
    fun verifyWrite(atLocation:ReferenceExpectation?,byIdentity:ReferenceExpectation?,current:BundleHeader,candidate:BundleHeader) {
        // A candidate cannot silently "repair" a source that already lost its expected contract.
        verifyObserved(atLocation,byIdentity,current)
        val id=current.identity?.documentId ?: throw ReferenceContractViolation(StorageMsg.REFERENCE_IDENTITY_MISSING)
        if(candidate.identity?.documentId!=id) throw ReferenceContractViolation(StorageMsg.REFERENCE_IDENTITY_MISMATCH)
        if(current.anchors!=null && candidate.anchors==null) throw ReferenceContractViolation(StorageMsg.REFERENCE_PAGE_METADATA_MISSING)
    }
}
