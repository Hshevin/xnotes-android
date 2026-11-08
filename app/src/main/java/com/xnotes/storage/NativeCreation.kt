package com.xnotes.storage

import com.xnotes.core.util.DocumentKind
import java.util.UUID

/** Runtime authority from an explicit create/Save-As action, never inferred from a failed save. */
internal class NativeCreationIntent private constructor(private val issuer:Any,val kind:DocumentKind,val documentId:String) {
    internal class Stage(val locationKey:String,val candidateSha256:String,val recordDigest:String)
    private var consumed=false
    private var staged:Stage?=null
    @Synchronized internal fun consume(owner:Any) {
        if(owner!==issuer || consumed) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        consumed=true
    }
    @Synchronized internal fun recordStage(owner:Any,locationKey:String,candidateSha256:String,recordDigest:String):Stage {
        if(owner!==issuer || !consumed || staged!=null) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        return Stage(locationKey,candidateSha256,recordDigest).also {staged=it}
    }
    @Synchronized internal fun retainedStage(owner:Any,locationKey:String):Stage? {
        if(owner!==issuer) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        val actual=staged ?: return null
        if(actual.locationKey!=locationKey) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        return actual
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
    private val issuer:Any,private val intent:NativeCreationIntent,val kind:DocumentKind,val documentId:String,val locationKey:String,
) {
    private var consumed=false
    @Synchronized internal fun consume(owner:Any) {
        if(owner!==issuer || consumed) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        consumed=true
    }
    @Synchronized internal fun recordStage(owner:Any,candidateSha256:String,recordDigest:String):NativeCreationIntent.Stage {
        if(owner!==issuer || !consumed) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        return intent.recordStage(owner,locationKey,candidateSha256,recordDigest)
    }
    companion object {
        internal fun issue(issuer:Any,intent:NativeCreationIntent,locationKey:String)=
            NativeCreationPermit(issuer,intent,intent.kind,intent.documentId,locationKey)
    }
}

/** Runtime retry of one actual retained CREATE stage. Never a replacement candidate or a new permit. */
internal class NativeCreationPending private constructor(
    private val issuer:Any,val kind:DocumentKind,val documentId:String,val locationKey:String,
    val candidateSha256:String,val recordDigest:String,internal val uri:String,internal val name:String,
) {
    internal fun checkIssuer(owner:Any) {
        if(owner!==issuer) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
    }
    companion object {
        internal fun issue(issuer:Any,intent:NativeCreationIntent,stage:NativeCreationIntent.Stage,uri:String,name:String):NativeCreationPending {
            if(intent.retainedStage(issuer,stage.locationKey)!==stage) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
            return NativeCreationPending(issuer,intent.kind,intent.documentId,stage.locationKey,stage.candidateSha256,stage.recordDigest,uri,name)
        }
    }
}
