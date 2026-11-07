package com.xnotes.format

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
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
    data class Prepared(val file: File, val identity: BundleIdentity, val sourceHash: String)
    fun prepare(source: File, directory: File, copy: Boolean = false, cancelled: () -> Boolean = { false }): Prepared =
        BundleSnapshot.take(source,directory,cancelled).use { snapshot ->
            val existing=BundleHeader.read(snapshot.file,cancelled).identity
            val identity=if(copy) BundleIdentity(pdfResource=existing?.pdfResource,externalPdf=existing?.externalPdf ?: false) else existing ?: BundleIdentity()
            BundleCopyPlan.prepare(snapshot,identity,directory,cancelled).use { plan ->
                val candidate=File.createTempFile("identity-",".zip",directory)
                val tail=File.createTempFile("identity-tail-",".zip",directory)
                try {
                    FileOutputStream(candidate).use {out->snapshot.file.inputStream().use {copyStream(it,out,cancelled)};out.fd.sync()}
                    if(existing==null || copy) {
                        FileOutputStream(tail).use {out->ZipOutputStream(out).use {identity.write(it)}}
                        val raw=RandomAccessFile(candidate,"rw").use {dest->FileInputStream(tail).use {extra->
                            val dir=ZipTail.read(dest.channel);val addition=ZipTail.read(extra.channel)
                            if(existing!=null || dir==null || addition==null) false else {
                                check(!cancelled())
                                check(ZipTail.splice(dest.channel,dir.offset,dir.entries,extra.channel,addition)>=0) {"Cannot append identity metadata"}
                                dest.fd.sync();true
                            }
                        }}
                        if(!raw) {
                            ZipFile(snapshot.file).use {zip->FileOutputStream(candidate).use {out->
                                ZipOutputStream(out).use {dest->
                                    dest.setLevel(1);zip.comment?.let(dest::setComment)
                                    for(entry in zip.entries().asSequence()) {
                                        check(!cancelled())
                                        if(entry.name==BundleIdentity.ENTRY) continue
                                        val replacement=entry.name==BundleCopyPlan.MANIFEST && plan.manifest!=null || entry.name==AnchorMetadata.ENTRY
                                        dest.putNextEntry(ZipEntry(entry.name).apply {
                                            time=entry.time
                                            if(!replacement && entry.method==ZipEntry.STORED) {method=ZipEntry.STORED;size=entry.size;crc=entry.crc}
                                        })
                                        when(entry.name) {
                                            AnchorMetadata.ENTRY -> dest.write(requireNotNull(plan.marker))
                                            BundleCopyPlan.MANIFEST -> (plan.manifest?.inputStream() ?: zip.getInputStream(entry)).use {copyStream(it,dest,cancelled)}
                                            else -> zip.getInputStream(entry).use {copyStream(it,dest,cancelled)}
                                        }
                                        dest.closeEntry()
                                    }
                                    identity.write(dest)
                                    dest.finish();dest.flush();out.fd.sync()
                                }
                            }}
                        }
                    }
                    plan.verify(candidate,cancelled)
                    check(hash(source,cancelled)==snapshot.sourceHash) {"Source changed during migration"}
                    Prepared(candidate,identity,snapshot.sourceHash)
                } catch(error:Throwable) {candidate.delete();throw error}
                finally {tail.delete()}
            }
        }
    fun publish(prepared: Prepared, destination: File) {
        check(hash(destination) == prepared.sourceHash) { "Source changed before migration commit" }
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
    private fun digest(input: InputStream, cancelled: () -> Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) {
            check(!cancelled()) { "Migration cancelled" }
            val n = input.read(buffer); if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun copyStream(input: InputStream, output: java.io.OutputStream, cancelled: () -> Boolean) {
        val buffer = ByteArray(65536)
        while (true) {
            check(!cancelled()) { "Migration cancelled" }
            val n = input.read(buffer); if (n < 0) break
            output.write(buffer, 0, n)
        }
    }
}
