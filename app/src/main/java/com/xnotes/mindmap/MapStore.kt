package com.xnotes.mindmap

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.FilterInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

interface MapTarget {
    fun read(): ByteArray
    fun write(bytes: ByteArray)
}
class MapConflict : IOException()
internal class MapRecoveryObservationException(message:String,cause:Throwable?=null):IOException(message,cause)

/** Caller serializes operations. A durable private journal precedes every external write. */
class MapStore(directory: File, key: String, private val target: MapTarget, legacyKeys:Set<String> = emptySet()) {
    internal val recoveryKeys:Set<String> = java.util.Collections.unmodifiableSet(linkedSetOf(key).apply {addAll(legacyKeys)})
    private val journals=recoveryKeys.map {File(directory,hash(it.toByteArray())+".json")}
    private var journal=journals.first()
    private var expectedHash: String? = null
    @Volatile var journalReady = false; private set
    /** Actual raw bytes observed by open(), independent of the recovery writer's baseline. */
    @Volatile internal var openedRawSha256:String?=null;private set
    data class Opened(val document: MindMapDocument, val recovered: Boolean = false, val conflict: Boolean = false)
    data class Observed(val document:MindMapDocument,val recovered:Boolean,val conflict:Boolean,val remoteAvailable:Boolean,
        val remoteBytes:Long,val journalBytes:Long)
    private data class Inspection(val opened:Opened,val chosen:File,val expected:String?,val ready:Boolean,
        val checkpoint:Boolean=false,val remoteAvailable:Boolean,val remoteBytes:Long,val journalBytes:Long,val rawSha256:String?)

    fun open(): Opened {
        val read=inspect({})
        openedRawSha256=read.rawSha256
        journal=read.chosen;expectedHash=read.expected
        if(read.ready) journalReady=true
        if(read.checkpoint) checkpoint(read.opened.document,pending=false)
        return read.opened
    }
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
        if(journals.any {staging(it).exists()}) throw MapRecoveryObservationException("Unfinished recovery checkpoint")
        val existing=journals.filter {it.exists()}
        if(existing.size>1) throw MapRecoveryObservationException("Conflicting recovery journals")
        val chosen=existing.singleOrNull() ?: journals.first()
        val remote=try {target.read()} catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
            catch(_:Exception) {checkActive();null}
        checkActive()
        val remoteHash = remote?.let(::hash)
        var journalBytes=0L
        // A corrupt journal is retained; do not mask an otherwise readable original or overwrite it.
        val saved = if (chosen.exists()) runCatching {
            val bytes=chosen.inputStream().use {input->MapCodec.readBytes(object:FilterInputStream(input) {
                override fun read():Int {checkActive();return super.read().also {if(it>=0) readJournalBytes(1)}}
                override fun read(bytes:ByteArray,offset:Int,length:Int):Int {checkActive();return `in`.read(bytes,offset,length).also {if(it>0) readJournalBytes(it)}}
            })};journalBytes=bytes.size.toLong();checkActive()
            val j = JSONObject(String(bytes, Charsets.UTF_8))
            val doc = MapCodec.fromJson(j.getJSONObject("document"))
            checkActive()
            Triple(doc, j.getString("baseHash"), j.getBoolean("pending"))
        }.getOrElse {
            if(it is java.util.concurrent.CancellationException) throw it
            checkActive();throw MapRecoveryObservationException("Invalid recovery journal",it)
        } else null
        if(expectedDocumentId!=null && saved!=null && saved.first.id!=expectedDocumentId)
            throw MapRecoveryObservationException("Recovery checkpoint belongs to another mind map")
        fun result(opened:Opened,expected:String?,ready:Boolean,checkpoint:Boolean=false)=Inspection(opened,chosen,expected,ready,
            checkpoint,remote!=null,remote?.size?.toLong() ?: 0L,journalBytes,remoteHash)
        if (saved != null && saved.third) {
            val contentHash = hash(MapCodec.encode(saved.first))
            checkActive()
            if (contentHash == remoteHash) {
                return result(Opened(saved.first),remoteHash,true,true)
            }
            return result(Opened(saved.first,recovered=true,conflict=remoteHash!=saved.second),saved.second,true)
        }
        val decoded = remote?.let { runCatching { MapCodec.decode(it) }.getOrNull() }
        checkActive()
        if (decoded == null && saved != null) {
            return result(Opened(saved.first,recovered=true,conflict=true),saved.second,true)
        }
        val doc = decoded ?: throw IOException("Unreadable mind map")
        return result(Opened(doc),remoteHash,false)
    }

    /** A new copy may claim only a readable, empty destination without any earlier recovery work. */
    fun create(doc:MindMapDocument,checkActive:()->Unit={}) {
        checkActive()
        val bytes=MapCodec.encode(doc)
        checkActive()
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
        val remote = target.read()
        val doc = MapCodec.decode(remote)
        check(!journal.exists() || journal.delete()) { "Cannot remove the recovery journal" }
        expectedHash = hash(remote)
        journalReady = false
        return Opened(doc)
    }

    private fun checkpoint(doc: MindMapDocument, pending: Boolean,checkActive:()->Unit={}) {
        checkActive()
        val data = JSONObject().put("baseHash", requireNotNull(expectedHash))
            .put("pending", pending).put("document", MapCodec.toJson(doc)).toString().toByteArray(Charsets.UTF_8)
        // Envelope adds a small fixed overhead to the document's size.
        require(data.size <= MapCodec.MAX_BYTES)
        check(journal.parentFile!!.isDirectory || journal.parentFile!!.mkdirs())
        val staging=staging(journal)
        checkActive()
        if(!staging.createNewFile()) throw IOException("Unfinished recovery checkpoint")
        var synced=false
        try {
            FileOutputStream(staging).use {out->
                var offset=0
                while(offset<data.size) {checkActive();val count=minOf(8192,data.size-offset);out.write(data,offset,count);offset+=count}
                out.fd.sync();synced=true
            }
            Files.move(staging.toPath(), journal.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            journalReady = true
        } finally {
            // This invocation owns the incomplete file. A fully synced checkpoint remains
            // recovery material if its atomic move fails; earlier staging files are never ours.
            if(!synced) staging.delete()
        }
    }
    private fun staging(file:File)=File(file.parentFile,file.name+".pending")
    companion object {
        /** Base for a destination that could not be read; never equal to a content hash. */
        private const val ABSENT = "absent"
        private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
