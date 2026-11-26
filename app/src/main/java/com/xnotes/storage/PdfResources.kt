package com.xnotes.storage

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** One immutable PDF object per digest; notes and chats address the same private persistent object. */
class PdfResources internal constructor(private val context: Context,val registry:DocumentRegistry) {
    internal class Unavailable(cause:Throwable?=null):IllegalStateException(StorageText.t(StorageMsg.PDF_RESOURCE_CORRUPT),cause)
    val directory = File(context.noBackupFilesDir, "ai-pdfs").apply { check(isDirectory || mkdirs()) }
    private val gate = Any()
    private val admitted = java.util.concurrent.ConcurrentHashMap<String,File>()
    fun isOwned(file: File) = file.canonicalFile.parentFile == directory.canonicalFile && file.name.matches(Regex("[a-f0-9]{64}\\.pdf"))
    fun admit(file: File, name: String): File {
        if (isOwned(file)) return resolve(file.nameWithoutExtension)
        val key = "${file.canonicalPath}:${file.length()}:${file.lastModified()}"
        admitted[key]?.takeIf { it.isFile }?.let { return it }
        return file.inputStream().use { admit(it,name) }.also { if(admitted.size>=256) admitted.clear(); admitted[key]=it }
    }
    fun admit(input: InputStream, name: String): File = synchronized(gate) {
        val temp = File.createTempFile("resource-", ".tmp",directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(temp).use { out ->
                val bytes = ByteArray(65536)
                while (true) { val n = input.read(bytes); if (n < 0) break; out.write(bytes,0,n); digest.update(bytes,0,n) }
                out.fd.sync()
            }
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            val dest = File(directory,"$id.pdf")
            if (dest.exists()) {
                check(dest.isFile && dest.length() == temp.length()) { StorageText.t(StorageMsg.PDF_RESOURCE_VERIFY_FAILED) }
            } else Files.move(temp.toPath(),dest.toPath(),StandardCopyOption.ATOMIC_MOVE)
            register(dest,name); dest
        } finally { temp.delete() }
    }
    /** Re-attach a missing or damaged original: only bytes whose SHA-256 is [id] are kept, so a different PDF never lands under a note. */
    fun restore(input: InputStream, id: String, name: String): File = synchronized(gate) {
        require(id.matches(Regex("[a-f0-9]{64}"))) { StorageText.t(StorageMsg.PDF_RESOURCE_CORRUPT) }
        val temp = File.createTempFile("resource-", ".tmp",directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(temp).use { out ->
                val bytes = ByteArray(65536)
                while (true) { val n = input.read(bytes); if (n < 0) break; out.write(bytes,0,n); digest.update(bytes,0,n) }
                out.fd.sync()
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == id) { StorageText.t(StorageMsg.PDF_RESOURCE_MISMATCH) }
            val dest = File(directory,"$id.pdf")
            Files.move(temp.toPath(),dest.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            register(dest,name); dest
        } finally { temp.delete() }
    }
    fun resolve(id: String): File = resolveChecked(id) {}

    internal fun resolveChecked(id: String, checkActive: () -> Unit): File = synchronized(gate) {
        checkActive()
        require(id.matches(Regex("[a-f0-9]{64}"))) { StorageText.t(StorageMsg.PDF_RESOURCE_CORRUPT) }
        val file = File(directory, "$id.pdf")
        if (!file.isFile || file.length() == 0L) throw Unavailable()
        if (registry.resource(id) == null) register(file, "PDF")
        file
    }
    fun register(file: File, name: String) {
        check(isOwned(file) && file.isFile)
        registry.registerResource(DocumentRegistry.Resource(file.nameWithoutExtension,name,file.absolutePath,file.length(),file.nameWithoutExtension))
    }
    fun pin(owner: String, ids: Set<String>) = synchronized(gate) {
        ids.forEach { id ->
            require(id.matches(Regex("[a-f0-9]{64}")))
            val file = File(directory,"$id.pdf")
            if (registry.resource(id) == null) {
                check(file.isFile && file.length() > 0) { StorageText.t(StorageMsg.PDF_RESOURCE_CORRUPT) }
                register(file,"附件.pdf")
            }
        }
        if (registry.referenceIds(owner) != ids) registry.references(owner,ids)
    }
    fun references(owner: String): Set<String> = registry.referenceIds(owner)
    /** Validate and acquire under the collection gate; there is no resolve-to-lease GC gap. */
    internal fun holdResolved(ids:Set<String>,checkActive:()->Unit={}):AutoCloseable = synchronized(gate) {
        checkActive();ids.forEach {resolveChecked(it,checkActive)};checkActive()
        hold(ids)
    }
    fun hold(ids: Set<String>): AutoCloseable = synchronized(gate) {
        val tokens = mutableListOf<String>()
        try {
            ids.forEach { id -> tokens.add(registry.lease(id,System.currentTimeMillis()+24L*60*60*1000)) }
        } catch(e: Exception) { tokens.forEach { registry.release(it) }; throw e }
        AutoCloseable { synchronized(gate) { tokens.forEach { registry.release(it) } } }
    }
    fun collect(): Int = synchronized(gate) { registry.collect(System.currentTimeMillis(),7L*24*60*60*1000,directory) }
    companion object {
        fun get(context:Context):PdfResources = NotebookStorage.get(context).pdfResources
    }
}
