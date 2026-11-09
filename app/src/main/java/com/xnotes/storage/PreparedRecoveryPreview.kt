package com.xnotes.storage

import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.BundleMigration
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.format.BundleSnapshot
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.platform.NativeWorkingSession
import com.xnotes.platform.WorkingSessionCache
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID

/** Read-only historical body, not a source binding. Owns its raw body and all decoded resources. */
internal class PreparedRecoveryPreview private constructor(
    val kind:DocumentKind,val displayName:String?,val byteSize:Long,
    internal val note:Document?,internal val canvas:InfiniteDocument?,
    private val body:File,private val bodyHash:String,private val assets:File,
    private val pdfLeases:List<AutoCloseable>,private val rawLease:AutoCloseable,
) : AutoCloseable {
    @Volatile private var closed=false
    val pageCount get()=if(kind==DocumentKind.NOTE) requireNotNull(note).pages.size else 1
    private fun checkOpen() {check(!closed) {"Recovery preview is closed"}}
    internal fun ensureOpen()=checkOpen()

    /** The displayed model is deliberately not encoded: unknown original payload must survive. */
    fun prepareCopy(targetDocumentId:String,directory:File,checkActive:()->Unit={}):BundleMigration.Prepared {
        val check={checkOpen();checkActive()}
        check()
        if(BundleMigration.hash(body) {check();false}!=bodyHash) throw WorkingSessionCache.Invalid()
        val copy=BundleMigration.prepareCopy(body,directory,targetDocumentId) {check();false}
        try {
            check()
            if(copy.sourceHash!=bodyHash) throw WorkingSessionCache.Invalid()
            return copy
        } catch(error:Throwable) {copy.file.delete();throw error}
    }
    override fun close() {
        synchronized(this) {if(closed) return;closed=true}
        var failure:Throwable?=null
        fun release(action:()->Unit) {try {action()} catch(error:Throwable) {if(failure==null) failure=error else failure!!.addSuppressed(error)}}
        release {if(!assets.deleteRecursively() && assets.exists()) throw java.io.IOException("Cannot remove recovery preview assets")}
        pdfLeases.forEach {release {it.close()}}
        release {rawLease.close()}
        failure?.let {throw it}
    }
    companion object {
        /** Takes ownership on every path, including cancellation before decoding. */
        fun readLegacy(snapshot:BundleSnapshot,kind:DocumentKind,resources:PdfResources,
                       noteCodec:DocumentCodec,canvasCodec:CanvasCodec,pdfDir:File,assetRoot:File,
                       displayName:String?=null,checkActive:()->Unit={}):PreparedRecoveryPreview =
            decode(snapshot.file,snapshot.sourceHash,kind,null,displayName,false,null,resources,noteCodec,canvasCodec,
                pdfDir,assetRoot,snapshot,checkActive)

        /** Takes only this adapter's actual held body. It never opens or matches an original URI. */
        fun readWorking(session:NativeWorkingSession,read:NativeWorkingSession.Read,resources:PdfResources,
                        noteCodec:DocumentCodec,canvasCodec:CanvasCodec,pdfDir:File,assetRoot:File,
                        checkActive:()->Unit):PreparedRecoveryPreview {
            try {
                session.checkRecoveryRead(read)
                if(read.metadata.source!=null || read.metadata.handoff!=null)
                    throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
                val kind=if(read.kind==WorkingSessionCache.Kind.NOTE) DocumentKind.NOTE else DocumentKind.CANVAS
                return decode(read.body,read.sha256,kind,read.documentId,read.metadata.displayName,read.metadata.dirty,
                    read.metadata,resources,noteCodec,canvasCodec,pdfDir,assetRoot,read) {checkActive();session.checkRecoveryRead(read)}
            } catch(error:Throwable) {try {read.close()} catch(close:Throwable) {error.addSuppressed(close)};throw error}
        }

        private fun decode(body:File,hash:String,kind:DocumentKind,expectedId:String?,name:String?,dirty:Boolean,
                           metadata:NativeWorkingSession.Metadata?,resources:PdfResources,noteCodec:DocumentCodec,
                           canvasCodec:CanvasCodec,pdfDir:File,assetRoot:File,rawLease:AutoCloseable,
                           checkActive:()->Unit):PreparedRecoveryPreview {
            var assets:File?=null
            val leases=mutableListOf<AutoCloseable>()
            fun verifyBody() {
                checkActive()
                if(!Files.isRegularFile(body.toPath(),NOFOLLOW_LINKS) || BundleMigration.hash(body) {checkActive();false}!=hash)
                    throw WorkingSessionCache.Invalid()
            }
            try {
                verifyBody()
                val info=BundleReferenceInfo.inspectStrict(body) {checkActive();false}
                if(kind !in setOf(DocumentKind.NOTE,DocumentKind.CANVAS) || info.format!=kind.extension || info.header.identity?.externalPdf==true)
                    throw WorkingSessionCache.Invalid()
                if(expectedId!=null && info.header.identity?.documentId!=expectedId) throw WorkingSessionCache.Invalid()
                check(assetRoot.isDirectory || assetRoot.mkdirs()) {"Cannot create recovery preview root"}
                val owned=File(assetRoot,"native-open-${UUID.randomUUID()}");check(owned.mkdir()) {"Cannot create recovery preview assets"};assets=owned
                fun input()=object:java.io.FilterInputStream(body.inputStream()) {
                    override fun read():Int {checkActive();return super.read()}
                    override fun read(bytes:ByteArray,offset:Int,length:Int):Int {checkActive();return `in`.read(bytes,offset,length)}
                }
                val note:Document?;val canvas:InfiniteDocument?
                when(kind) {
                    DocumentKind.NOTE->{
                        var admittedOriginal:File?=null
                        note=input().use {noteCodec.read(it,pdfDir,owned,pdfStore={input->input.use {stream->
                            val expected=info.embeddedPdfHash ?: throw WorkingSessionCache.Invalid()
                            val held=resources.admitHeld(stream,"PDF",expected);leases.add(held.lease)
                            admittedOriginal=held.file
                            checkActive();held.file
                        }})};canvas=null
                        // A genuinely old body has no persisted resource field. Bind only the
                        // exact original admitted above against this body's verified PDF hash.
                        if(info.header.identity==null && note.identity.pdfResource==null && admittedOriginal!=null &&
                            note.pdfFile===admittedOriginal) {
                            note.identity=note.identity.copy(pdfResource=requireNotNull(info.embeddedPdfHash))
                        }
                        NativeDecodedContract.note(note,info,expectedId ?: info.header.identity?.documentId,checkActive)
                        if(info.header.anchors!=null && note.pages.map {it.referenceIdentity.id}!=info.orderedPageIds)
                            throw WorkingSessionCache.Invalid()
                        note.path=null;note.displayName=name;note.dirty=dirty
                    }
                    DocumentKind.CANVAS->{
                        canvas=input().use {canvasCodec.read(it,owned)};note=null
                        if(info.header.anchors!=null || info.header.identity?.let {it!=canvas.identity}==true) throw WorkingSessionCache.Invalid()
                        canvas.path=null;canvas.displayName=name;canvas.dirty=dirty
                        if(metadata!=null) canvas.lastView=metadata.canvasView
                    }
                    else->throw WorkingSessionCache.Invalid()
                }
                verifyBody();checkActive()
                return PreparedRecoveryPreview(kind,name,body.length(),note,canvas,body,hash,owned,leases.toList(),rawLease)
            } catch(error:Throwable) {
                assets?.let {try {if(!it.deleteRecursively() && it.exists()) throw java.io.IOException("Cannot remove recovery preview assets")}
                    catch(close:Throwable) {error.addSuppressed(close)}}
                leases.forEach {try {it.close()} catch(close:Throwable) {error.addSuppressed(close)}}
                try {rawLease.close()} catch(close:Throwable) {error.addSuppressed(close)}
                throw error
            }
        }
    }
}
