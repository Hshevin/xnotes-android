package com.xnotes.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.xnotes.format.BundleIdentity
import com.xnotes.format.BundleMigration
import com.xnotes.format.BundleHeader
import com.xnotes.platform.AppStorageDocumentsProvider
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.ensureActive

/** On-demand, IO-only registration. Never walks storage on startup or a drawing callback. */
class NotebookStorage internal constructor(private val context:Context,
                                          private val targetOverride:((String)->MigrationPublication.Target)?=null,
                                          private val sourceValidationHook:(()->Unit)?=null) {
    val registry = DocumentRegistry(context)
    internal val pdfResources by lazy {PdfResources(context,registry)}
    private val lock = Any()
    /** Read-only: unsupported metadata layout retries from the URI start, never inventing identity. */
    fun identity(uri: String,checkActive:()->Unit={}): BundleIdentity? = synchronized(lock) {
        try {
            NativeReferenceReader.read(context.cacheDir,tryHeader={
                if(targetOverride!=null) null else {
                val descriptor=try {context.contentResolver.openFileDescriptor(Uri.parse(uri),"r")}
                    catch(_:UnsupportedOperationException) {null}
                descriptor?.use {pfd->
                    // Pipes/non-regular descriptors cannot be sought. Reopen a fresh input stream.
                    if(pfd.statSize<0) null else BundleHeader.read(FileInputStream(pfd.fileDescriptor).channel) {checkActive();false}
                }
                }
            },open={targetOverride?.invoke(uri)?.read() ?: context.contentResolver.openInputStream(Uri.parse(uri))
                ?: throw java.io.FileNotFoundException(StorageText.t(StorageMsg.NOTE_UNREADABLE))},checkActive=checkActive) {header->
                ReferenceContract.verifyObserved(registry.expectationAt(uri),
                    header.identity?.let {registry.expectationForId(it.documentId)},header)
            }.header.identity
        } catch(error:Exception) {
            when(error) {
                is ReferenceContractViolation,is java.util.concurrent.CancellationException -> throw error
                is IllegalArgumentException,is IllegalStateException ->
                    throw IllegalStateException(StorageText.t(StorageMsg.REFERENCE_METADATA_INVALID),error)
                else -> throw IllegalStateException(StorageText.t(StorageMsg.NOTE_UNREADABLE),error)
            }
        }
    }
    private fun hashKey(value:String)=java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {"%02x".format(it)}
    private fun journalCandidates(uri:String):List<File> = listOfNotNull(registry.locationKey(uri),uri,registry.uriAtLocation(uri))
        .distinct().map {File(context.noBackupFilesDir,"migrations/${hashKey(it)}")}.distinct()
    private fun journal(uri:String):MigrationPublication {
        val candidates=journalCandidates(uri);val pending=candidates.filter {MigrationPublication(it).pending}
        if(pending.size>1) throw recovery(StorageMsg.MIGRATION_RECOVERY_INVALID)
        return MigrationPublication(pending.singleOrNull() ?: candidates.first())
    }
    private fun migrationOwner(uri:String)="migration:${hashKey(registry.locationKey(uri))}"
    private fun recovery(reason:StorageMsg)=MigrationPublication.RecoveryRequired(StorageText.t(reason),reason=reason)
    private fun sourceSnapshot(backend:MigrationPublication.Target,checkActive:()->Unit={})=
        com.xnotes.format.BundleSnapshot.fromInput(backend.read(),context.cacheDir) {checkActive();false}

    /** IO-only, source bytes and their version are from the same owned container. */
    internal fun openSource(uri:String,kind:com.xnotes.core.util.DocumentKind,checkActive:()->Unit={}):NativeSourceSnapshot = synchronized(lock) {
        purpose(kind.extension) // Reject unsupported document kinds before reading.
        if(journal(uri).pending) throw recovery(StorageMsg.MIGRATION_RECOVERY_PENDING)
        checkActive()
        val snapshot=sourceSnapshot(target(uri),checkActive)
        var lease:AutoCloseable?=null
        var leasedIds:Set<String>?=null
        try {
            // A fallback can inflate a large local ZIP view before returning the header. Protect
            // only its dependency hints first; they cannot register or authorize a source identity.
            val header=BundleHeader.readWithPdfProtection(snapshot.file,protect={ids->
                check(lease==null) {"Source metadata protection was requested twice"}
                lease=pdfResources.holdResolved(ids)
                leasedIds=ids.toSet()
                sourceValidationHook?.invoke()
            }) {checkActive();false}
            ReferenceContract.verifyObserved(registry.expectationAt(uri),
                header.identity?.let {registry.expectationForId(it.documentId)},header)
            val ids=header.identity?.takeIf {it.externalPdf}?.pdfResource?.let {setOf(it)} ?: emptySet()
            if(leasedIds!=ids) throw ReferenceContractViolation(StorageMsg.REFERENCE_METADATA_INVALID)
            val inspected=NativeSourceVersion.inspect(snapshot.file,registry.locationKey(uri),kind,checkActive)
            verifyObserved(uri,inspected.info)
            val source=NativeSourceSnapshot.open(snapshot,inspected,pdfResources,requireNotNull(lease),ids,checkActive)
            lease=null // Ownership transfers only on success.
            source
        } catch(error:Throwable) {
            try {lease?.close()} catch(close:Throwable) {error.addSuppressed(close)}
            try {snapshot.close()} catch(close:Throwable) {error.addSuppressed(close)}
            if(error !is ReferenceContractViolation && error !is MigrationPublication.RecoveryRequired &&
                error !is java.util.concurrent.CancellationException && error !is PdfResources.Unavailable &&
                (error is IllegalArgumentException || error is IllegalStateException || error is java.util.zip.ZipException))
                throw ReferenceContractViolation(StorageMsg.REFERENCE_METADATA_INVALID,error)
            throw error
        }
    }
    private fun inspect(file:File):com.xnotes.format.BundleReferenceInfo = try {
        com.xnotes.format.BundleReferenceInfo.inspect(file,validatePayload=true)
    } catch(error:Exception) {
        if(error is ReferenceContractViolation || error is MigrationPublication.RecoveryRequired || error is java.util.concurrent.CancellationException) throw error
        throw ReferenceContractViolation(StorageMsg.REFERENCE_METADATA_INVALID,error)
    }
    private fun verifyObserved(uri:String,info:com.xnotes.format.BundleReferenceInfo) = ReferenceContract.verifyObserved(
        registry.expectationAt(uri),info.header.identity?.let {registry.expectationForId(it.documentId)},info.header)
    private fun dependencies(info:com.xnotes.format.BundleReferenceInfo):Set<String> = info.header.identity
        ?.takeIf {it.externalPdf}?.pdfResource?.let {setOf(it)} ?: emptySet()
    private fun protectPending(uri:String,vararg infos:com.xnotes.format.BundleReferenceInfo) =
        pdfResources.protect(migrationOwner(uri),infos.flatMap {dependencies(it)}.toSet())
    private fun protectCurrentHeader(uri:String,file:File,embeddedBackup:String?=null) {
        val header=try {BundleHeader.read(file)} catch(error:Exception) {
            if(error is java.util.concurrent.CancellationException) throw error
            return
        }
        val dependency=header.identity?.takeIf {it.externalPdf}?.pdfResource
        if(dependency!=null) try {pdfResources.protect(migrationOwner(uri),setOf(dependency))}
        catch(error:PdfResources.Unavailable) {
            if(dependency!=embeddedBackup) throw error
            // Only absent/corrupt bytes can use the verified embedded original as fallback.
            // An available current resource is always pinned, even if the current UUID is foreign.
        }
    }
    private fun purpose(kind:String):MigrationPublication.Purpose = when(kind) {
        "xnote" -> MigrationPublication.Purpose.SAVE_NOTE
        "xcanvas" -> MigrationPublication.Purpose.SAVE_CANVAS
        else -> throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
    }
    private fun verifyKind(info:com.xnotes.format.BundleReferenceInfo,expected:MigrationPublication.Purpose?) {
        if(expected!=null && purpose(info.format.orEmpty())!=expected) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
    }
    private fun registerVerified(uri:String,info:com.xnotes.format.BundleReferenceInfo,name:String?=null) {
        val identity=info.header.identity ?: run {
            // Explicit restoration of an unidentified historical original can return to legacy open.
            verifyObserved(uri,info);return
        }
        pdfResources.protect("note:${identity.documentId}",dependencies(info))
        val display=name ?: registry.nameForId(identity.documentId) ?: Uri.parse(uri).lastPathSegment.orEmpty()
        registry.register(identity.documentId,requireNotNull(info.format),display,uri,pageReferences=info.header.anchors!=null)
    }
    private fun releaseCompletedPin(uri:String) {
        // Only after successful validation + note pin/registration + journal clear, while owner holds lock.
        try {pdfResources.pin(migrationOwner(uri),emptySet())}
        catch(error:Exception) {android.util.Log.w("xnotes.storage","Retained migration resource protection after commit",error)}
    }
    private fun currentHash(backend:MigrationPublication.Target):String {
        val digest=java.security.MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
        backend.read().use {input->while(true) {val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n)}}
        return digest.digest().joinToString("") {"%02x".format(it)}
    }
    private fun publishSave(uri:String,name:String,expected:MigrationPublication.Purpose):NativeSourceVersion {
        val pending=journal(uri);val backend=target(uri)
        var verified:com.xnotes.format.BundleReferenceInfo?=null
        var receipt:NativeSourceVersion?=null
        pending.publishValidated(backend,validate={state->
            if(state.purpose!=expected) throw recovery(StorageMsg.MIGRATION_RECOVERY_PENDING)
            val original=inspect(state.original)
            val checked=NativeSourceVersion.inspect(state.candidate,registry.locationKey(uri),
                if(expected==MigrationPublication.Purpose.SAVE_NOTE) com.xnotes.core.util.DocumentKind.NOTE else com.xnotes.core.util.DocumentKind.CANVAS)
            val candidate=checked.info;receipt=checked.version
            val current=when(state.current) {state.original->original;state.candidate->candidate;else->error("Unbound publication version")}
            NativeSaveContract.verify(original,candidate,current,expected,registry.expectationAt(uri),
                current.header.identity?.let {registry.expectationForId(it.documentId)})
            protectPending(uri,original,candidate);verified=candidate
        },afterVerified={registerVerified(uri,requireNotNull(verified),name)})
        releaseCompletedPin(uri)
        return requireNotNull(receipt)
    }
    private fun target(uri:String):MigrationPublication.Target {
        targetOverride?.let {return it(uri)}
        val parsed=Uri.parse(uri)
        if(parsed.authority==context.packageName+".documents") return OwnedRecoveryTarget(context,DocumentsContract.getDocumentId(parsed))
        return object:MigrationPublication.Target {
            override fun read()=requireNotNull(context.contentResolver.openInputStream(parsed)) {StorageText.t(StorageMsg.NOTE_UNREADABLE)}
            override fun write(source:File) {
                val descriptor=requireNotNull(context.contentResolver.openFileDescriptor(parsed,"rwt")) {StorageText.t(StorageMsg.NOTE_NOT_WRITABLE)}
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use {out->
                    source.inputStream().use {it.copyTo(out)};out.flush();out.fd.sync()
                }
            }
        }
    }
    fun hasRecovery(uri:String):Boolean = synchronized(lock) {journalCandidates(uri).any {MigrationPublication(it).pending}}
    fun restore(uri:String) = synchronized(lock) {
        val pending=journal(uri);val backend=target(uri)
        var verified:com.xnotes.format.BundleReferenceInfo?=null
        pending.restoreValidated(backend,validate={state->
            val original=inspect(state.original)
            verifyKind(original,state.purpose);verifyObserved(uri,original)
            if(state.currentHash!=null) sourceSnapshot(backend).use {current->
                if(current.sourceHash!=state.currentHash) throw recovery(StorageMsg.MIGRATION_INTERRUPTED)
                protectCurrentHeader(uri,current.file,original.embeddedPdfHash)
                val header=try {BundleHeader.read(current.file)} catch(error:Exception) {
                    if(error is java.util.concurrent.CancellationException) throw error
                    null // Partial/corrupt target; the explicit restore still requires a verified original.
                }
                val currentId=header?.identity?.documentId
                if(currentId!=null && currentId!=original.header.identity?.documentId &&
                    !(original.header.identity==null && state.candidateHash==current.sourceHash && state.purpose==null))
                    throw ReferenceContractViolation(StorageMsg.REFERENCE_IDENTITY_MISMATCH)
                val readable=try {inspect(current.file)} catch(error:ReferenceContractViolation) {null}
                if(readable!=null && (readable.format!=original.format ||
                    readable.originalPdfHash!=null && readable.originalPdfHash!=original.originalPdfHash))
                    throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
            }
            protectPending(uri,original);verified=original
        },afterVerified={registerVerified(uri,requireNotNull(verified))})
        releaseCompletedPin(uri)
    }
    fun keepCurrent(uri:String) = synchronized(lock) {
        val pending=journal(uri);val backend=target(uri)
        sourceSnapshot(backend).use {snapshot->
            protectCurrentHeader(uri,snapshot.file)
            val current=inspect(snapshot.file)
            pending.keepCurrentValidated(validate={state->
                verifyKind(current,state.purpose);verifyObserved(uri,current)
                if(currentHash(backend)!=snapshot.sourceHash) throw recovery(StorageMsg.MIGRATION_INTERRUPTED)
                // With no location evidence, an explicit keep still must not reassign a known backup UUID.
                val versions=listOf(state.original to state.originalVerified,state.candidate to state.candidateVerified)
                val historical=versions.mapNotNull {(file,trusted)->
                    if(file==null || !trusted) null else try {inspect(file)} catch(error:Exception) {
                        if(error is java.util.concurrent.CancellationException) throw error
                        null
                    }
                }
                val trustedOriginal=state.original?.takeIf {state.originalVerified}?.let {file->
                    try {inspect(file)} catch(error:Exception) {
                        if(error is java.util.concurrent.CancellationException) throw error
                        null
                    }
                }
                if(trustedOriginal!=null) {
                    if(state.purpose!=null) NativeSaveContract.verify(trustedOriginal,current,current,state.purpose,
                        registry.expectationAt(uri),current.header.identity?.let {registry.expectationForId(it.documentId)})
                    else if(trustedOriginal.originalPdfHash!=current.originalPdfHash ||
                        trustedOriginal.header.anchors!=null && current.header.anchors==null)
                        throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
                }
                val knownCurrent=snapshot.sourceHash in listOfNotNull(state.originalHash,state.candidateHash)
                if(!knownCurrent && historical.isEmpty()) throw recovery(StorageMsg.MIGRATION_RECOVERY_INVALID)
                val ids=historical.mapNotNull {it.header.identity?.documentId}.toSet()
                if(!knownCurrent && ids.isNotEmpty() && current.header.identity?.documentId !in ids)
                    throw ReferenceContractViolation(StorageMsg.REFERENCE_IDENTITY_MISMATCH)
                if(!knownCurrent && historical.any {it.originalPdfHash!=current.originalPdfHash})
                    throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
                protectPending(uri,current)
            },afterVerified={registerVerified(uri,current)})
        }
        releaseCompletedPin(uri)
    }
    fun ensure(uri: String, name: String, kind: String,checkActive:()->Unit={}): BundleIdentity = synchronized(lock) {
        if(journal(uri).pending) publishSave(uri,name,purpose(kind))
        val existing = identity(uri,checkActive)
        checkActive()
        val identity = compactPdf(uri, existing ?: migrate(uri, copy = false))
        registry.register(identity.documentId, kind, name, uri)
        for(old in registry.locations(identity.documentId)) if(registry.locationKey(old)!=registry.locationKey(uri)) {
            val reachable=runCatching { context.contentResolver.openFileDescriptor(Uri.parse(old),"r")?.use { true } ?: false }.getOrDefault(false)
            if(!reachable) registry.markUnavailable(old)
        }
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
    fun publishNote(uri:String,candidate:File,kind:com.xnotes.core.util.DocumentKind,name:String) {
        synchronized(lock) {publishExisting(uri,candidate,kind,name,null)}
    }

    /** Ordinary save of a model based on an issued source version. No implicit creation or repair. */
    internal fun publishExpected(uri:String,candidate:File,expected:NativeSourceVersion,name:String):NativeSourceVersion = synchronized(lock) {
        publishExisting(uri,candidate,expected.kind,name,expected)
    }
    private fun publishExisting(uri:String,candidate:File,kind:com.xnotes.core.util.DocumentKind,name:String,
                                version:NativeSourceVersion?):NativeSourceVersion {
        val pending=journal(uri)
        if(pending.pending) throw recovery(StorageMsg.MIGRATION_RECOVERY_PENDING)
        val expected=purpose(kind.extension)
        if(version!=null && version.locationKey!=registry.locationKey(uri))
            throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        sourceSnapshot(target(uri)).use {source->
            val original=inspect(source.file);val next=inspect(candidate)
            if(version!=null) {
                if(original.header.identity?.documentId!=version.documentId)
                    throw ReferenceContractViolation(StorageMsg.REFERENCE_IDENTITY_MISMATCH)
                if(source.sourceHash!=version.sha256)
                    throw ReferenceContractViolation(StorageMsg.REFERENCE_SOURCE_CHANGED)
            }
            NativeSaveContract.verify(original,next,original,expected,registry.expectationAt(uri),
                original.header.identity?.let {registry.expectationForId(it.documentId)})
            protectPending(uri,original,next)
            pending.stage(source.file,candidate,expected)
        }
        return publishSave(uri,name,expected)
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
        identity.pdfResource?.let { id -> if(registry.resource(id)!=null) pdfResources.pin("note:${identity.documentId}",setOf(id)) }
        identity
    }
    /** Explicit user-triggered rediscovery, only inside granted workspace trees, with a bounded scan. */
    suspend fun rediscover(id: String, roots: List<String>): String? {
        require(id.isNotBlank()) { StorageText.t(StorageMsg.SPLIT_MISSING_DOCUMENT_ID) }
        val matches=linkedMapOf<String,String>()
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
