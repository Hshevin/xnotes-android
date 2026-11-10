package com.xnotes.storage

import com.xnotes.core.model.Document
import com.xnotes.format.BundleSnapshot
import com.xnotes.format.BundleMigration
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.format.DocumentCodec
import com.xnotes.format.RecordingBodyEvidence
import com.xnotes.platform.WorkingSessionCache
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID

/** Complete cache body with transferable assets, without an original-source capability. */
internal class PreparedProtectedNote private constructor(
    val document:Document,private val complete:DocumentCodec.CompleteRead,
    private val snapshot:BundleSnapshot,private val assets:File,private val leases:List<AutoCloseable>,
    private val evidence:RecordingBodyEvidence?,
):AutoCloseable {
    private var closed=false
    private var adopted=false
    /** Asset lifetime is transferred once to the actual model owner, never to a UUID lookup. */
    @Synchronized fun adopt(actual:Document):AutoCloseable {
        check(!closed && !adopted && actual===document && complete.matches(actual))
        adopted=true
        return object:AutoCloseable {
            private val released=java.util.concurrent.atomic.AtomicBoolean(false)
            override fun close() {if(released.compareAndSet(false,true)) releaseAll(leases)}
        }
    }
    @Synchronized fun adoptedRecordingEvidence(actual:Document):RecordingBodyEvidence? {
        check(!closed && adopted && actual===document)
        return evidence
    }
    override fun close() {
        val discard=synchronized(this) {if(closed) return;closed=true;!adopted}
        val releases=mutableListOf<AutoCloseable>(snapshot)
        if(discard) {
            releases+=AutoCloseable {if(!assets.deleteRecursively() && assets.exists()) throw java.io.IOException("Cannot remove private recovery assets")}
            releases+=leases
        }
        releaseAll(releases)
    }
    companion object {
        private fun releaseAll(items:List<AutoCloseable>) {
            var failure:Throwable?=null
            items.forEach {try {it.close()} catch(error:Throwable) {if(failure==null) failure=error else failure!!.addSuppressed(error)}}
            failure?.let {throw it}
        }
        /** Takes the raw snapshot on every path. */
        fun read(snapshot:BundleSnapshot,resources:PdfResources,codec:DocumentCodec,pdfDir:File,assetRoot:File,
                 checkActive:()->Unit={}):PreparedProtectedNote {
            var assets:File?=null
            val leases=mutableListOf<AutoCloseable>()
            fun verify() {
                checkActive()
                if(!Files.isRegularFile(snapshot.file.toPath(),NOFOLLOW_LINKS) ||
                    BundleMigration.hash(snapshot.file) {checkActive();false}!=snapshot.sourceHash) throw WorkingSessionCache.Invalid()
            }
            try {
                verify()
                val info=BundleReferenceInfo.inspectStrict(snapshot.file) {checkActive();false}
                require(info.format=="xnote" && info.header.identity?.externalPdf!=true)
                check(assetRoot.isDirectory || assetRoot.mkdirs())
                val owned=File(assetRoot,"protected-note-${UUID.randomUUID()}")
                check(owned.mkdir());assets=owned;checkActive()
                var admittedOriginal:File?=null
                val input=object:java.io.FilterInputStream(snapshot.file.inputStream()) {
                    override fun read():Int {checkActive();return super.read()}
                    override fun read(bytes:ByteArray,offset:Int,length:Int):Int {checkActive();return `in`.read(bytes,offset,length)}
                }
                val complete=input.use {codec.readComplete(it,pdfDir,owned,pdfStore={stream->stream.use {pdf->
                    val expected=info.embeddedPdfHash ?: throw WorkingSessionCache.Invalid()
                    val held=resources.admitHeld(pdf,"PDF",expected);leases+=held.lease;admittedOriginal=held.file
                    checkActive();held.file
                }},preserveInkSamples=true)}
                val document=complete.document
                if(info.header.identity==null && document.identity.pdfResource==null && admittedOriginal!=null && document.pdfFile===admittedOriginal)
                    document.identity=document.identity.copy(pdfResource=requireNotNull(info.embeddedPdfHash))
                NativeDecodedContract.note(document,info,info.header.identity?.documentId,checkActive)
                if(info.header.anchors!=null && document.pages.map {it.referenceIdentity.id}!=info.orderedPageIds)
                    throw WorkingSessionCache.Invalid()
                document.path=null
                val evidence=if(info.header.identity?.documentId?.let(com.xnotes.ui.AudioRecovery::needsDurableEvidence)==true)
                    try {RecordingBodyEvidence.inspect(snapshot.file,checkActive)}
                    catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
                    catch(_:Exception) {null} else null
                verify()
                return PreparedProtectedNote(document,complete,snapshot,owned,leases.toList(),evidence)
            } catch(error:Throwable) {
                val cleanup=mutableListOf<AutoCloseable>(snapshot)
                assets?.let {owned->cleanup+=AutoCloseable {
                    if(!owned.deleteRecursively() && owned.exists()) throw java.io.IOException("Cannot remove private recovery assets")
                }}
                cleanup+=leases
                try {releaseAll(cleanup)} catch(close:Throwable) {error.addSuppressed(close)}
                throw error
            }
        }
    }
}
