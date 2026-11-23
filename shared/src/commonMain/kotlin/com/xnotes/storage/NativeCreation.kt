package com.xnotes.storage

import com.xnotes.core.util.DocumentKind
import com.xnotes.core.util.CoreLock
import com.xnotes.core.util.locked
import com.xnotes.kio.Uuids

/** Runtime authority from an explicit create/Save-As action, never inferred from a failed save. */
class NativeCreationIntent private constructor(private val issuer:Any,val kind:DocumentKind,val documentId:String) {
    class Stage internal constructor(val locationKey:String,val candidateSha256:String,val recordDigest:String)
    private val gate=CoreLock()
    private var consumed=false
    private var permitIssued=false
    private var staged:Stage?=null
    internal fun consume(owner:Any) = gate.locked {
        if(owner!==issuer || consumed) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        consumed=true
    }
    internal fun claimPermit(owner:Any) = gate.locked {
        if(owner!==issuer || !consumed || permitIssued) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        permitIssued=true
    }
    internal fun recordStage(owner:Any,locationKey:String,candidateSha256:String,recordDigest:String):Stage = gate.locked {
        if(owner!==issuer || !consumed || staged!=null) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        return Stage(locationKey,candidateSha256,recordDigest).also {staged=it}
    }
    internal fun retainedStage(owner:Any,locationKey:String):Stage? = gate.locked {
        if(owner!==issuer) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        val actual=staged ?: return null
        if(actual.locationKey!=locationKey) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        return actual
    }
    companion object {
        internal fun issue(issuer:Any,kind:DocumentKind):NativeCreationIntent {
            if(kind!=DocumentKind.NOTE && kind!=DocumentKind.CANVAS)
                throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
            return NativeCreationIntent(issuer,kind,Uuids.random())
        }
    }
}

/** One write attempt at the observed empty canonical location. Not an overwrite or repair permit. */
class NativeCreationPermit private constructor(
    private val issuer:Any,private val intent:NativeCreationIntent,val kind:DocumentKind,val documentId:String,val locationKey:String,
) {
    private val gate=CoreLock()
    private var consumed=false
    internal fun consume(owner:Any) = gate.locked {
        if(owner!==issuer || consumed) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        consumed=true
    }
    internal fun recordStage(owner:Any,candidateSha256:String,recordDigest:String):NativeCreationIntent.Stage = gate.locked {
        if(owner!==issuer || !consumed) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        return intent.recordStage(owner,locationKey,candidateSha256,recordDigest)
    }
    companion object {
        internal fun issue(issuer:Any,intent:NativeCreationIntent,locationKey:String):NativeCreationPermit {
            intent.claimPermit(issuer)
            return NativeCreationPermit(issuer,intent,intent.kind,intent.documentId,locationKey)
        }
    }
}

/** Runtime retry of one actual retained CREATE stage. Never a replacement candidate or a new permit. */
class NativeCreationPending private constructor(
    private val issuer:Any,val kind:DocumentKind,val documentId:String,val locationKey:String,
    val candidateSha256:String,val recordDigest:String,val uri:String,val name:String,
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

class NativeCreationIssuer {
    private val owner=Any()
    fun begin(kind:DocumentKind)=NativeCreationIntent.issue(owner,kind)
    fun consume(intent:NativeCreationIntent)=intent.consume(owner)
    fun permit(intent:NativeCreationIntent,locationKey:String)=NativeCreationPermit.issue(owner,intent,locationKey)
    fun consume(permit:NativeCreationPermit)=permit.consume(owner)
    fun record(permit:NativeCreationPermit,hash:String,digest:String)=permit.recordStage(owner,hash,digest)
    fun stage(intent:NativeCreationIntent,locationKey:String)=intent.retainedStage(owner,locationKey)
    fun pending(intent:NativeCreationIntent,stage:NativeCreationIntent.Stage,uri:String,name:String)=NativeCreationPending.issue(owner,intent,stage,uri,name)
    fun check(pending:NativeCreationPending)=pending.checkIssuer(owner)
}
