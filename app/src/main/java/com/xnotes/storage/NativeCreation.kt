package com.xnotes.storage

import com.xnotes.core.util.DocumentKind
import java.util.UUID

/** Runtime authority from an explicit create/Save-As action, never inferred from a failed save. */
internal class NativeCreationIntent private constructor(private val issuer:Any,val kind:DocumentKind,val documentId:String) {
    private var consumed=false
    @Synchronized internal fun consume(owner:Any) {
        if(owner!==issuer || consumed) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        consumed=true
    }
    companion object {
        internal fun issue(issuer:Any,kind:DocumentKind):NativeCreationIntent {
            if(kind!=DocumentKind.NOTE && kind!=DocumentKind.CANVAS)
                throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
            return NativeCreationIntent(issuer,kind,UUID.randomUUID().toString())
        }
    }
}

/** One write attempt at the observed empty canonical location. Not an overwrite or repair permit. */
internal class NativeCreationPermit private constructor(
    private val issuer:Any,val kind:DocumentKind,val documentId:String,val locationKey:String,
) {
    private var consumed=false
    @Synchronized internal fun consume(owner:Any) {
        if(owner!==issuer || consumed) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        consumed=true
    }
    companion object {
        internal fun issue(issuer:Any,intent:NativeCreationIntent,locationKey:String)=
            NativeCreationPermit(issuer,intent.kind,intent.documentId,locationKey)
    }
}
