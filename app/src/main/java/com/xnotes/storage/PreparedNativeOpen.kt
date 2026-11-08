package com.xnotes.storage

import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.model.snapshot
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.format.BundleMigration
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.platform.NativeWorkingSession
import com.xnotes.platform.WorkingSessionCache
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * Actual codec output from one verified source. Owns all temporary decoded assets until adoption.
 * Closing an adopted preparation releases only the handoff lease; its model keeps its assets.
 * This is neither a writer nor permission to rebind a cached model to a source.
 */
internal class PreparedNativeOpen private constructor(
    val version:NativeSourceVersion,val byteSize:Long,
    val note:Document?,val canvas:InfiniteDocument?,
    private val assets:File,private val resources:PdfResources,
    private val pdfIds:Set<String>,private val lease:AutoCloseable,
) : AutoCloseable {
    private var closed=false
    private var adopted=false
    private var protected=false
    private val originalExternalPdf=note?.identity?.externalPdf==true
    private var editSourceMemo:EditSource?=null

    /** One long-lived source owner per actual adoption; the temporary lease is not retained by it. */
    fun editSource():EditSource=EditSource.fromPrepared(this)

    /** IO-only, before the final non-suspending UI adoption check. No source file is written. */
    @Synchronized fun protectForAdoption() {
        check(!closed && !adopted) {"Prepared open is no longer awaiting adoption"}
        resources.protect("note:${version.documentId}",pdfIds)
        protected=true
    }
    private fun same(document:Document)=note===document && version.kind==DocumentKind.NOTE &&
        document.identity.documentId==version.documentId && document.path?.let {resources.registry.locationKey(it)}==version.locationKey
    private fun same(document:InfiniteDocument)=canvas===document && version.kind==DocumentKind.CANVAS &&
        document.identity.documentId==version.documentId && document.path?.let {resources.registry.locationKey(it)}==version.locationKey
    @Synchronized fun adopt(document:Document) {checkReady();if(!same(document)) reject();adopted=true}
    @Synchronized fun adopt(document:InfiniteDocument) {checkReady();if(!same(document)) reject();adopted=true}
    private fun checkReady() {check(!closed && protected) {"Prepared open is not ready for adoption"}}
    @Synchronized fun sourceVersion(document:Document):NativeSourceVersion {
        check(adopted) {"Source version requires actual adoption"};if(!same(document)) reject();return version
    }
    @Synchronized fun sourceVersion(document:InfiniteDocument):NativeSourceVersion {
        check(adopted) {"Source version requires actual adoption"};if(!same(document)) reject();return version
    }
    private fun reject():Nothing=throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
    override fun close() {
        val discard=synchronized(this) {if(closed) return;closed=true;!adopted}
        var failure:Throwable?=null
        if(discard) try {
            if(!assets.deleteRecursively() && assets.exists()) throw java.io.IOException("Cannot remove prepared document assets")
        } catch(error:Throwable) {failure=error}
        try {lease.close()} catch(error:Throwable) {if(failure==null) failure=error else failure.addSuppressed(error)}
        failure?.let {throw it}
    }
    /** Runtime-only baseline and request authority. Capture/accept/invalidate belong to the model owner thread. */
    internal class EditSource private constructor(
        private val note:Document?,private val canvas:InfiniteDocument?,private val resources:PdfResources,
        private val pdfIds:Set<String>,private val externalPdf:Boolean,initial:NativeSourceVersion,
    ) {
        private val issuer=Any()
        private var generation=0L
        private var active=true
        private var base=initial
        private fun same(document:Document)=note===document && base.kind==DocumentKind.NOTE &&
            document.identity.documentId==base.documentId && document.path?.let {resources.registry.locationKey(it)}==base.locationKey
        private fun same(document:InfiniteDocument)=canvas===document && base.kind==DocumentKind.CANVAS &&
            document.identity.documentId==base.documentId && document.path?.let {resources.registry.locationKey(it)}==base.locationKey
        private fun invalid():Nothing=throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        @Synchronized fun version(document:Document):NativeSourceVersion {if(!active || !same(document)) invalid();return base}
        @Synchronized fun version(document:InfiniteDocument):NativeSourceVersion {if(!active || !same(document)) invalid();return base}
        @Synchronized fun invalidate() {active=false}
        private fun authorize(epoch:Long,expected:NativeSourceVersion):Boolean=synchronized(this) {active && generation==epoch && base===expected}
        @Synchronized fun capture(document:Document):SaveRequest.Note {
            val expected=version(document);if(generation==Long.MAX_VALUE) invalid()
            val epoch=generation
            return SaveRequest.Note(issuer,epoch,expected,requireNotNull(document.path),resources,pdfIds,externalPdf,document.snapshot()) {authorize(epoch,expected)}
        }
        @Synchronized fun capture(document:InfiniteDocument):SaveRequest.Canvas {
            val expected=version(document);if(generation==Long.MAX_VALUE) invalid()
            val epoch=generation
            return SaveRequest.Canvas(issuer,epoch,expected,requireNotNull(document.path),resources,document.snapshotForWrite()) {authorize(epoch,expected)}
        }
        @Synchronized fun accept(request:SaveRequest,receipt:SaveRequest.Receipt):Boolean {
            if(!active || !request.issuedBy(issuer,generation) || request.expected!==base || !receipt.matches(request)) return false
            if(note?.let {same(it)}!=true && canvas?.let {same(it)}!=true) return false
            val next=receipt.version
            if(next.kind!=base.kind || next.documentId!=base.documentId || next.locationKey!=base.locationKey) return false
            // Byte-identical publication still consumes the epoch; no digest-based "same base" shortcut.
            generation=Math.incrementExact(generation);base=next
            return true
        }
        companion object {
            internal fun fromPrepared(prepared:PreparedNativeOpen):EditSource=synchronized(prepared) {
                val version=prepared.note?.let {prepared.sourceVersion(it)}
                    ?: prepared.sourceVersion(requireNotNull(prepared.canvas))
                prepared.editSourceMemo ?: EditSource(prepared.note,prepared.canvas,prepared.resources,
                    prepared.pdfIds.toSet(),prepared.originalExternalPdf,version).also {prepared.editSourceMemo=it}
            }
        }
    }

    /** No external candidate/receipt factory: this request encodes only its own owner-captured snapshot. */
    internal sealed class SaveRequest protected constructor(
        private val issuer:Any,private val generation:Long,internal val expected:NativeSourceVersion,
        private val uri:String,private val resources:PdfResources,private val pdfIds:Set<String>,private val authorized:()->Boolean,
    ) {
        internal fun issuedBy(owner:Any,epoch:Long)=issuer===owner && generation==epoch
        /** IO-safe source epoch check, also used by view-only cache writes; reads no live model fields. */
        internal fun isCurrentSource()=authorized()
        private fun checkCurrent(checkActive:()->Unit) {
            checkActive();if(!authorized()) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        }
        protected abstract fun validatePdf(checkActive:()->Unit)
        protected abstract fun encode(output:java.io.OutputStream,noteCodec:DocumentCodec,canvasCodec:CanvasCodec,checkActive:()->Unit)
        fun publish(storage:NotebookStorage,noteCodec:DocumentCodec,canvasCodec:CanvasCodec,tempDir:File,name:String,
                    checkActive:()->Unit={}):Receipt=Receipt.publish(this,storage,noteCodec,canvasCodec,tempDir,name,checkActive)

        class Note internal constructor(issuer:Any,generation:Long,expected:NativeSourceVersion,uri:String,resources:PdfResources,
                                        pdfIds:Set<String>,private val externalPdf:Boolean,val snapshot:Document,authorized:()->Boolean)
            :SaveRequest(issuer,generation,expected,uri,resources,pdfIds,authorized) {
            private val originalPdf=pdfIds.singleOrNull()
            override fun validatePdf(checkActive:()->Unit) {
                val file=snapshot.pdfFile
                if((file!=null)!=(originalPdf!=null)) throw ReferenceContractViolation(StorageMsg.PDF_RESOURCE_MISMATCH)
                if(file!=null && com.xnotes.format.BundleMigration.hash(file) {checkActive();false}!=originalPdf)
                    throw ReferenceContractViolation(StorageMsg.PDF_RESOURCE_MISMATCH)
            }
            override fun encode(output:java.io.OutputStream,noteCodec:DocumentCodec,canvasCodec:CanvasCodec,checkActive:()->Unit) {
                noteCodec.write(snapshot,output,embedPdf=!externalPdf,isCancelled={checkActive();false})
            }
        }
        class Canvas internal constructor(issuer:Any,generation:Long,expected:NativeSourceVersion,uri:String,resources:PdfResources,
                                          val snapshot:InfiniteDocument,authorized:()->Boolean)
            :SaveRequest(issuer,generation,expected,uri,resources,emptySet(),authorized) {
            override fun validatePdf(checkActive:()->Unit) {}
            override fun encode(output:java.io.OutputStream,noteCodec:DocumentCodec,canvasCodec:CanvasCodec,checkActive:()->Unit) {
                canvasCodec.write(snapshot,output,isCancelled={checkActive();false})
            }
        }
        class Receipt private constructor(private val request:SaveRequest,private val expected:NativeSourceVersion,val version:NativeSourceVersion) {
            internal fun matches(candidate:SaveRequest)=request===candidate && expected===candidate.expected
            companion object {
                /** IO-only. A successful storage return, rather than a read/inspect version, issues the receipt. */
                internal fun publish(request:SaveRequest,storage:NotebookStorage,noteCodec:DocumentCodec,canvasCodec:CanvasCodec,
                                     tempDir:File,name:String,checkActive:()->Unit):Receipt {
                    val check={request.checkCurrent(checkActive)}
                    check()
                    // The lease precedes PDF hashing and encoding, including when a source was just closed/deleted.
                    val lease=request.resources.holdResolved(request.pdfIds)
                    var failure:Throwable?=null;var committed=false
                    var candidate:File?=null
                    try {
                        request.validatePdf(check);check()
                        if(!tempDir.isDirectory && !tempDir.mkdirs()) throw java.io.IOException("Cannot create native save directory")
                        val file=File.createTempFile("native-request-", ".zip",tempDir);candidate=file
                        file.outputStream().use {request.encode(it,noteCodec,canvasCodec,check)}
                        check()
                        val version=storage.publishExpected(request.uri,file,request.expected,name)
                        committed=true
                        return Receipt(request,request.expected,version)
                    } catch(error:Throwable) {failure=error;throw error}
                    finally {
                        candidate?.delete()
                        try {lease.close()} catch(close:Throwable) {
                            if(failure!=null) failure.addSuppressed(close)
                            else if(committed) android.util.Log.w("xnotes.storage","Retained PDF lease after confirmed save",close)
                            else throw close
                        }
                    }
                }
            }
        }
    }

    companion object {
        private val ASSET_NAME=Regex("native-open-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        /** Only these owned directories are recursively purged at launch; other temp entries retain prior behavior. */
        internal fun isAssetDirectory(file:File)=ASSET_NAME.matches(file.name) && Files.isDirectory(file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)
        /**
         * Decode the adapter's own held body, never an externally supplied live model. The raw
         * source supplies the write baseline and PDF packaging; the cache supplies editable pages.
         */
        internal fun readWorking(session:NativeWorkingSession,read:NativeWorkingSession.Read,source:NativeSourceSnapshot,
                                 resources:PdfResources,noteCodec:DocumentCodec,canvasCodec:CanvasCodec,
                                 pdfDir:File,assetRoot:File,checkActive:()->Unit):PreparedNativeOpen {
            val check={checkActive();session.checkRecoveryRead(read)}
            check()
            val origin=read.metadata.source ?: throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
            val version=source.version
            if(version.kind!=origin.kind || version.documentId!=origin.documentId || version.locationKey!=origin.locationKey || version.sha256!=origin.sha256)
                throw ReferenceContractViolation(StorageMsg.REFERENCE_SOURCE_CHANGED)
            val body=read.body
            fun verifyBody() {
                check()
                if(!Files.isRegularFile(body.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
                    BundleMigration.hash(body) {check();false}!=read.sha256) throw WorkingSessionCache.Invalid()
            }
            verifyBody()
            val info=BundleReferenceInfo.inspect(body,true) {check();false}
            if(info.format!=version.kind.extension || info.header.identity?.documentId!=read.documentId || info.header.identity.externalPdf)
                throw WorkingSessionCache.Invalid()
            val identity=source.verifyWorkingContract(info)
            check(assetRoot.isDirectory || assetRoot.mkdirs()) {"Cannot create decoded asset directory"}
            val assets=File(assetRoot,"native-open-${UUID.randomUUID()}")
            check(assets.mkdir()) {"Cannot create working recovery directory"}
            val leases=mutableListOf<AutoCloseable>()
            fun release() {
                var failure:Throwable?=null
                for(lease in leases) try {lease.close()} catch(error:Throwable) {
                    if(failure==null) failure=error else failure.addSuppressed(error)
                }
                failure?.let {throw it}
            }
            try {
                fun input()=object:java.io.FilterInputStream(body.inputStream()) {
                    override fun read():Int {check();return super.read()}
                    override fun read(bytes:ByteArray,offset:Int,length:Int):Int {check();return `in`.read(bytes,offset,length)}
                }
                val note:Document?;val canvas:InfiniteDocument?
                when(version.kind) {
                    DocumentKind.NOTE -> {
                        note=input().use {noteCodec.read(it,pdfDir,assets,pdfStore={pdf->
                            pdf.use {stream->
                                check()
                                val expected=info.embeddedPdfHash ?: throw WorkingSessionCache.Invalid()
                                val held=resources.admitHeld(stream,"PDF",expected)
                                leases.add(held.lease);check();held.file
                            }
                        })}
                        canvas=null;source.verifyWorkingCopy(note,info)
                        // A portable cache may have normalized resource metadata. Only the source
                        // establishes ordinary-save packaging, including embedded v1 with no ID.
                        note.identity=identity;note.path=origin.uri
                        note.displayName=read.metadata.displayName;note.dirty=read.metadata.dirty
                    }
                    DocumentKind.CANVAS -> {
                        canvas=input().use {canvasCodec.read(it,assets)};note=null
                        source.verifyWorkingCopy(canvas,info)
                        canvas.identity=identity;canvas.path=origin.uri;canvas.displayName=read.metadata.displayName
                        canvas.lastView=read.metadata.canvasView;canvas.dirty=read.metadata.dirty
                    }
                    else -> throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
                }
                verifyBody();check()
                val ids=note?.pdfFile?.let {setOf(it.nameWithoutExtension)} ?: emptySet()
                // Transfer the cache's admission leases, not source.ids: embedded source has not
                // been decoded and can have an empty set. No resolve/close GC gap is introduced.
                return PreparedNativeOpen(version,source.file.length(),note,canvas,assets,resources,ids,AutoCloseable {release()})
            } catch(error:Throwable) {
                try {release()} catch(close:Throwable) {error.addSuppressed(close)}
                try {if(!assets.deleteRecursively() && assets.exists()) error.addSuppressed(java.io.IOException("Cannot remove working recovery assets"))}
                catch(close:Throwable) {error.addSuppressed(close)}
                throw error
            }
        }
        /** Borrows source; only this private decode path can issue a model/source pairing. */
        internal fun read(source:NativeSourceSnapshot,resources:PdfResources,noteCodec:DocumentCodec,canvasCodec:CanvasCodec,
                          pdfDir:File,assetRoot:File,timing:DocumentCodec.ReadTiming?,checkActive:()->Unit):PreparedNativeOpen {
            checkActive()
            check(assetRoot.isDirectory || assetRoot.mkdirs()) {"Cannot create decoded asset directory"}
            val assets=File(assetRoot,"native-open-${UUID.randomUUID()}")
            check(assets.mkdir()) {"Cannot create prepared open directory"}
            var lease:AutoCloseable?=null
            try {
                val bytes=source.file.length();val version=source.version
                val note:Document?;val canvas:InfiniteDocument?
                fun input()=object:java.io.FilterInputStream(source.file.inputStream()) {
                    override fun read():Int {checkActive();return super.read()}
                    override fun read(bytes:ByteArray,offset:Int,length:Int):Int {checkActive();return `in`.read(bytes,offset,length)}
                }
                when(version.kind) {
                    DocumentKind.NOTE -> {
                        note=input().use {noteCodec.read(it,pdfDir,assets,timing,pdfStore={source.admitOriginalPdf(it,"PDF")})}
                        canvas=null;source.verify(note)
                    }
                    DocumentKind.CANVAS -> {
                        canvas=input().use {canvasCodec.read(it,assets)};note=null;source.verify(canvas)
                    }
                    else -> throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
                }
                checkActive()
                val ids=note?.pdfFile?.let {file->
                    if(!resources.isOwned(file)) throw ReferenceContractViolation(StorageMsg.PDF_RESOURCE_MISMATCH)
                    setOf(file.nameWithoutExtension)
                } ?: emptySet()
                lease=source.retainPdfForAdoption()
                checkActive()
                return PreparedNativeOpen(version,bytes,note,canvas,assets,resources,ids,lease)
            } catch(error:Throwable) {
                try {lease?.close()} catch(close:Throwable) {error.addSuppressed(close)}
                try {if(!assets.deleteRecursively() && assets.exists()) error.addSuppressed(java.io.IOException("Cannot remove prepared document assets"))}
                catch(close:Throwable) {error.addSuppressed(close)}
                throw error
            }
        }
    }
}
