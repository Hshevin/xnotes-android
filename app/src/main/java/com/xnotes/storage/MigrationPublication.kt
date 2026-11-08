package com.xnotes.storage

import com.xnotes.format.BundleMigration
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException

/** Owner serializes access per URI. Backups survive until file read-back AND registration succeed. */
class MigrationPublication internal constructor(private val directory:File,private val hashFile:(File)->String,
                                               private val markerOpen:(File)->InputStream) {
    internal constructor(directory:File,hashFile:(File)->String):this(directory,hashFile,{it.inputStream()})
    constructor(directory:File):this(directory,{BundleMigration.hash(it)})
    interface Target {
        fun read():InputStream
        /** Positive backend evidence only; a failed read or lost permission is not proof of absence. */
        fun confirmedMissing():Boolean = false
        /** Called only after the original, candidate and record have been durably staged. */
        fun write(source:File)
        /** Atomic backends recheck this version immediately before replacing their destination. */
        fun write(source:File,expectedCurrentHash:String?) {write(source)}
    }
    enum class Purpose(val key:String) {
        SAVE_NOTE("save-note"), SAVE_CANVAS("save-canvas"), CREATE_NOTE("create-note"), CREATE_CANVAS("create-canvas");
        internal val creation get()=this==CREATE_NOTE || this==CREATE_CANVAS
    }
    internal data class Validation(val original:File,val candidate:File,val current:File,val purpose:Purpose?)
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

    fun stage(source:File,replacement:File,purpose:Purpose?=null,expectedCandidateHash:String?=null,
              expectedOriginalHash:String?=null,handoffId:String?=null) {
        // A handoff record is durable intent, not permission to publish. The coordinated producer
        // will supply both inspected hashes; old callers cannot accidentally opt in via a new default.
        require(handoffId==null || (canonicalUuid(handoffId) && purpose!=null && !purpose.creation &&
            expectedOriginalHash?.matches(SHA256)==true && expectedCandidateHash?.matches(SHA256)==true))
        check(!pending) {StorageText.t(StorageMsg.MIGRATION_RECOVERY_PENDING)}
        check(directory.isDirectory || directory.mkdirs())
        copyDurable(source,original);copyDurable(replacement,candidate)
        val oldHash=BundleMigration.hash(original);val newHash=BundleMigration.hash(candidate)
        if(expectedCandidateHash!=null && newHash!=expectedCandidateHash || expectedOriginalHash!=null && oldHash!=expectedOriginalHash)
            throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        check(BundleMigration.hash(source)==oldHash && BundleMigration.hash(replacement)==newHash) {StorageText.t(StorageMsg.MIGRATION_VERIFY_FAILED)}
        val temp=File(directory,"pending.tmp")
        try {
            FileOutputStream(temp).use {val record=when {
                handoffId!=null -> "4\n$oldHash\n$newHash\n${requireNotNull(purpose).key}\n$handoffId"
                purpose!=null -> "3\n$oldHash\n$newHash\n${purpose.key}"
                else -> "2\n$oldHash\n$newHash"
            }
                it.write(record.toByteArray(Charsets.UTF_8));it.fd.sync()}
            Files.move(temp.toPath(),marker.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally {temp.delete()}
    }

    /** Validation borrows immutable retained files. current is the version matching the target SHA. */
    fun publish(target:Target,validate:(File,File,File)->Unit={_,_,_->},afterVerified:(File)->Unit={}) =
        publishValidated(target,validate={requireLegacy(it.purpose);validate(it.original,it.candidate,it.current)},afterVerified=afterVerified)

    internal fun publishValidated(target:Target,validate:(Validation)->Unit,afterVerified:(File)->Unit={},
                                  protectCandidate:(File)->Unit={}) {
        val record=readRecord()
        // No acknowledgement authority exists at this layer yet. In particular, ordinary open /
        // retry after a restart must never publish B before the working body + handoff is durable.
        if(record.handoffId!=null) throw failure(StorageMsg.MIGRATION_RECOVERY_PENDING)
        // Only dependency protection, never identity authority. The caller owns its lease on every path.
        protectCandidate(candidate)
        val kept=retained()
        val oldHash=kept.original.digest ?: throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID)
        val newHash=kept.candidate.digest ?: throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID)
        checkRecord(record,kept,checkCandidate=true)
        val currentHash=fingerprint(target)
        val current=when(currentHash) {
            newHash -> candidate
            oldHash -> original
            else -> throw failure(StorageMsg.MIGRATION_INTERRUPTED)
        }
        validate(Validation(original,candidate,current,record.purpose))
        unchanged(kept)
        if(fingerprint(target)!=currentHash) throw failure(StorageMsg.MIGRATION_INTERRUPTED)
        if(currentHash!=newHash) writeVerified(target,candidate,newHash,currentHash,StorageMsg.MIGRATION_VERIFY_FAILED)
        register {afterVerified(candidate)}
        if(fingerprint(target)!=newHash) throw failure(StorageMsg.MIGRATION_VERIFY_FAILED)
        unchanged(kept)
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

    private fun writeVerified(target:Target,file:File,hash:String,expectedCurrentHash:String?,verifyFailure:StorageMsg) {
        try {
            target.write(file,expectedCurrentHash)
            if(fingerprint(target)!=hash) throw failure(verifyFailure)
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
    private fun fingerprint(target:Target):String? = try {
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        target.read().use {input->val buffer=ByteArray(65536);while(true) {val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n)}}
        digest.digest().joinToString("") {"%02x".format(it)}
    } catch(error:Exception) {rethrowControlFlow(error);null}

    private fun retained(mode:Inspection=Inspection.PUBLISH):Retained {
        fun inspect(file:File,strict:Boolean):FileProof {
            val exists=file.exists();val regular=file.isFile;val size=file.length();val modified=file.lastModified()
            val digest=if(!exists || !regular) {
                if(exists && strict) throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID)
                null
            } else try {hashFile(file)} catch(error:Exception) {
                rethrowControlFlow(error)
                if(strict) throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID,error)
                null
            }
            return FileProof(digest,exists,regular,size,modified)
        }
        return Retained(inspect(original,mode!=Inspection.KEEP_CURRENT),inspect(candidate,mode==Inspection.PUBLISH),inspect(marker,mode!=Inspection.KEEP_CURRENT))
    }
    private fun unchanged(before:Retained,mode:Inspection=Inspection.PUBLISH):Retained {
        val after=retained(mode)
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
    private data class RawMarker(val bytes:ByteArray,val digest:String,val size:Long) {
        // No integer conversion: even an overflowing future version must remain typed.
        val typed:Boolean get() {
            val prefix=String(bytes,Charsets.UTF_8)
            if('\n' !in prefix && size>=257 && prefix.all {it in '0'..'9'}) return true
            val version=prefix.substringBefore('\n').trim().trimStart('0')
            return version.isNotEmpty() && version.all {it in '0'..'9'} && (version.length>1 || version[0]>'2')
        }
    }
    /** Strict paths read at most 257 bytes; only explicit malformed legacy keep hashes the remainder. */
    private fun readMarker(forKeep:Boolean=false):RawMarker {
        try {
            if(!marker.isFile) throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID)
            val out=ByteArrayOutputStream();val digest=java.security.MessageDigest.getInstance("SHA-256");var size=0L
            markerOpen(marker).use {input->
                val buffer=ByteArray(257)
                while(out.size()<257) {
                    val n=input.read(buffer,0,257-out.size());if(n<0) break
                    out.write(buffer,0,n);digest.update(buffer,0,n);size+=n
                }
                val prefix=RawMarker(out.toByteArray(),"",size)
                if(size>256) {
                    if(!forKeep || prefix.typed) throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID)
                    // Historical garbage can still be explicitly kept, with one digest over the same
                    // stream as its bounded classification. Unknown typed records never reach here.
                    val rest=ByteArray(65536)
                    while(true) {val n=input.read(rest);if(n<0) break;digest.update(rest,0,n);size+=n}
                }
            }
            return RawMarker(out.toByteArray(),digest.digest().joinToString("") {"%02x".format(it)},size)
        } catch(error:Exception) {
            rethrowControlFlow(error);throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID,error)
        }
    }
    private fun readRecord():Record=parseRecord(readMarker())
    private fun parseRecord(raw:RawMarker):Record {
        if(raw.size>256) throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID)
        val body=String(raw.bytes,Charsets.UTF_8)
        if(body=="1") return Record(null,null,raw.digest,null) // Historical records prove call-time consistency only.
        val parts=body.split('\n')
        val purpose=when {
            parts.size==3 && parts[0]=="2" -> null
            parts.size==4 && parts[0]=="3" -> Purpose.entries.singleOrNull {it.key==parts[3]}
                ?: throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID)
            parts.size==5 && parts[0]=="4" && canonicalUuid(parts[4]) ->
                Purpose.entries.singleOrNull {it.key==parts[3] && !it.creation}
                    ?: throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID)
            else -> throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID)
        }
        if(!parts[1].matches(SHA256) || !parts[2].matches(SHA256)) throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID)
        return Record(parts[1],parts[2],raw.digest,purpose,parts.getOrNull(4))
    }
    private fun canonicalUuid(value:String)=try {java.util.UUID.fromString(value).toString()==value} catch(_:IllegalArgumentException) {false}
    private companion object {val SHA256=Regex("[a-f0-9]{64}")}
    private fun checkRecord(record:Record,kept:Retained,checkCandidate:Boolean) {
        if(record.digest!=kept.marker.digest || record.original!=null && record.original!=kept.original.digest || checkCandidate && record.candidate!=null && record.candidate!=kept.candidate.digest)
            throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID)
    }
    private fun rethrowControlFlow(error:Exception) {
        if(error is ReferenceContractViolation || error is RecoveryRequired || error is CancellationException) throw error
    }
    private fun failure(reason:StorageMsg,cause:Throwable?=null)=RecoveryRequired(StorageText.t(reason),cause,reason)
    private fun copyDurable(source:File,destination:File) {
        val tmp=File(directory,destination.name+".tmp")
        try {
            FileOutputStream(tmp).use {out->source.inputStream().use {it.copyTo(out)};out.fd.sync()}
            Files.move(tmp.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally {tmp.delete()}
    }
    private fun clear() {
        // A crash during cleanup leaves harmless retained bytes, never a partially committed record.
        check(marker.delete() || !marker.exists()) {StorageText.t(StorageMsg.MIGRATION_RECORD_FAILED)}
        original.delete();candidate.delete()
    }
}
