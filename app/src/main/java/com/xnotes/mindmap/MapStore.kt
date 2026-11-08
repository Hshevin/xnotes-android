package com.xnotes.mindmap

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

interface MapTarget {
    fun read(): ByteArray
    fun write(bytes: ByteArray)
}
class MapConflict : IOException()

/** Caller serializes operations. A durable private journal precedes every external write. */
class MapStore(directory: File, key: String, private val target: MapTarget, legacyKeys:Set<String> = emptySet()) {
    private val journals=(listOf(key)+legacyKeys).distinct().map {File(directory,hash(it.toByteArray())+".json")}
    private var journal=journals.first()
    private var expectedHash: String? = null
    @Volatile var journalReady = false; private set
    data class Opened(val document: MindMapDocument, val recovered: Boolean = false, val conflict: Boolean = false)

    fun open(): Opened {
        if(journals.any {staging(it).exists()}) throw IOException("Unfinished recovery checkpoint")
        val existing=journals.filter {it.exists()}
        if(existing.size>1) throw IOException("Conflicting recovery journals")
        journal=existing.singleOrNull() ?: journals.first()
        val remote = runCatching { target.read() }.getOrNull()
        val remoteHash = remote?.let(::hash)
        // A corrupt journal is retained; do not mask an otherwise readable original or overwrite it.
        val saved = if (journal.exists()) runCatching {
            val j = journal.inputStream().use { JSONObject(String(MapCodec.readBytes(it), Charsets.UTF_8)) }
            val doc = MapCodec.fromJson(j.getJSONObject("document"))
            Triple(doc, j.getString("baseHash"), j.getBoolean("pending"))
        }.getOrElse { throw IOException("Invalid recovery journal", it) } else null
        if (saved != null && saved.third) {
            expectedHash = saved.second
            journalReady = true
            val contentHash = hash(MapCodec.encode(saved.first))
            if (contentHash == remoteHash) {
                expectedHash = remoteHash
                checkpoint(saved.first, pending = false)
                return Opened(saved.first)
            }
            return Opened(saved.first, recovered = true, conflict = remoteHash != saved.second)
        }
        val decoded = remote?.let { runCatching { MapCodec.decode(it) }.getOrNull() }
        if (decoded == null && saved != null) {
            expectedHash = saved.second
            journalReady = true
            return Opened(saved.first, recovered = true, conflict = true)
        }
        val doc = decoded ?: throw IOException("Unreadable mind map")
        expectedHash = remoteHash
        return Opened(doc)
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
