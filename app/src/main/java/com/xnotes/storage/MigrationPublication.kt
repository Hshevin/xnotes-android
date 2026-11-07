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
class MigrationPublication internal constructor(private val directory:File,private val hashFile:(File)->String) {
    constructor(directory:File):this(directory,{BundleMigration.hash(it)})
    interface Target {
        fun read():InputStream
        /** Positive backend evidence only; a failed read or lost permission is not proof of absence. */
        fun confirmedMissing():Boolean = false
        /** Called only after the original, candidate and record have been durably staged. */
        fun write(source:File)
    }
    class RecoveryRequired(message:String,cause:Throwable?=null,val reason:StorageMsg?=null):IllegalStateException(message,cause)
    private val original get()=File(directory,"original.zip")
    private val candidate get()=File(directory,"candidate.zip")
    private val marker get()=File(directory,"pending")
    val pending get()=marker.exists()
    private data class Record(val original:String?,val candidate:String?,val digest:String)
    private data class FileProof(val digest:String?,val exists:Boolean,val regular:Boolean,val size:Long,val modified:Long)
    private data class Retained(val original:FileProof,val candidate:FileProof,val marker:FileProof)
    private enum class Inspection { PUBLISH, RESTORE, KEEP_CURRENT }

    fun stage(source:File,replacement:File) {
        check(!pending) {StorageText.t(StorageMsg.MIGRATION_RECOVERY_PENDING)}
        check(directory.isDirectory || directory.mkdirs())
        copyDurable(source,original);copyDurable(replacement,candidate)
        val oldHash=BundleMigration.hash(original);val newHash=BundleMigration.hash(candidate)
        check(BundleMigration.hash(source)==oldHash && BundleMigration.hash(replacement)==newHash) {StorageText.t(StorageMsg.MIGRATION_VERIFY_FAILED)}
        val temp=File(directory,"pending.tmp")
        try {
            FileOutputStream(temp).use {it.write("2\n$oldHash\n$newHash".toByteArray(Charsets.UTF_8));it.fd.sync()}
            Files.move(temp.toPath(),marker.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally {temp.delete()}
    }

    /** Validation borrows immutable retained files. current is the version matching the target SHA. */
    fun publish(target:Target,validate:(File,File,File)->Unit={_,_,_->},afterVerified:(File)->Unit={}) {
        val record=readRecord();val kept=retained()
        val oldHash=kept.original.digest ?: throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID)
        val newHash=kept.candidate.digest ?: throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID)
        checkRecord(record,kept,checkCandidate=true)
        val currentHash=fingerprint(target)
        val current=when(currentHash) {
            newHash -> candidate
            oldHash -> original
            else -> throw failure(StorageMsg.MIGRATION_INTERRUPTED)
        }
        validate(original,candidate,current)
        unchanged(kept)
        if(fingerprint(target)!=currentHash) throw failure(StorageMsg.MIGRATION_INTERRUPTED)
        if(currentHash!=newHash) writeVerified(target,candidate,newHash,StorageMsg.MIGRATION_VERIFY_FAILED)
        register {afterVerified(candidate)}
        if(fingerprint(target)!=newHash) throw failure(StorageMsg.MIGRATION_VERIFY_FAILED)
        unchanged(kept)
        clear()
    }

    /** Explicit recovery. A damaged/missing candidate must not prevent restoring the verified original. */
    fun restore(target:Target,validate:(File)->Unit={},afterVerified:(File)->Unit={}) {
        val record=readRecord();var kept=retained(Inspection.RESTORE)
        val hash=kept.original.digest ?: throw failure(StorageMsg.NO_BACKUP_TO_RESTORE)
        checkRecord(record,kept,checkCandidate=false)
        val current=fingerprint(target)
        if(current==null && !target.confirmedMissing()) throw failure(StorageMsg.NOTE_UNREADABLE)
        validate(original)
        kept=unchanged(kept,Inspection.RESTORE)
        if(fingerprint(target)!=current || current==null && !target.confirmedMissing()) throw failure(StorageMsg.MIGRATION_INTERRUPTED)
        if(current!=hash) writeVerified(target,original,hash,StorageMsg.RESTORE_VERIFY_FAILED)
        register {afterVerified(original)}
        if(fingerprint(target)!=hash) throw failure(StorageMsg.RESTORE_VERIFY_FAILED)
        unchanged(kept,Inspection.RESTORE)
        clear()
    }

    /** Caller validates the captured current file/version; pure validation is repeated after DB commit. */
    fun keepCurrent(validate:(File?,File?)->Unit={_,_->},afterVerified:()->Unit={}) {
        var kept=retained(Inspection.KEEP_CURRENT)
        fun validateCurrent() {validate(original.takeIf {kept.original.regular},candidate.takeIf {kept.candidate.regular})}
        validateCurrent();kept=unchanged(kept,Inspection.KEEP_CURRENT)
        register(afterVerified)
        validateCurrent();unchanged(kept,Inspection.KEEP_CURRENT)
        clear()
    }

    private fun writeVerified(target:Target,file:File,hash:String,verifyFailure:StorageMsg) {
        try {
            target.write(file)
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
    private fun readRecord():Record {
        try {
            if(!marker.isFile) throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID)
            val out=ByteArrayOutputStream()
            marker.inputStream().use {input->
                val buffer=ByteArray(257)
                while(out.size()<=256) {
                    val n=input.read(buffer,0,minOf(buffer.size,257-out.size()));if(n<0) break
                    out.write(buffer,0,n)
                }
            }
            if(out.size()>256) throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID)
            val bytes=out.toByteArray()
            val digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
            val body=String(bytes,Charsets.UTF_8)
            if(body=="1") return Record(null,null,digest) // Historical records prove call-time consistency only.
            val parts=body.split('\n');val hash=Regex("[a-f0-9]{64}")
            if(parts.size!=3 || parts[0]!="2" || !parts[1].matches(hash) || !parts[2].matches(hash)) throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID)
            return Record(parts[1],parts[2],digest)
        } catch(error:Exception) {
            rethrowControlFlow(error)
            throw failure(StorageMsg.MIGRATION_RECOVERY_INVALID,error)
        }
    }
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
