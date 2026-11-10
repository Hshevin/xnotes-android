package com.xnotes.mindmap

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.FilterInputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

interface MapTarget {
    fun read(): ByteArray
    fun write(bytes: ByteArray)
}
class MapConflict : IOException()
internal class MapRecoveryObservationException(message:String,cause:Throwable?=null):IOException(message,cause)

/** Caller serializes operations. A durable private journal precedes every external write. */
class MapStore internal constructor(directory: File, key: String, private val target: MapTarget, legacyKeys:Set<String>,
    private val attributes:(File)->BasicFileAttributes) {
    constructor(directory:File,key:String,target:MapTarget,legacyKeys:Set<String> = emptySet()):
        this(directory,key,target,legacyKeys,{Files.readAttributes(it.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS)})
    internal val recoveryKeys:Set<String> = java.util.Collections.unmodifiableSet(linkedSetOf(key).apply {addAll(legacyKeys)})
    private val journals=recoveryKeys.map {File(directory,hash(it.toByteArray())+".json")}
    private var journal=journals.first()
    private var expectedHash: String? = null
    @Volatile var journalReady = false; private set
    /** Actual raw bytes observed by open(), independent of the recovery writer's baseline. */
    @Volatile internal var openedRawSha256:String?=null;private set
    // File identity survives our writes; the body digest detects changes independently.
    internal data class JournalFingerprint(val key:Any,val size:Long,val sha256:String)
    internal data class JournalHead(val exists:Boolean,val fingerprint:JournalFingerprint?)
    /** Actual first-read bytes plus only the deterministic reconciliation performed by this open. */
    internal class RecoveryLoadProof internal constructor(
        internal val issuer:Any,val documentId:String,internal val rawSha256:String?,
        internal val originalHeads:Map<File,JournalHead>,internal val reconciledHeads:Map<File,JournalHead>,
    )
    @Volatile internal var openedRecoveryProof:RecoveryLoadProof?=null;private set
    @Volatile private var durabilityEpoch:Any=Any()
    /** This describes actual recoverable content, never the original-file overwrite baseline. */
    internal class RecoveryDurabilityProof internal constructor(
        internal val issuer:Any,internal val epoch:Any,val documentId:String,
        internal val rawSha256:String?,internal val heads:Map<File,JournalHead>,
    )
    data class Opened(val document: MindMapDocument, val recovered: Boolean = false, val conflict: Boolean = false)
    data class Observed(val document:MindMapDocument,val recovered:Boolean,val conflict:Boolean,val remoteAvailable:Boolean,
        val remoteBytes:Long,val journalBytes:Long)
    private data class Inspection(val opened:Opened,val chosen:File,val expected:String?,val ready:Boolean,
        val checkpoint:Boolean=false,val remoteAvailable:Boolean,val remoteBytes:Long,val journalBytes:Long,val rawSha256:String?,
        val heads:Map<File,JournalHead>,val pending:Map<File,Triple<MindMapDocument,String,Boolean>?>,val identitiesMatch:Boolean) {
        val provable get()=heads.values.all {!it.exists || it.fingerprint!=null}
    }
    private data class JournalRead(val checkpoint:Triple<MindMapDocument,String,Boolean>?,val head:JournalHead)

    fun open(expectedDocumentId:String?=null): Opened {
        durabilityEpoch=Any()
        openedRecoveryProof=null
        val read=inspect({},expectedDocumentId=expectedDocumentId)
        if(expectedDocumentId!=null && !read.provable) throw MapRecoveryObservationException("Unverifiable recovery checkpoint")
        val reconciled=if(read.provable) read.heads.toMutableMap().also {reconcileOpened(read,it)} else null
        if(reconciled==null) reconcileStaging()
        openedRawSha256=read.rawSha256
        journal=read.chosen;expectedHash=read.expected
        if(read.ready) journalReady=true
        if(read.checkpoint) checkpoint(read.opened.document,pending=false,knownHeads=reconciled)
        if(reconciled!=null && read.identitiesMatch) openedRecoveryProof=RecoveryLoadProof(this,read.opened.document.id,read.rawSha256,
            read.heads.toMap(),reconciled.toMap())
        return read.opened
    }
    /** Read-only final comparison. A later observation must never replace an actor's first load. */
    internal fun verifyOpenedRecovery(proof:RecoveryLoadProof,checkActive:()->Unit={}):Boolean {
        if(proof.issuer!==this || openedRecoveryProof!==proof) return false
        return try {
            val read=inspect(checkActive,expectedDocumentId=proof.documentId)
            checkActive()
            read.provable && read.rawSha256==proof.rawSha256 && read.heads==proof.reconciledHeads &&
                read.opened.document.id==proof.documentId
        } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
        catch(_:Exception) {false}
    }
    /** Caller holds the URI mutex. Only this actual read can prove a clean body's durability. */
    internal fun observeRecoveryDurability(document:MindMapDocument,checkActive:()->Unit={}):RecoveryDurabilityProof? {
        val epoch=Any().also {durabilityEpoch=it}
        return try {
            val read=inspect(checkActive,expectedDocumentId=document.id)
            checkActive()
            if(!read.provable || !read.identitiesMatch || read.opened.document!=document || durabilityEpoch!==epoch) null
            else RecoveryDurabilityProof(this,epoch,document.id,read.rawSha256,read.heads.toMap())
        } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
        catch(_:Exception) {null}
    }
    /** Pure memory check; writes or a newer actual durability observation retire the old ticket. */
    internal fun recoveryDurabilityCurrent(proof:RecoveryDurabilityProof)=proof.issuer===this && proof.epoch===durabilityEpoch
    /** Indexing sees the same recovery winner without changing a writer's baseline or either file. */
    fun observe(checkActive:()->Unit={},readJournalBytes:(Int)->Unit={}):Observed {
        val read=inspect(checkActive,readJournalBytes)
        return Observed(read.opened.document,read.opened.recovered,read.opened.conflict,read.remoteAvailable,read.remoteBytes,read.journalBytes)
    }
    /** Pair adoption must also reject a foreign checkpoint that would not win over readable JSON. */
    internal fun observeForIdentity(documentId:String,checkActive:()->Unit={}):Observed {
        val read=inspect(checkActive,expectedDocumentId=documentId)
        return Observed(read.opened.document,read.opened.recovered,read.opened.conflict,read.remoteAvailable,read.remoteBytes,read.journalBytes)
    }
    /** A live editor must not claim to have inspected a newly discovered alias journal. */
    internal fun recoveryOutside(covered:Set<String>):Boolean = recoveryKeys.zip(journals).any {(key,file)->
        key !in covered && (file.exists() || staging(file).exists())
    }
    private fun inspect(checkActive:()->Unit,readJournalBytes:(Int)->Unit={},expectedDocumentId:String?=null):Inspection {
        checkActive()
        var journalBytes=0L
        val heads=linkedMapOf<File,JournalHead>()
        val pending=linkedMapOf<File,Triple<MindMapDocument,String,Boolean>?>()
        val inspectedIds=linkedSetOf<String>()
        val savedByFile=journals.associateWith {file->
            fun read(at:File):JournalRead=readJournal(at,checkActive) {count->journalBytes+=count;readJournalBytes(count)}.also {
                heads[at]=it.head
                it.checkpoint?.first?.id?.let(inspectedIds::add)
                if(expectedDocumentId!=null && it.checkpoint?.first?.id?.let {id->id!=expectedDocumentId}==true)
                    throw MapRecoveryObservationException("Recovery checkpoint belongs to another mind map")
            }
            val stage=read(staging(file));pending[file]=stage.checkpoint
            // Even a complete stage cannot hide a foreign valid published checkpoint.
            val published=read(file)
            // A complete staging file is the next checkpoint for this same serialized writer.
            // An incomplete one cannot supersede the last published recovery journal.
            stage.checkpoint ?: if(published.head.exists) published.checkpoint
                ?: throw MapRecoveryObservationException("Invalid recovery journal") else null
        }
        val existing=journals.filter {savedByFile[it]!=null}
        if(existing.size>1) throw MapRecoveryObservationException("Conflicting recovery journals")
        val chosen=existing.singleOrNull() ?: journals.first()
        val remote=try {target.read().clone()} catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
            catch(_:Exception) {checkActive();null}
        checkActive()
        val remoteHash = remote?.let(::hash)
        val saved=savedByFile[chosen]
        val decoded = remote?.let { runCatching { MapCodec.decode(it) }.getOrNull() }
        decoded?.id?.let(inspectedIds::add)
        if(expectedDocumentId!=null && decoded!=null && decoded.id!=expectedDocumentId)
            throw MapRecoveryObservationException("Raw file belongs to another mind map")
        fun result(opened:Opened,expected:String?,ready:Boolean,checkpoint:Boolean=false)=Inspection(opened,chosen,expected,ready,
            checkpoint,remote!=null,remote?.size?.toLong() ?: 0L,journalBytes,remoteHash,heads,pending,
            inspectedIds.all {it==opened.document.id})
        if (saved != null && saved.third) {
            val contentHash = hash(MapCodec.encode(saved.first))
            checkActive()
            if (contentHash == remoteHash) {
                return result(Opened(saved.first),remoteHash,true,true)
            }
            return result(Opened(saved.first,recovered=true,conflict=remoteHash!=saved.second),saved.second,true)
        }
        checkActive()
        if (decoded == null && saved != null) {
            return result(Opened(saved.first,recovered=true,conflict=true),saved.second,true)
        }
        val doc = decoded ?: throw IOException("Unreadable mind map")
        return result(Opened(doc),remoteHash,false)
    }

    /** A new copy may claim only a readable, empty destination without any earlier recovery work. */
    fun create(doc:MindMapDocument,checkActive:()->Unit={}) {
        durabilityEpoch=Any()
        checkActive()
        val bytes=MapCodec.encode(doc)
        checkActive()
        reconcileStaging(checkActive,promote=false)
        if(journals.any {it.exists() || staging(it).exists()}) throw IOException("Destination has recovery work")
        val before=target.read()
        checkActive()
        if(before.isNotEmpty()) throw MapConflict()
        expectedHash=hash(before)
        journalReady=false
        checkpoint(doc,pending=true,checkActive)
        checkActive()
        val current=target.read()
        checkActive()
        if(current.isNotEmpty()) throw MapConflict()
        target.write(bytes)
        checkActive()
        val written=target.read()
        checkActive()
        if(!written.contentEquals(bytes)) throw IOException("Destination verification failed")
        expectedHash=hash(bytes)
        checkpoint(doc,pending=false,checkActive)
        checkActive()
    }

    fun save(doc: MindMapDocument) {
        durabilityEpoch=Any()
        journalReady = false
        val bytes = MapCodec.encode(doc) // validate before touching either copy
        checkpoint(doc, pending = true)
        val remote = runCatching { target.read() }.getOrNull()
        val currentHash = remote?.let(::hash)
        if (currentHash == hash(bytes)) {
            expectedHash = currentHash
            checkpoint(doc, pending = false)
            return
        }
        if ((currentHash ?: ABSENT) != expectedHash) throw MapConflict()
        target.write(bytes)
        if (hash(target.read()) != hash(bytes)) throw IOException("Destination verification failed")
        expectedHash = hash(bytes)
        checkpoint(doc, pending = false)
    }

    /** Retain the actual body locally without granting or exercising original-file write access. */
    internal fun retainForRecovery(doc:MindMapDocument) {
        durabilityEpoch=Any()
        journalReady=false
        MapCodec.encode(doc) // reject invalid bodies before touching the existing recovery copy
        checkpoint(doc,pending=true)
    }

    /** The caller holds the actual URI lock, so this is the just-verified publication, not a later read. */
    internal fun saveAndVerify(doc:MindMapDocument):String {
        save(doc)
        return requireNotNull(expectedHash)
    }
    internal fun overwriteAndVerify(doc:MindMapDocument):String {
        overwrite(doc)
        return requireNotNull(expectedHash)
    }

    /**
     * Explicit conflict choice "keep this version": accept whatever the destination holds now as the
     * base, then save normally, so the journal stays ahead of the write and verification still runs.
     * Without this the base hash recorded at open never matches again and every save is a conflict.
     */
    fun overwrite(doc: MindMapDocument) {
        expectedHash = runCatching { target.read() }.getOrNull()?.let(::hash) ?: ABSENT
        save(doc)
    }

    /**
     * Explicit conflict choice "use the file's version": drop the local recovery copy and reload the
     * destination. An unreadable destination throws before the journal is touched, so it is kept.
     */
    fun discardRecovery(): Opened {
        durabilityEpoch=Any()
        val remote = target.read()
        val doc = MapCodec.decode(remote)
        for(file in journals.flatMap {listOf(it,staging(it))}) {
            check(!file.exists() || file.delete()) { "Cannot remove the recovery journal" }
        }
        journal=journals.first()
        expectedHash = hash(remote)
        journalReady = false
        return Opened(doc)
    }

    private fun checkpoint(doc: MindMapDocument, pending: Boolean,checkActive:()->Unit={},knownHeads:MutableMap<File,JournalHead>?=null) {
        checkActive()
        if(knownHeads==null) reconcileStaging(checkActive)
        else if(!headsMatch(knownHeads,checkActive)) throw MapRecoveryObservationException("Recovery changed during open")
        val data = JSONObject().put("baseHash", requireNotNull(expectedHash))
            .put("pending", pending).put("document", MapCodec.toJson(doc)).toString().toByteArray(Charsets.UTF_8)
        // Envelope adds a small fixed overhead to the document's size.
        require(data.size <= MapCodec.MAX_BYTES)
        check(journal.parentFile!!.isDirectory || journal.parentFile!!.mkdirs())
        val staging=staging(journal)
        checkActive()
        if(!staging.createNewFile()) throw IOException("Unfinished recovery checkpoint")
        val owned=attributes(staging)
        var synced=false
        try {
            FileOutputStream(staging).use {out->
                var offset=0
                while(offset<data.size) {checkActive();val count=minOf(8192,data.size-offset);out.write(data,offset,count);offset+=count}
                out.fd.sync();synced=true
            }
            val expected=knownHeads?.let {JournalHead(true,JournalFingerprint(requireNotNull(owned.fileKey()),data.size.toLong(),hash(data)))}
            if(expected!=null && readJournal(staging,checkActive,decode=false).head!=expected)
                throw MapRecoveryObservationException("Checkpoint stage changed")
            Files.move(staging.toPath(), journal.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            journalReady = true
            knownHeads?.let {it[journal]=requireNotNull(expected);it[staging]=absentHead}
        } finally {
            // This invocation owns the incomplete file. A fully synced checkpoint remains
            // recovery material if its atomic move fails; earlier staging files are never ours.
            if(!synced && sameFile(staging,owned)) staging.delete()
        }
    }
    /** Compare against the first inspect; rereads only veto, and never establish a new baseline. */
    private fun headsMatch(heads:Map<File,JournalHead>,checkActive:()->Unit={}):Boolean =
        heads.all {(file,head)->checkActive();readJournal(file,checkActive,decode=false).head==head}

    private fun reconcileOpened(read:Inspection,heads:MutableMap<File,JournalHead>) {
        if(!headsMatch(heads)) throw MapRecoveryObservationException("Recovery changed during open")
        for(file in journals) {
            val stage=staging(file)
            val before=requireNotNull(heads[stage])
            if(!before.exists) continue
            if(readJournal(stage,{},decode=false).head!=before || readJournal(file,{},decode=false).head!=heads[file])
                throw MapRecoveryObservationException("Recovery changed during reconciliation")
            if(read.pending[file]!=null) {
                Files.move(stage.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
                heads[file]=before
            } else check(stage.delete() || !stage.exists()) {"Cannot remove an incomplete recovery checkpoint"}
            heads[stage]=absentHead
        }
    }
    /** Only the serialized editing owner cleans/promotes checkpoints; indexing stays read-only. */
    private fun reconcileStaging(checkActive:()->Unit={},promote:Boolean=true) {
        for(file in journals) {
            val pending=staging(file)
            if(!pending.exists()) continue
            val valid=readCheckpoint(pending,checkActive)
            checkActive()
            if(valid!=null) {
                if(promote) Files.move(pending.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            }
            else check(pending.delete() || !pending.exists()) {"Cannot remove an incomplete recovery checkpoint"}
        }
    }
    /** I/O, cancellation and read-budget failures propagate; only invalid bytes are discardable. */
    private fun readCheckpoint(file:File,checkActive:()->Unit,readBytes:(Int)->Unit={}):Triple<MindMapDocument,String,Boolean>? {
        return readJournal(file,checkActive,readBytes=readBytes).checkpoint
    }
    private fun sameFile(file:File,expected:BasicFileAttributes):Boolean=try {
        val actual=attributes(file)
        actual.isRegularFile && expected.fileKey()!=null && actual.fileKey()==expected.fileKey()
    } catch(_:Exception) {false}
    private fun readJournal(file:File,checkActive:()->Unit,decode:Boolean=true,readBytes:(Int)->Unit={}):JournalRead {
        checkActive()
        val before=try {attributes(file)}
            catch(_:NoSuchFileException) {return JournalRead(null,absentHead)}
        if(!before.isRegularFile || before.fileKey()==null) throw MapRecoveryObservationException("Recovery checkpoint is not a regular file")
        if(before.size()>MapCodec.MAX_BYTES) return JournalRead(null,JournalHead(true,null))
        val bytes=Files.newInputStream(file.toPath(),READ,NOFOLLOW_LINKS).use {input->MapCodec.readBytes(object:FilterInputStream(input) {
            override fun read():Int {checkActive();return super.read().also {if(it>=0) readBytes(1)}}
            override fun read(bytes:ByteArray,offset:Int,length:Int):Int {checkActive();return `in`.read(bytes,offset,length).also {if(it>0) readBytes(it)}}
        })}
        checkActive()
        val after=attributes(file)
        if(!after.isRegularFile || before.fileKey()!=after.fileKey() ||
            before.size()!=after.size() || bytes.size.toLong()!=after.size() || before.lastModifiedTime()!=after.lastModifiedTime())
            throw MapRecoveryObservationException("Recovery checkpoint changed while reading")
        val head=JournalHead(true,JournalFingerprint(requireNotNull(after.fileKey()),bytes.size.toLong(),hash(bytes)))
        if(!decode) return JournalRead(null,head)
        val checkpoint=try {
            val j=JSONObject(String(bytes,Charsets.UTF_8))
            val base=j.get("baseHash").also {require(it is String && (it==ABSENT || it.matches(Regex("[0-9a-f]{64}"))))} as String
            val pending=j.get("pending").also {require(it is Boolean)} as Boolean
            Triple(MapCodec.fromJson(j.getJSONObject("document")),base,pending)
        } catch(_:org.json.JSONException) {null} catch(_:IllegalArgumentException) {null}
        return JournalRead(checkpoint,head)
    }
    private fun staging(file:File)=File(file.parentFile,file.name+".pending")
    companion object {
        private val absentHead=JournalHead(false,null)
        /** Base for a destination that could not be read; never equal to a content hash. */
        private const val ABSENT = "absent"
        private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
