package com.xnotes.storage

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.xnotes.format.BundleIdentity
import com.xnotes.format.BundleMigration
import com.xnotes.format.BundleHeader
import com.xnotes.platform.AppStorageDocumentsProvider
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.ensureActive

/** On-demand, IO-only registration. Never walks storage on startup or a drawing callback. */
class NotebookStorage internal constructor(private val context:Context,
                                          private val targetOverride:((String)->Target)?=null) {
    internal interface Target {
        fun read(): java.io.InputStream
        fun write(source: File)
    }
    val registry = DocumentRegistry(context)
    internal val pdfResources by lazy {PdfResources(context,registry)}
    private val lock = Any()
    private data class Metadata(val identity: BundleIdentity?, val anchored: Boolean, val embeddedPdf: Boolean)
    private fun localSource(uri: String): File? {
        val parsed = Uri.parse(uri)
        return if (parsed.scheme == "file") parsed.path?.let(::File) else ownedFile(uri)
    }

    /** Reading identity alone may stop at the first marker on a pipe. */
    fun identity(uri: String, checkActive: () -> Unit = {}): BundleIdentity? =
        metadata(uri, true, checkActive).identity

    private fun metadata(uri: String, identityOnly: Boolean, checkActive: () -> Unit): Metadata {
        checkActive()
        fun directory(channel: java.nio.channels.FileChannel): Metadata? {
            val dir = com.xnotes.format.ZipTail.read(channel, allowComment = true) ?: return null
            val entry = dir.entries.singleOrNull { it.name == BundleIdentity.ENTRY }
            require(entry != null || dir.entries.none { it.name == BundleIdentity.ENTRY })
            val identity = entry?.let { requireNotNull(com.xnotes.format.ZipTail.readEntry(channel, it, BundleIdentity::read)) }
            checkActive()
            return Metadata(identity, dir.entries.any { it.name == com.xnotes.format.AnchorMetadata.ENTRY },
                dir.entries.any { it.name == com.xnotes.format.BundlePdf.ENTRY })
        }
        fun standardDirectory(file: File) = ZipFile(file).use { zip ->
            val identity = zip.getEntry(BundleIdentity.ENTRY)?.let { zip.getInputStream(it).use(BundleIdentity::read) }
            Metadata(identity, zip.getEntry(com.xnotes.format.AnchorMetadata.ENTRY) != null,
                zip.getEntry(com.xnotes.format.BundlePdf.ENTRY) != null)
        }
        if (targetOverride == null) {
            localSource(uri)?.let { file ->
                file.inputStream().use { directory(it.channel)?.let { info -> return info } }
                return standardDirectory(file)
            }
            val descriptor = try { context.contentResolver.openFileDescriptor(Uri.parse(uri), "r") }
                catch (_: UnsupportedOperationException) { null }
            if (descriptor != null) ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                if (descriptor.statSize >= 0) return directory(input.channel) ?: try {
                    standardDirectory(descriptor.zipFilePath())
                } catch (_: SecurityException) {
                    input.channel.position(0)
                    sequentialMetadata(input, identityOnly, checkActive)
                } catch (_: java.io.FileNotFoundException) {
                    input.channel.position(0)
                    sequentialMetadata(input, identityOnly, checkActive)
                }
                return sequentialMetadata(input, identityOnly, checkActive)
            }
        }
        return sequentialMetadata(openInput(uri), identityOnly, checkActive)
    }

    private fun sequentialMetadata(input: java.io.InputStream, identityOnly: Boolean, checkActive: () -> Unit): Metadata {
        var id: BundleIdentity? = null
        var anchored = false
        var pdf = false
        java.util.zip.ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                checkActive()
                when ((zip.nextEntry ?: break).name) {
                    BundleIdentity.ENTRY -> {
                        require(id == null)
                        id = BundleIdentity.read(zip)
                        if (identityOnly) break
                    }
                    com.xnotes.format.AnchorMetadata.ENTRY -> anchored = true
                    com.xnotes.format.BundlePdf.ENTRY -> pdf = true
                }
            }
        }
        return Metadata(id, anchored, pdf)
    }
    /** Native bundle publication and recovery must never replace an original PDF. */
    private fun checkBundleName(name: String?) {
        if (name?.endsWith(".pdf", ignoreCase = true) == true)
            error(StorageText.t(StorageMsg.NATIVE_CREATION_TARGET_INVALID))
    }

    private fun checkBundleDestination(uri: Uri) {
        checkBundleName(uri.lastPathSegment)
        if (uri.scheme != "content" || uri.authority == context.packageName + ".documents") return
        val resolver = context.contentResolver
        if (resolver.getType(uri).equals("application/pdf", ignoreCase = true))
            error(StorageText.t(StorageMsg.NATIVE_CREATION_TARGET_INVALID))
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) checkBundleName(it.getString(0))
        }
    }

    fun ensure(uri: String, name: String, kind: String,checkActive:()->Unit={}): BundleIdentity = synchronized(lock) {
        checkActive()
        val info = metadata(uri, false, checkActive)
        val existing = info.identity
        registry.expectationAt(uri)?.let { if (it.documentId != existing?.documentId) throw ReferenceContractViolation(StorageMsg.REFERENCE_IDENTITY_MISMATCH) }
        if (existing != null && !info.anchored && registry.expectationForId(existing.documentId).requiresPages)
            throw ReferenceContractViolation(StorageMsg.REFERENCE_PAGE_METADATA_MISSING)
        checkActive()
        val identified = existing ?: migrate(uri, copy = false)
        val identity = if (kind == "xnote" && (identified.externalPdf || info.embeddedPdf)) compactPdf(uri, identified) else identified
        registry.register(identity.documentId, kind, name, uri, info.anchored)
        // A failed descriptor open cannot distinguish a second copy from a provider alias.
        // Only explicit removal/relocation retires an unverified location.
        identity.pdfResource?.let { id -> if(registry.resource(id)!=null) pdfResources.pin("note:${identity.documentId}",pdfResources.references("note:${identity.documentId}")+id) }
        identity
    }
    /** Pin a verified immutable original before removing its embedded copy. Never re-encode ink. */
    private fun compactPdf(uri: String, identity: BundleIdentity): BundleIdentity {
        val resources = pdfResources
        if (identity.externalPdf) {
            val id = requireNotNull(identity.pdfResource)
            resources.resolve(id)
            resources.pin("note:${identity.documentId}", resources.references("note:${identity.documentId}") + id)
            return identity
        }
        val local = ownedFile(uri)
        val source = local ?: File.createTempFile("pdf-migrate-", ".zip", context.cacheDir)
        try {
            if (local == null) openInput(uri).use { input -> source.outputStream().use { input.copyTo(it) } }
            val stamp = BundleMigration.stamp(source)
            val pdf = java.util.zip.ZipFile(source).use { zip ->
                val entry = zip.getEntry(com.xnotes.format.BundlePdf.ENTRY) ?: return identity
                zip.getInputStream(entry).use { resources.admit(it, "PDF") }
            }
            val updated = identity.copy(pdfResource = pdf.nameWithoutExtension, externalPdf = true)
            resources.resolve(pdf.nameWithoutExtension)
            resources.pin("note:${identity.documentId}", resources.references("note:${identity.documentId}") + pdf.nameWithoutExtension)
            val staged = com.xnotes.format.BundlePdf.prepare(source, local?.parentFile ?: context.cacheDir, updated, pdf)
            try {
                check(BundleMigration.stamp(source) == stamp)
                writeBundle(uri, staged.file)
            } finally { staged.file.delete() }
            return updated
        } finally { if (local == null) source.delete() }
    }

    internal fun writeNewBundle(uri: String, source: File, id: String, kind: String, name: String, checkActive: () -> Unit = {}) = synchronized(lock) {
        require(openInput(uri).use { it.read() == -1 }) { StorageText.t(StorageMsg.NATIVE_CREATION_TARGET_INVALID) }
        checkActive()
        val anchored = BundleHeader.read(source).anchors != null
        writeBundle(uri, source)
        registry.register(id,kind,name,uri,anchored)
    }

    internal fun openInput(uri: String): java.io.InputStream = targetOverride?.invoke(uri)?.read()
        ?: ownedFile(uri)?.inputStream() ?: requireNotNull(context.contentResolver.openInputStream(Uri.parse(uri)))

    internal fun enablePageIds(uri: String, documentId: String, pageCount: Int, checkActive: () -> Unit) = synchronized(lock) {
        val local = ownedFile(uri)
        val source = local ?: File.createTempFile("page-source-", ".xnote", context.cacheDir)
        try {
            if (local == null) openInput(uri).use { input -> source.outputStream().use { input.copyTo(it) } }
            require(BundleHeader.read(source).identity?.documentId == documentId)
            val prepared = com.xnotes.format.BundleAnchors.prepare(source, local?.parentFile ?: context.cacheDir) {
                checkActive()
                false
            }
            try {
                checkActive()
                require(prepared.anchorCount == pageCount)
                writeBundle(uri, prepared.file)
            } finally { prepared.file.delete() }
        } finally { if (local == null) source.delete() }
    }

    /** Consume the completed local candidate or stream it through the provider exactly once. */
    fun writeBundle(uri: String, source: File) {
        val local = ownedFile(uri)
        if (local == null || targetOverride != null) {
            targetOverride?.let { it(uri).write(source); return }
            val parsed = Uri.parse(uri)
            checkBundleDestination(parsed)
            val descriptor = requireNotNull(context.contentResolver.openFileDescriptor(parsed, "rwt"))
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                source.inputStream().use { it.copyTo(output) }
                output.flush()
                output.fd.sync()
            }
        } else {
            fun replace(candidate: File) {
                java.nio.file.Files.move(candidate.toPath(), local.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            try {
                replace(source)
            } catch (error: java.nio.file.AtomicMoveNotSupportedException) {
                // The cache may be internal while the application's notes directory is app-external.
                if (source.parentFile == local.parentFile) throw error
                val sibling = File.createTempFile(".xnotes-save-", ".tmp", local.parentFile)
                try {
                    source.inputStream().use { input -> sibling.outputStream().use { input.copyTo(it) } }
                    replace(sibling)
                } finally { sibling.delete() }
            }
        }
    }

    private fun migrate(uri: String, copy: Boolean): BundleIdentity {
        val local = ownedFile(uri)
        if (local != null) {
            check(local.canWrite()) { StorageText.t(StorageMsg.LEGACY_NOTE_READ_ONLY) }
            val staged = BundleMigration.prepare(local, requireNotNull(local.parentFile), copy = copy)
            try { writeBundle(uri, staged.file); return staged.identity } finally { staged.file.delete() }
        }
        // Check write permission before staging the legacy bundle.
        context.contentResolver.openFileDescriptor(Uri.parse(uri), "rw")?.use { } ?: error(StorageText.t(StorageMsg.FILE_READ_ONLY_UPGRADE))
        val source = File.createTempFile("legacy-note-", ".zip", context.cacheDir)
        try {
            openInput(uri).use { input -> source.outputStream().use { input.copyTo(it) } }
            val staged = BundleMigration.prepare(source, context.cacheDir, copy = copy)
            try {
                writeBundle(uri, staged.file)
                return staged.identity
            } finally { staged.file.delete() }
        } finally { source.delete() }
    }
    /** Call only on a newly created copy, never the original. */
    fun assignCopy(uri: String) = synchronized(lock) {
        val identity=migrate(uri, copy = true)
        val name=context.contentResolver.query(Uri.parse(uri),arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else "" }.orEmpty()
        registry.register(identity.documentId,if(name.endsWith(".xcanvas",true)) "xcanvas" else "xnote",name,uri)
        identity.pdfResource?.let { id -> if(registry.resource(id)!=null) pdfResources.pin("note:${identity.documentId}",setOf(id)) }
        identity
    }
    /** Explicit user-triggered rediscovery, only inside granted workspace trees, with a bounded scan. */
    suspend fun rediscover(id: String, roots: List<String>): String? {
        require(id.isNotBlank()) { StorageText.t(StorageMsg.SPLIT_MISSING_DOCUMENT_ID) }
        val matches=linkedSetOf<String>()
        val coroutine=kotlinx.coroutines.currentCoroutineContext()
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
                            val matchesId=try {identity(uri) {coroutine.ensureActive()}?.documentId==id}
                                catch(error:java.util.concurrent.CancellationException) {throw error}
                                catch(_:Exception) {false}
                            if(matchesId) {
                                registry.register(id,if(name.endsWith(".xcanvas",true)) "xcanvas" else "xnote",name,uri)
                                matches.add(uri)
                            }
                        }
                    }
                }
            }
        }
        val distinct = registry.distinctLocations(matches)
        check(distinct.size<=1) { StorageText.t(StorageMsg.DUPLICATE_DOCUMENTS_FOUND) }
        return distinct.singleOrNull()
    }
    fun ownedFile(uri: String): File? {
        val parsed = Uri.parse(uri)
        if (parsed.authority != context.packageName + ".documents") return null
        val id = DocumentsContract.getDocumentId(parsed)
        if (!id.startsWith("root/")) return null
        val root = AppStorageDocumentsProvider.rootDir(context).canonicalFile
        val file = File(root, id.removePrefix("root/")).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { StorageText.t(StorageMsg.INVALID_NOTE_PATH) }
        checkBundleName(file.name)
        return file
    }
    companion object {
        @Volatile private var instance: NotebookStorage? = null
        fun get(context: Context): NotebookStorage = instance ?: synchronized(this) {
            instance ?: NotebookStorage(context.applicationContext).also { instance = it }
        }
    }
}

/** ZipFile needs a path to use its ZIP64 central directory reader. Keep the descriptor open. */
internal fun ParcelFileDescriptor.zipFilePath(): File =
    File(if (File("/proc/self/fd").isDirectory) "/proc/self/fd" else "/dev/fd", fd.toString())
