package com.xnotes.storage

import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.format.BundleMigration
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.format.BundleSnapshot
import com.xnotes.format.CanvasCodec
import com.xnotes.format.RecordingBodyEvidence
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID

/** A typed locator disagreed with the actual raw body, before any asset decode. */
internal class ProtectedCanvasIdentityMismatch:IllegalArgumentException("Canvas cache identity does not match the restore hint")

/** Complete private Canvas cache with assets transferred only to its actual adopted model. */
internal class PreparedProtectedCanvas private constructor(
    val document:InfiniteDocument,private val complete:CanvasCodec.CompleteRead,
    private val snapshot:BundleSnapshot,private val assets:File,
    internal val persistedDocumentId:String?,private val evidence:RecordingBodyEvidence?,
):AutoCloseable {
    private var closed=false
    private var adopted=false
    private val identity=document.identity
    /** Images/audio retain the existing shared process lifetime after model adoption. */
    @Synchronized fun adopt(actual:InfiniteDocument):AutoCloseable {
        check(!closed && !adopted && actual===document && actual.identity==identity && complete.matches(actual))
        adopted=true
        return AutoCloseable {} // Canvas has no managed PDF lease to release.
    }
    @Synchronized fun adoptedRecordingEvidence(actual:InfiniteDocument):RecordingBodyEvidence? {
        check(!closed && adopted && actual===document)
        return evidence
    }
    override fun close() {
        val discard=synchronized(this) {if(closed) return;closed=true;!adopted}
        var failure:Throwable?=null
        try {snapshot.close()} catch(error:Throwable) {failure=error}
        if(discard) try {
            if(!assets.deleteRecursively() && assets.exists()) throw java.io.IOException("Cannot remove private Canvas recovery assets")
        } catch(error:Throwable) {if(failure==null) failure=error else failure.addSuppressed(error)}
        failure?.let {throw it}
    }
    companion object {
        /** Takes the snapshot on every path, including an unsuccessful preparation. */
        fun read(snapshot:BundleSnapshot,codec:CanvasCodec,assetRoot:File,checkActive:()->Unit={}):PreparedProtectedCanvas=
            readChecked(snapshot,codec,assetRoot,null,checkActive)

        internal fun readChecked(snapshot:BundleSnapshot,codec:CanvasCodec,assetRoot:File,expectedId:String?,
                                 checkActive:()->Unit):PreparedProtectedCanvas {
            var assets:File?=null
            fun verify() {
                checkActive()
                if(!Files.isRegularFile(snapshot.file.toPath(),NOFOLLOW_LINKS) ||
                    BundleMigration.hash(snapshot.file) {checkActive();false}!=snapshot.sourceHash)
                    throw com.xnotes.platform.WorkingSessionCache.Invalid()
            }
            try {
                verify()
                val info=BundleReferenceInfo.inspectStrict(snapshot.file) {checkActive();false}
                require(info.format=="xcanvas" && info.header.anchors==null && info.originalPdfHash==null &&
                    info.header.identity?.externalPdf!=true && info.header.identity?.pdfResource==null)
                val rawId=info.header.identity?.documentId
                if(expectedId!=null && expectedId!=rawId) throw ProtectedCanvasIdentityMismatch()
                check(assetRoot.isDirectory || assetRoot.mkdirs())
                val owned=File(assetRoot,"protected-canvas-${UUID.randomUUID()}")
                check(owned.mkdir());assets=owned;checkActive()
                val input=object:java.io.FilterInputStream(snapshot.file.inputStream()) {
                    override fun read():Int {checkActive();return super.read()}
                    override fun read(bytes:ByteArray,offset:Int,length:Int):Int {checkActive();return `in`.read(bytes,offset,length)}
                }
                val complete=input.use {codec.readComplete(it,owned)}
                val document=complete.document
                if(info.header.identity?.let {it!=document.identity}==true)
                    throw com.xnotes.platform.WorkingSessionCache.Invalid()
                document.path=null
                val evidence=if(rawId?.let(com.xnotes.ui.AudioRecovery::needsDurableEvidence)==true)
                    try {RecordingBodyEvidence.inspect(snapshot.file,checkActive)}
                    catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
                    catch(_:Exception) {null} else null
                verify()
                return PreparedProtectedCanvas(document,complete,snapshot,owned,rawId,evidence)
            } catch(error:Throwable) {
                try {snapshot.close()} catch(close:Throwable) {error.addSuppressed(close)}
                assets?.let {owned->try {
                    if(!owned.deleteRecursively() && owned.exists()) throw java.io.IOException("Cannot remove private Canvas recovery assets")
                } catch(close:Throwable) {error.addSuppressed(close)}}
                throw error
            }
        }
    }
}
