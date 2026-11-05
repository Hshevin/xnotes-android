package com.xnotes.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.xnotes.format.BundleIdentity
import com.xnotes.format.BundleMigration
import com.xnotes.format.ZipTail
import com.xnotes.platform.AppStorageDocumentsProvider
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.ensureActive

/** On-demand, IO-only registration. Never walks storage on startup or a drawing callback. */
class NotebookStorage private constructor(private val context: Context) {
    val registry = DocumentRegistry(context)
    private val lock = Any()
    fun identity(uri: String): BundleIdentity? = context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { pfd ->
        // Own only pfd, not a second close of the same file descriptor.
        ZipTail.readEntry(FileInputStream(pfd.fileDescriptor).channel, BundleIdentity.ENTRY) { BundleIdentity.read(it) }
    }
    private fun journal(uri: String): MigrationPublication {
        val id = java.security.MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }
        return MigrationPublication(File(context.noBackupFilesDir, "migrations/$id"))
    }
    private fun target(uri: String) = object : MigrationPublication.Target {
        override fun read() = requireNotNull(context.contentResolver.openInputStream(Uri.parse(uri))) { StorageText.t(StorageMsg.NOTE_UNREADABLE) }
        override fun write(source: File) {
            val descriptor = requireNotNull(context.contentResolver.openFileDescriptor(Uri.parse(uri), "rwt")) { StorageText.t(StorageMsg.NOTE_NOT_WRITABLE) }
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { out ->
                source.inputStream().use { it.copyTo(out) }
                out.flush(); out.fd.sync()
            }
        }
    }
    fun hasRecovery(uri: String): Boolean = journal(uri).pending
    fun restore(uri: String) = synchronized(lock) { journal(uri).restore(target(uri)) }
    fun keepCurrent(uri: String) = synchronized(lock) {
        // Check the complete ZIP before discarding the only known-good original.
        val temp = File.createTempFile("validate-", ".zip", context.cacheDir)
        try { target(uri).read().use { input -> temp.outputStream().use { input.copyTo(it) } }; BundleMigration.fingerprints(temp); journal(uri).keepCurrent() }
        finally { temp.delete() }
    }
    fun ensure(uri: String, name: String, kind: String): BundleIdentity = synchronized(lock) {
        if (journal(uri).pending) journal(uri).publish(target(uri))
        val existing = identity(uri)
        val identity = compactPdf(uri, existing ?: migrate(uri, copy = false))
        registry.register(identity.documentId, kind, name, uri)
        for(old in registry.locations(identity.documentId)) if(old!=uri) {
            val reachable=runCatching { context.contentResolver.openFileDescriptor(Uri.parse(old),"r")?.use { true } ?: false }.getOrDefault(false)
            if(!reachable) registry.markUnavailable(old)
        }
        identity.pdfResource?.let { id -> if(registry.resource(id)!=null) PdfResources.get(context).pin("note:${identity.documentId}",PdfResources.get(context).references("note:${identity.documentId}")+id) }
        identity
    }
    /** Pin a verified immutable original before removing its embedded copy. Never re-encode ink. */
    private fun compactPdf(uri: String, identity: BundleIdentity): BundleIdentity {
        val resources = PdfResources.get(context)
        if (identity.externalPdf) {
            val id = requireNotNull(identity.pdfResource)
            resources.resolve(id)
            resources.pin("note:${identity.documentId}", resources.references("note:${identity.documentId}") + id)
            return identity
        }
        val local = ownedFile(uri)
        val source = local ?: File.createTempFile("pdf-migrate-", ".zip", context.cacheDir)
        try {
            if (local == null) target(uri).read().use { input -> source.outputStream().use { input.copyTo(it) } }
            val pdf = java.util.zip.ZipFile(source).use { zip ->
                val entry = zip.getEntry(com.xnotes.format.BundlePdf.ENTRY) ?: return identity
                zip.getInputStream(entry).use { resources.admit(it, "PDF") }
            }
            val updated = identity.copy(pdfResource = pdf.nameWithoutExtension, externalPdf = true)
            resources.resolve(pdf.nameWithoutExtension)
            resources.pin("note:${identity.documentId}", resources.references("note:${identity.documentId}") + pdf.nameWithoutExtension)
            val staged = com.xnotes.format.BundlePdf.prepare(source, local?.parentFile ?: context.cacheDir, updated, pdf)
            try {
                if (local != null) BundleMigration.publish(staged, local)
                else { journal(uri).stage(source, staged.file); journal(uri).publish(target(uri)) }
            } finally { staged.file.delete() }
            return updated
        } finally { if (local == null) source.delete() }
    }

    /** Complete candidate first; local replacement is atomic, SAF retains a durable recovery journal. */
    fun publishNote(uri: String, candidate: File) = synchronized(lock) {
        check(!journal(uri).pending) { StorageText.t(StorageMsg.MIGRATION_INTERRUPTED) }
        val local = ownedFile(uri)
        if (local != null) {
            val sibling = File.createTempFile("save-", ".xnote", requireNotNull(local.parentFile))
            try {
                java.io.FileOutputStream(sibling).use { out -> candidate.inputStream().use { it.copyTo(out) }; out.fd.sync() }
                check(BundleMigration.hash(sibling) == BundleMigration.hash(candidate))
                java.nio.file.Files.move(sibling.toPath(), local.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { sibling.delete() }
        } else {
            val source = File.createTempFile("save-original-", ".zip", context.cacheDir)
            try {
                target(uri).read().use { input -> source.outputStream().use { input.copyTo(it) } }
                journal(uri).stage(source, candidate)
                journal(uri).publish(target(uri))
            } finally { source.delete() }
        }
    }

    private fun migrate(uri: String, copy: Boolean): BundleIdentity {
        val local = ownedFile(uri)
        if (local != null) {
            check(local.canWrite()) { StorageText.t(StorageMsg.LEGACY_NOTE_READ_ONLY) }
            val staged = BundleMigration.prepare(local, requireNotNull(local.parentFile), copy = copy)
            try { BundleMigration.publish(staged, local); return staged.identity } finally { staged.file.delete() }
        }
        // Check write permission without truncation before making recovery files.
        context.contentResolver.openFileDescriptor(Uri.parse(uri), "rw")?.use { } ?: error(StorageText.t(StorageMsg.FILE_READ_ONLY_UPGRADE))
        val source = File.createTempFile("legacy-note-", ".zip", context.cacheDir)
        try {
            target(uri).read().use { input -> java.io.FileOutputStream(source).use { out -> input.copyTo(out); out.fd.sync() } }
            val staged = BundleMigration.prepare(source, context.cacheDir, copy = copy)
            try {
                journal(uri).stage(source, staged.file)
                journal(uri).publish(target(uri))
                return staged.identity
            } finally { staged.file.delete() }
        } finally { source.delete() }
    }
    /** Call only on a newly created copy, never the original. */
    fun assignCopy(uri: String) = synchronized(lock) {
        val identity=migrate(uri, copy = true)
        val name=context.contentResolver.query(Uri.parse(uri),arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else "" }.orEmpty()
        registry.register(identity.documentId,if(name.endsWith(".xcanvas",true)) "xcanvas" else "xnote",name,uri)
        identity.pdfResource?.let { id -> if(registry.resource(id)!=null) PdfResources.get(context).pin("note:${identity.documentId}",setOf(id)) }
        identity
    }
    /** Explicit user-triggered rediscovery, only inside granted workspace trees, with a bounded scan. */
    suspend fun rediscover(id: String, roots: List<String>): String? {
        require(id.isNotBlank()) { StorageText.t(StorageMsg.SPLIT_MISSING_DOCUMENT_ID) }
        val matches=linkedMapOf<String,String>()
        var inspected=0
        for(root in roots.distinct()) {
            val tree=Uri.parse(root)
            val queue=java.util.ArrayDeque<String>()
            queue.add(DocumentsContract.getTreeDocumentId(tree))
            val visited=hashSetOf<String>()
            while(queue.isNotEmpty()) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val parent=queue.removeFirst()
                if(!visited.add(parent)) continue
                val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent)
                context.contentResolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE),null,null,null)?.use { c ->
                    while(c.moveToNext()) {
                        check(++inspected<=5000) { StorageText.t(StorageMsg.SEARCH_LIMIT_REACHED) }
                        val child=c.getString(0); val name=c.getString(1).orEmpty()
                        if(c.getString(2)==DocumentsContract.Document.MIME_TYPE_DIR) queue.add(child)
                        else if(name.endsWith(".xnote",true)||name.endsWith(".xcanvas",true)) {
                            val uri=DocumentsContract.buildDocumentUriUsingTree(tree,child).toString()
                            if(runCatching { identity(uri)?.documentId==id }.getOrDefault(false)) {
                                registry.register(id,if(name.endsWith(".xcanvas",true)) "xcanvas" else "xnote",name,uri); matches["${tree.authority}|$child"]=uri
                            }
                        }
                    }
                }
            }
        }
        check(matches.size<=1) { StorageText.t(StorageMsg.DUPLICATE_DOCUMENTS_FOUND) }
        return matches.values.singleOrNull()
    }
    fun ownedFile(uri: String): File? {
        val parsed = Uri.parse(uri)
        if (parsed.authority != context.packageName + ".documents") return null
        val id = DocumentsContract.getDocumentId(parsed)
        if (!id.startsWith("root/")) return null
        val root = AppStorageDocumentsProvider.rootDir(context).canonicalFile
        val file = File(root, id.removePrefix("root/")).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { StorageText.t(StorageMsg.INVALID_NOTE_PATH) }
        return file
    }
    companion object {
        @Volatile private var instance: NotebookStorage? = null
        fun get(context: Context): NotebookStorage = instance ?: synchronized(this) {
            instance ?: NotebookStorage(context.applicationContext).also { instance = it }
        }
    }
}
