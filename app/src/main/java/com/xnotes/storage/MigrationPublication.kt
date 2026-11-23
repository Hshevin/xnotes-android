package com.xnotes.storage

import com.xnotes.format.BundleMigration
import okio.source
import java.io.File
import java.io.InputStream
import java.util.concurrent.CancellationException

/** Owner serializes access per URI. Backups survive until file read-back AND registration succeed. */
class MigrationPublication internal constructor(private val directory:File,private val hashFile:(File,()->Unit)->String,
                                               private val markerOpen:(File)->InputStream) {
    internal constructor(directory:File,hashFile:(File)->String,markerOpen:(File)->InputStream):this(directory,
        {file,check->check();hashFile(file).also {check()}},markerOpen)
    internal constructor(directory:File,hashFile:(File)->String):this(directory,hashFile,{it.inputStream()})
    constructor(directory:File):this(directory,{file,check->BundleMigration.hash(file) {check();false}},{it.inputStream()})
    interface Target {
        fun read():InputStream
        /** Positive backend evidence only; a failed read or lost permission is not proof of absence. */
        fun confirmedMissing():Boolean = false
        /** Called only after the original, candidate and record have been durably staged. */
        fun write(source:File)
        /** Atomic backends recheck this version immediately before replacing their destination. */
        fun write(source:File,expectedCurrentHash:String?) {write(source)}
    }
    enum class Purpose(val core: PublicationPurpose) {
        SAVE_NOTE(PublicationPurpose.SAVE_NOTE), SAVE_CANVAS(PublicationPurpose.SAVE_CANVAS), CREATE_NOTE(PublicationPurpose.CREATE_NOTE), CREATE_CANVAS(PublicationPurpose.CREATE_CANVAS), ACTIVATE_PAGES(PublicationPurpose.ACTIVATE_PAGES);
        val key get() = core.key
        internal val creation get() = core.creation
        internal val ordinarySave get() = core.ordinarySave
    }
    internal data class Validation(val original:File,val candidate:File,val current:File,val purpose:Purpose?,
                                   val originalHash:String,val candidateHash:String,val recordDigest:String)
    internal data class RetainedValidation(val original:File,val candidate:File,val purpose:Purpose?,val recordDigest:String,
                                           val originalHash:String,val candidateHash:String)
    internal data class RestoreValidation(val original:File,val purpose:Purpose?,val currentHash:String?,val candidateHash:String?,val candidateStageProof:Boolean,
                                          val handoffId:String?=null)
    internal data class KeepValidation(val original:File?,val candidate:File?,val purpose:Purpose?,val recordValid:Boolean,
                                       val originalHash:String?,val candidateHash:String?,val originalVerified:Boolean,val candidateVerified:Boolean,
                                       val handoffId:String?=null)
    class RecoveryRequired(message:String,cause:Throwable?=null,val reason:StorageMsg?=null):IllegalStateException(message,cause)
    private val original get()=File(directory,"original.zip")
    private val candidate get()=File(directory,"candidate.zip")
    private val marker get()=File(directory,"pending")
    val pending get()=marker.exists()
    private data class Record(val original:String?,val candidate:String?,val digest:String,val purpose:Purpose?,val handoffId:String?=null)
    private data class FileProof(val digest:String?,val exists:Boolean,val regular:Boolean,val size:Long,val modified:Long)
    private data class Retained(val original:FileProof,val candidate:FileProof,val marker:FileProof)
    private enum class Inspection { PUBLISH, RESTORE, KEEP_CURRENT }

    val purpose:Purpose? get()=readRecord().purpose
    /** Explicit selected-target recovery captures purpose and digest from one bounded actual record.
     * This is only an observation; the consumer must still prove the retained edit permission. */
    internal fun selectedRecordDigest(expected:Purpose,checkActive:()->Unit={}):String {
        checkActive();val record=readRecord()
        if(record.purpose!=expected || record.handoffId!=null) throw failure(StorageMsg.MIGRATION_RECOVERY_PENDING)
        checkActive();return record.digest
    }
    internal val directoryPath:String get()=directory.canonicalPath

    fun stage(source:File,replacement:File,purpose:Purpose?=null,expectedCandidateHash:String?=null,
              expectedOriginalHash:String?=null,handoffId:String?=null,checkActive:()->Unit={}):String {
        return MigrationJournal(directory).stage(source,replacement,purpose?.core,expectedCandidateHash,expectedOriginalHash,handoffId,checkActive)
    }

    /** Validation borrows immutable retained files. current is the version matching the target SHA. */
    fun publish(target:Target,validate:(File,File,File)->Unit={_,_,_->},afterVerified:(File)->Unit={}) =
        publishValidated(target,validate={requireLegacy(it.purpose);validate(it.original,it.candidate,it.current)},afterVerified=afterVerified)

    /** Read-only, borrowed retained files. A caller supplies its actual stage digest, never an observed replacement. */
    internal fun <T> inspectRetained(expectedRecordDigest:String,protectCandidate:(File)->Unit={},checkActive:()->Unit={},
                                    validate:(RetainedValidation)->T):T {
        checkActive();val record=readRecord()
        if(record.digest!=expectedRecordDigest) throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID)
        checkActive();protectCandidate(candidate)
        val kept=retained(checkActive=checkActive)
        checkRecord(record,kept,checkCandidate=true)
        val result=validate(RetainedValidation(original,candidate,record.purpose,record.digest,
            kept.original.digest ?: throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID),
            kept.candidate.digest ?: throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID)))
        unchanged(kept,checkActive=checkActive)
        // Large retained hashes precede the final bounded marker check.
        if(readRecord().digest!=expectedRecordDigest) throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID)
        checkActive();return result
    }

    internal fun publishValidated(target:Target,validate:(Validation)->Unit,afterVerified:(File)->Unit={},
                                  protectCandidate:(File)->Unit={},expectedRecordDigest:String?=null,checkActive:()->Unit={},
                                  acknowledgement:NativeHandoffAcknowledgement?=null) {
        val check={if(acknowledgement!=null) acknowledgement.checkCurrent(checkActive) else checkActive()}
        check()
        val record=readRecord()
        if(expectedRecordDigest!=null && record.digest!=expectedRecordDigest) throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID)
        if(record.handoffId!=null && acknowledgement?.matches(directoryPath,record.handoffId,record.digest)!=true)
            throw failure(StorageMsg.MIGRATION_RECOVERY_PENDING)
        if(record.handoffId==null && acknowledgement!=null) throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID)
        // Only dependency protection, never identity authority. The caller owns its lease on every path.
        check();protectCandidate(candidate)
        val kept=retained(checkActive=check)
        val oldHash=kept.original.digest ?: throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID)
        val newHash=kept.candidate.digest ?: throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID)
        checkRecord(record,kept,checkCandidate=true)
        val currentHash=fingerprint(target,check)
        val current=when(currentHash) {
            newHash -> candidate
            oldHash -> original
            else -> throw failure(StorageMsg.MIGRATION_INTERRUPTED)
        }
        check();validate(Validation(original,candidate,current,record.purpose,oldHash,newHash,record.digest))
        unchanged(kept,checkActive=check)
        if(fingerprint(target,check)!=currentHash) throw failure(StorageMsg.MIGRATION_INTERRUPTED)
        check()
        if(currentHash!=newHash) writeVerified(target,candidate,newHash,currentHash,StorageMsg.MIGRATION_VERIFY_FAILED,check)
        register {check();afterVerified(candidate)}
        if(fingerprint(target,check)!=newHash) throw failure(StorageMsg.MIGRATION_VERIFY_FAILED)
        unchanged(kept,checkActive=check)
        check()
        clear()
    }

    /** Explicit recovery. A damaged/missing candidate must not prevent restoring the verified original. */
    fun restore(target:Target,validate:(File)->Unit={},afterVerified:(File)->Unit={}) =
        restoreValidated(target,validate={requireLegacy(it.purpose);validate(it.original)},afterVerified=afterVerified)

    internal fun restoreValidated(target:Target,validate:(RestoreValidation)->Unit,afterVerified:(File)->Unit={}) {
        val record=readRecord();var kept=retained(Inspection.RESTORE)
        val hash=kept.original.digest ?: throw failure(StorageMsg.NO_BACKUP_TO_RESTORE)
        checkRecord(record,kept,checkCandidate=false)
        val current=fingerprint(target)
        if(current==null && !target.confirmedMissing()) throw failure(StorageMsg.NOTE_UNREADABLE)
        validate(RestoreValidation(original,record.purpose,current,record.candidate ?: kept.candidate.digest,record.candidate!=null,record.handoffId))
        kept=unchanged(kept,Inspection.RESTORE)
        if(fingerprint(target)!=current || current==null && !target.confirmedMissing()) throw failure(StorageMsg.MIGRATION_INTERRUPTED)
        if(current!=hash) writeVerified(target,original,hash,current,StorageMsg.RESTORE_VERIFY_FAILED)
        register {afterVerified(original)}
        if(fingerprint(target)!=hash) throw failure(StorageMsg.RESTORE_VERIFY_FAILED)
        unchanged(kept,Inspection.RESTORE)
        clear()
    }

    /** Caller validates the captured current file/version; pure validation is repeated after DB commit. */
    fun keepCurrent(validate:(File?,File?)->Unit={_,_->},afterVerified:()->Unit={}) =
        keepCurrentValidated(validate={requireLegacy(it.purpose);validate(it.original,it.candidate)},afterVerified=afterVerified)

    internal fun keepCurrentValidated(validate:(KeepValidation)->Unit,afterVerified:()->Unit={},beforeClear:()->Unit={}) {
        // Explicit keep can discard a malformed historical record, after the caller validates current.
        val raw=readMarker(forKeep=true)
        val record=try {parseRecord(raw)} catch(error:RecoveryRequired) {if(raw.typed) throw error else null}
        var kept=retained(Inspection.KEEP_CURRENT)
        if(raw.digest!=kept.marker.digest) throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID)
        fun validateCurrent() {validate(KeepValidation(original.takeIf {kept.original.regular},
            candidate.takeIf {kept.candidate.regular},record?.purpose,record!=null,record?.original,record?.candidate,
            record?.original!=null && record.original==kept.original.digest,
            record?.candidate!=null && record.candidate==kept.candidate.digest,record?.handoffId))}
        validateCurrent();kept=unchanged(kept,Inspection.KEEP_CURRENT)
        register(afterVerified)
        validateCurrent();unchanged(kept,Inspection.KEEP_CURRENT)
        // The final target read must follow large retained-body hashes. Recheck only the small
        // typed marker afterwards; an unknown replacement cannot be cleared by this operation.
        beforeClear()
        val finalMarker=readMarker(forKeep=!raw.typed)
        if(finalMarker.digest!=kept.marker.digest) throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID)
        clear()
    }

    private fun writeVerified(target:Target,file:File,hash:String,expectedCurrentHash:String?,verifyFailure:StorageMsg,checkActive:()->Unit={}) {
        try {
            checkActive()
            target.write(file,expectedCurrentHash)
            if(fingerprint(target,checkActive)!=hash) throw failure(verifyFailure)
        } catch(error:Exception) {
            rethrowControlFlow(error)
            throw failure(StorageMsg.MIGRATION_WRITE_INCOMPLETE,error)
        }
    }
    private fun register(block:()->Unit) {
        try {block()} catch(error:Exception) {
            rethrowControlFlow(error)
            throw failure(StorageMsg.MIGRATION_INDEX_INCOMPLETE,error)
        }
    }
    private fun fingerprint(target:Target,checkActive:()->Unit={}):String? = try {
        checkActive()
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        target.read().use {input->val buffer=ByteArray(65536);while(true) {checkActive();val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n)}}
        checkActive()
        digest.digest().joinToString("") {"%02x".format(it)}
    } catch(error:Exception) {rethrowControlFlow(error);null}

    private fun retained(mode:Inspection=Inspection.PUBLISH,checkActive:()->Unit={}):Retained {
        fun inspect(file:File,strict:Boolean):FileProof {
            checkActive()
            val exists=file.exists();val regular=file.isFile;val size=file.length();val modified=file.lastModified()
            val digest=if(!exists || !regular) {
                if(exists && strict) throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID)
                null
            } else try {hashFile(file,checkActive)} catch(error:Exception) {
                rethrowControlFlow(error)
                if(strict) throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID,error)
                null
            }
            return FileProof(digest,exists,regular,size,modified)
        }
        return Retained(inspect(original,mode!=Inspection.KEEP_CURRENT),inspect(candidate,mode==Inspection.PUBLISH),inspect(marker,mode!=Inspection.KEEP_CURRENT))
    }
    private fun unchanged(before:Retained,mode:Inspection=Inspection.PUBLISH,checkActive:()->Unit={}):Retained {
        val after=retained(mode,checkActive)
        fun merge(old:FileProof,next:FileProof,lenient:Boolean):FileProof {
            if(old.copy(digest=null)!=next.copy(digest=null) ||
                (!lenient && old.digest!=next.digest) ||
                (lenient && old.digest!=null && next.digest!=null && old.digest!=next.digest))
                throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID)
            // A transient IO failure is not a content change. Keep the strongest digest seen so
            // recovery of readability cannot erase an earlier successfully observed checksum.
            return next.copy(digest=next.digest ?: old.digest)
        }
        return Retained(merge(before.original,after.original,mode==Inspection.KEEP_CURRENT),
            merge(before.candidate,after.candidate,mode!=Inspection.PUBLISH),merge(before.marker,after.marker,mode==Inspection.KEEP_CURRENT))
    }
    private fun requireLegacy(purpose:Purpose?) {
        if(purpose!=null) throw failure(StorageMsg.MIGRATION_RECOVERY_PENDING)
    }
    private fun readMarker(forKeep:Boolean=false):MigrationJournal.Marker = try {
        if(!marker.isFile) throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID)
        MigrationJournal.readMarker(markerOpen(marker).source(),forKeep)
    } catch(error:Exception) {rethrowControlFlow(error);throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID,error)}
    private fun readRecord():Record=parseRecord(readMarker())
    private fun parseRecord(raw:MigrationJournal.Marker):Record = try {
        val parsed=MigrationJournal.parse(raw)
        Record(parsed.original,parsed.candidate,parsed.digest,parsed.purpose?.let { purpose -> Purpose.entries.single { it.core==purpose } },parsed.handoffId)
    } catch(error:Exception) {rethrowControlFlow(error);throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID,error)}
    private fun checkRecord(record:Record,kept:Retained,checkCandidate:Boolean) {
        if(record.digest!=kept.marker.digest || record.original!=null && record.original!=kept.original.digest || checkCandidate && record.candidate!=null && record.candidate!=kept.candidate.digest)
            throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID)
    }
    private fun rethrowControlFlow(error:Exception) {
        if(error is ReferenceContractViolation || error is RecoveryRequired || error is CancellationException) throw error
    }
    private fun failure(reason:StorageMsg,cause:Throwable?=null)=RecoveryRequired(StorageText.t(reason),cause,reason)
    private fun clear() {
        // A crash during cleanup leaves harmless retained bytes, never a partially committed record.
        check(marker.delete() || !marker.exists()) {StorageText.t(StorageMsg.MIGRATION_RECORD_FAILED)}
        original.delete();candidate.delete()
    }
}
