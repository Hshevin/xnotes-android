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
class MapStore(directory: File, key: String, private val target: MapTarget) {
    private val journal = File(directory, hash(key.toByteArray()) + ".json")
    private var expectedHash: String? = null
    @Volatile var journalReady = false; private set
    data class Opened(val document: MindMapDocument, val recovered: Boolean = false, val conflict: Boolean = false)

    fun open(): Opened {
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
        if (currentHash != expectedHash) throw MapConflict()
        target.write(bytes)
        if (hash(target.read()) != hash(bytes)) throw IOException("Destination verification failed")
        expectedHash = hash(bytes)
        checkpoint(doc, pending = false)
    }

    private fun checkpoint(doc: MindMapDocument, pending: Boolean) {
        val data = JSONObject().put("baseHash", requireNotNull(expectedHash))
            .put("pending", pending).put("document", MapCodec.toJson(doc)).toString().toByteArray(Charsets.UTF_8)
        // Envelope adds a small fixed overhead to the document's size.
        require(data.size <= MapCodec.MAX_BYTES)
        check(journal.parentFile!!.isDirectory || journal.parentFile!!.mkdirs())
        val staging = File(journal.parentFile, journal.name + ".pending")
        try {
            FileOutputStream(staging).use { it.write(data); it.fd.sync() }
            Files.move(staging.toPath(), journal.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            journalReady = true
        } finally { staging.delete() }
    }
    companion object {
        private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
