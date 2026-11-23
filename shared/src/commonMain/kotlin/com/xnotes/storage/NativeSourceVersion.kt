package com.xnotes.storage

import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.BundleMigration
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.format.BundleSnapshot
import com.xnotes.core.pal.AssetFile
import com.xnotes.core.util.CoreLock
import com.xnotes.core.util.locked
import com.xnotes.format.isBundleZipError
import kotlin.coroutines.cancellation.CancellationException
import okio.*

interface OriginalPdfAssets {
    class Held(val file:AssetFile,val lease:AutoCloseable)
    fun admitHeld(input:Source,name:String,expectedHash:String):Held
    fun holdResolved(ids:Set<String>):AutoCloseable
}

/** Issued from actual verified bytes. No tuple constructor or copy can grant a new source version. */
class NativeSourceVersion private constructor(
    val locationKey:String,val documentId:String,val kind:DocumentKind,val sha256:String,
) {
    class Inspected private constructor(val version:NativeSourceVersion,val info:BundleReferenceInfo) {
        companion object {
            /** Keeps the issued version and its identity/PDF information inseparable. */
            fun inspect(file:AssetFile,locationKey:String,kind:DocumentKind,checkActive:()->Unit):Inspected {
                if(locationKey.isBlank() || kind !in setOf(DocumentKind.NOTE,DocumentKind.CANVAS))
                    throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
                val cancelled={checkActive();false}
                try {
                    val before=BundleMigration.hash(file,cancelled)
                    val info=BundleReferenceInfo.inspect(file,true,cancelled)
                    if(BundleMigration.hash(file,cancelled)!=before) throw ReferenceContractViolation(StorageMsg.REFERENCE_METADATA_INVALID)
                    if(info.kind!=kind) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
                    val id=info.header.identity?.documentId ?: throw ReferenceContractViolation(StorageMsg.REFERENCE_IDENTITY_MISSING)
                    checkActive()
                    return Inspected(NativeSourceVersion(locationKey,id,kind,before),info)
                } catch(error:Exception) {
                    if(error is ReferenceContractViolation || error is CancellationException) throw error
                    if(error is IllegalArgumentException || error is IllegalStateException || isBundleZipError(error))
                        throw ReferenceContractViolation(StorageMsg.REFERENCE_METADATA_INVALID,error)
                    throw error
                }
            }
        }
    }
    companion object {
        /** Caller owns this immutable file, either a source snapshot or the retained staged candidate. */
        fun inspect(file:AssetFile,locationKey:String,kind:DocumentKind,checkActive:()->Unit={}):Inspected =
            Inspected.inspect(file,locationKey,kind,checkActive)
    }
}

/**
 * Owns only a raw container and its PDF leases, never decoder-created image/audio/template assets.
 * Decode inside use. Retain a separate PDF lease before leaving use when adoption occurs later.
 */
class NativeSourceSnapshot private constructor(
    private val snapshot:BundleSnapshot,
    private val inspected:NativeSourceVersion.Inspected,
    private val resources:OriginalPdfAssets,
    initialLease:AutoCloseable,
    initialIds:Set<String>,
    private val checkActive:()->Unit,
):AutoCloseable {
    private val gate=CoreLock()
    private var closed=false
    private val leases=mutableListOf(initialLease)
    private val ids=initialIds.toMutableSet()
    val version:NativeSourceVersion get()=inspected.version
    val file:AssetFile get()=gate.locked {checkOpen();snapshot.file}
    private fun checkOpen() {check(!closed) {"Source snapshot is closed"}}

    /** A frozen observation of this captured version, not a writable source/model capability. */
    class ReferenceCatalog private constructor(val kind:DocumentKind,val documentId:String,val locationKey:String,val sha256:String,val pages:Pages) {
        sealed interface Pages {
            data object NotPaginated:Pages
            data object NeedsActivation:Pages
            class Available internal constructor(ordered:List<String>):Pages {
                val orderedIds:List<String> = buildList { addAll(ordered) }
                val ids:Set<String> = buildSet { addAll(orderedIds) }
            }
        }
        companion object {
            internal fun capture(source:NativeSourceSnapshot):ReferenceCatalog=source.gate.locked {
                source.checkOpen();source.checkActive()
                val version=source.inspected.version;val info=source.inspected.info
                val pages=when(version.kind) {
                    DocumentKind.CANVAS->Pages.NotPaginated
                    DocumentKind.NOTE->if(info.header.anchors==null) Pages.NeedsActivation else Pages.Available(info.orderedPageIds)
                    else->throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
                }
                ReferenceCatalog(version.kind,version.documentId,version.locationKey,version.sha256,pages)
            }
        }
    }
    fun referenceCatalog()=ReferenceCatalog.capture(this)

    /** Identity contract only, not a re-encoding or a bytewise comparison of model contents. */
    fun verify(document:Document) {
        gate.locked {checkOpen()};checkActive()
        if(version.kind!=DocumentKind.NOTE) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        verifyNote(document,inspected.info)
        verifyContainer()
    }
    private fun verifyNote(document:Document,info:BundleReferenceInfo) {
        NativeDecodedContract.note(document,info,version.documentId,checkActive)
    }
    fun verify(document:InfiniteDocument) {
        gate.locked {checkOpen()};checkActive()
        if(version.kind!=DocumentKind.CANVAS) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        if(document.identity.documentId!=version.documentId) throw ReferenceContractViolation(StorageMsg.REFERENCE_IDENTITY_MISMATCH)
        verifyContainer()
    }
    fun verify(read:com.xnotes.format.CanvasCodec.CompleteRead) {
        if(!read.matches(read.document)) throw ReferenceContractViolation(StorageMsg.REFERENCE_METADATA_INVALID)
        verify(read.document)
    }
    /** Data from the actual source, not a permission to bind any external model. */
    fun verifyWorkingContract(info:BundleReferenceInfo):com.xnotes.format.BundleIdentity {
        gate.locked {checkOpen()};checkActive()
        val original=inspected.info
        if(info.kind!=version.kind || info.header.identity?.documentId!=version.documentId ||
            (info.header.anchors!=null)!=(original.header.anchors!=null))
            throw ReferenceContractViolation(StorageMsg.REFERENCE_METADATA_INVALID)
        if(info.originalPdfHash!=original.originalPdfHash) throw ReferenceContractViolation(StorageMsg.PDF_RESOURCE_MISMATCH)
        verifyContainer()
        return requireNotNull(original.header.identity)
    }
    fun verifyWorkingCopy(document:Document,info:BundleReferenceInfo) {
        verifyWorkingContract(info)
        if(version.kind!=DocumentKind.NOTE) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        verifyNote(document,info)
    }
    fun verifyWorkingCopy(document:InfiniteDocument,info:BundleReferenceInfo) {
        verifyWorkingContract(info)
        if(version.kind!=DocumentKind.CANVAS || document.identity!=info.header.identity)
            throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
    }
    private fun verifyContainer() {
        if(BundleMigration.hash(file) {checkActive();false}!=version.sha256)
            throw ReferenceContractViolation(StorageMsg.REFERENCE_METADATA_INVALID)
    }

    /** Codec pdfStore callback for an embedded original; owns and closes input on every path. */
    fun admitOriginalPdf(input:Source,name:String):AssetFile = input.use {source->gate.locked {
        checkOpen();checkActive()
        val expected=inspected.info.embeddedPdfHash ?: throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        val guarded=object:ForwardingSource(source) {
            override fun read(sink:Buffer,byteCount:Long):Long {checkActive();return super.read(sink,byteCount)}
            override fun close() {}
        }
        val held=resources.admitHeld(guarded,name,expected)
        leases.add(held.lease);ids.add(expected)
        held.file
    }}

    /** Close this handoff lease only after persistent model protection/adoption, or on cancellation. */
    fun retainPdfForAdoption():AutoCloseable=gate.locked {
        checkOpen();checkActive();resources.holdResolved(ids.toSet())
    }
    override fun close() {
        val owned=gate.locked {if(closed) return;closed=true;leases.toList().also {leases.clear()}}
        var failure:Throwable?=null
        for(lease in owned) try {lease.close()} catch(error:Throwable) {
            if(failure==null) failure=error else failure.addSuppressed(error)
        }
        try {snapshot.close()} catch(error:Throwable) {if(failure==null) failure=error else failure.addSuppressed(error)}
        failure?.let {throw it}
    }
    companion object {
        /** The caller owns initialLease until this returns successfully. */
        fun open(snapshot:BundleSnapshot,inspected:NativeSourceVersion.Inspected,resources:OriginalPdfAssets,
                          initialLease:AutoCloseable,initialIds:Set<String>,checkActive:()->Unit):NativeSourceSnapshot {
            if(snapshot.sourceHash!=inspected.version.sha256) throw ReferenceContractViolation(StorageMsg.REFERENCE_METADATA_INVALID)
            val ids=inspected.info.header.identity?.takeIf {it.externalPdf}?.pdfResource?.let {setOf(it)} ?: emptySet()
            if(ids!=initialIds) throw ReferenceContractViolation(StorageMsg.REFERENCE_METADATA_INVALID)
            checkActive()
            return NativeSourceSnapshot(snapshot,inspected,resources,initialLease,ids,checkActive)
        }
    }
}
fun NativeReferenceTarget.verify(catalog:NativeSourceSnapshot.ReferenceCatalog) =
    verify(catalog.kind,catalog.documentId,(catalog.pages as? NativeSourceSnapshot.ReferenceCatalog.Pages.Available)?.ids)
