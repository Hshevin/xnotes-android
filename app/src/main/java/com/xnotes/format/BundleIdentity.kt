package com.xnotes.format

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** Identity metadata is separate; page identity copies use lossless manifest edits, never model encoding. */
data class BundleIdentity(val documentId: String = UUID.randomUUID().toString(), val pdfResource: String? = null, val externalPdf: Boolean = false) {
    init {
        require(UUID.fromString(documentId).toString() == documentId) { "Invalid document UUID" }
        require(!externalPdf || pdfResource?.matches(Regex("[a-f0-9]{64}")) == true) { com.xnotes.storage.StorageText.t(com.xnotes.storage.StorageMsg.PDF_RESOURCE_CORRUPT) }
    }
    fun bytes(): ByteArray = JSONObject().put("version", if (externalPdf) 2 else 1).put("documentId", documentId)
        .put("pdfResource", pdfResource ?: JSONObject.NULL).toString().toByteArray(Charsets.UTF_8)
    fun write(zip: ZipOutputStream) {
        zip.putNextEntry(ZipEntry(ENTRY)); zip.write(bytes()); zip.closeEntry()
    }
    companion object {
        const val ENTRY = "xnotes-identity.json"
        fun read(input: InputStream): BundleIdentity {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(512)
            while (out.size() <= 4096) {
                val n = input.read(buffer, 0, minOf(buffer.size, 4097 - out.size()))
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            val bytes = out.toByteArray()
            require(bytes.size <= 4096) { "Document identity metadata is too large" }
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            require(json.getInt("version") in 1..2) { "Unsupported document identity version" }
            return BundleIdentity(json.getString("documentId"), json.opt("pdfResource") as? String, json.getInt("version") == 2)
        }
        fun read(file: File): BundleIdentity? = ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().filter { it.name == ENTRY }.toList()
            require(entries.size <= 1) { "Duplicate identity metadata" }
            entries.singleOrNull()?.let { zip.getInputStream(it).use(::read) }
        }
    }
}

/** Prepare and verify on a separate file. Publishing is a distinct, atomic operation for local files. */
object BundleMigration {
    data class Prepared(val file: File, val identity: BundleIdentity, val sourceStamp: String)
    fun prepare(source: File, directory: File, copy: Boolean = false, cancelled: () -> Boolean = { false }): Prepared =
        prepareBundle(source,directory,copy,null,cancelled)

    /** Prepares an independent copy for a fixed creation UUID; grants no permission to publish it. */
    internal fun prepareCopy(source:File,directory:File,targetDocumentId:String,cancelled:()->Boolean={false}):Prepared {
        require(UUID.fromString(targetDocumentId).toString()==targetDocumentId) {"Invalid document UUID"}
        return prepareBundle(source,directory,true,targetDocumentId,cancelled)
    }

    private fun prepareBundle(source:File,directory:File,copy:Boolean,targetDocumentId:String?,cancelled:()->Boolean):Prepared =
        run {
            val stamp = stamp(source)
            val old = BundleHeader.read(source, cancelled).identity
            if (targetDocumentId != null) require(old?.documentId != targetDocumentId)
            val identity = if (copy) (old ?: BundleIdentity()).copy(documentId = targetDocumentId ?: UUID.randomUUID().toString())
                else old ?: BundleIdentity()
            val candidate = File.createTempFile("identity-", ".zip", directory)
            try {
                candidate.outputStream().use { rewriteBundle(source, it, identity, cancelled, validateCopy = targetDocumentId != null) }
                check(!cancelled())
                check(stamp(source) == stamp) { "Source changed during migration" }
                Prepared(candidate, identity, stamp)
            } catch (error: Throwable) { candidate.delete(); throw error }
        }
    internal fun stamp(file: File) = "${file.length()}:${file.lastModified()}"
    fun publish(prepared: Prepared, destination: File) {
        check(stamp(destination) == prepared.sourceStamp) { "Source changed before migration commit" }
        Files.move(prepared.file.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    fun fingerprints(file: File, cancelled: () -> Boolean = { false }): Map<String, String> = ZipFile(file).use { zip ->
        val result = linkedMapOf<String, String>()
        zip.entries().asSequence().forEach { entry ->
            require(entry.name !in result) { "Duplicate ZIP entry" }
            result[entry.name] = zip.getInputStream(entry).use { digest(it, cancelled) }
        }
        result
    }
    fun hash(file: File, cancelled: () -> Boolean = { false }): String = file.inputStream().use { digest(it, cancelled) }
    internal fun digest(input: InputStream, cancelled: () -> Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) {
            check(!cancelled()) { "Migration cancelled" }
            val n = input.read(buffer); if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
