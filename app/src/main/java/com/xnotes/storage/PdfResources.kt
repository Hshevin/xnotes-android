package com.xnotes.storage

import android.content.Context
import com.xnotes.format.BundleMigration
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** One immutable PDF object per digest; notes and chats address the same private persistent object. */
class PdfResources private constructor(private val context: Context) {
    val registry get() = NotebookStorage.get(context).registry
    val directory = File(context.noBackupFilesDir, "ai-pdfs").apply { check(isDirectory || mkdirs()) }
    private val gate = Any()
    private val admitted = java.util.concurrent.ConcurrentHashMap<String,File>()
    fun isOwned(file: File) = file.canonicalFile.parentFile == directory.canonicalFile && file.name.matches(Regex("[a-f0-9]{64}\\.pdf"))
    fun admit(file: File, name: String): File {
        if (isOwned(file)) { register(file,name); return file }
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
                check(dest.length() == temp.length() && BundleMigration.hash(dest) == id) { StorageText.t(StorageMsg.PDF_RESOURCE_VERIFY_FAILED) }
            } else Files.move(temp.toPath(),dest.toPath(),StandardCopyOption.ATOMIC_MOVE)
            register(dest,name); dest
        } finally { temp.delete() }
    }
    // Private immutable objects are hashed on first use and whenever their disk stamp changes.
    // Do not re-read a hundreds-of-MB scan on every autosave/thumbnail.
    private val verified = HashMap<String, Pair<Long, Long>>()
    fun resolve(id: String): File = synchronized(gate) {
        require(id.matches(Regex("[a-f0-9]{64}"))) { StorageText.t(StorageMsg.PDF_RESOURCE_CORRUPT) }
        val file = File(directory, "$id.pdf")
        check(file.isFile) { StorageText.t(StorageMsg.PDF_RESOURCE_CORRUPT) }
        val stamp = file.length() to file.lastModified()
        if (verified[id] != stamp) {
            check(BundleMigration.hash(file) == id) { StorageText.t(StorageMsg.PDF_RESOURCE_CORRUPT) }
            if (verified.size >= 256) verified.clear()
            verified[id] = stamp
        }
        register(file, "PDF")
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
                check(file.isFile && BundleMigration.hash(file) == id) { StorageText.t(StorageMsg.PDF_RESOURCE_CORRUPT) }
                register(file,"附件.pdf")
            }
        }
        registry.references(owner,ids)
    }
    fun references(owner: String): Set<String> = registry.referenceIds(owner)
    fun hold(ids: Set<String>): AutoCloseable = synchronized(gate) {
        val tokens = mutableListOf<String>()
        try {
            ids.forEach { id -> tokens.add(registry.lease(id,System.currentTimeMillis()+24L*60*60*1000)) }
        } catch(e: Exception) { tokens.forEach { registry.release(it) }; throw e }
        AutoCloseable { synchronized(gate) { tokens.forEach { registry.release(it) } } }
    }
    fun collect(): Int = synchronized(gate) { registry.collect(System.currentTimeMillis(),7L*24*60*60*1000,directory) }
    companion object {
        @Volatile private var instance: PdfResources? = null
        fun get(context: Context): PdfResources = instance ?: synchronized(this) { instance ?: PdfResources(context.applicationContext).also { instance = it } }
    }
}
