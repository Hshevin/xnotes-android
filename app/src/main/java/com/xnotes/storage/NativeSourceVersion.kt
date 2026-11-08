package com.xnotes.storage

import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.BundleMigration
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.format.BundleSnapshot
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/** Issued from actual verified bytes. No tuple constructor or copy can grant a new source version. */
internal class NativeSourceVersion private constructor(
    val locationKey:String,val documentId:String,val kind:DocumentKind,val sha256:String,
) {
    internal class Inspected private constructor(val version:NativeSourceVersion,val info:BundleReferenceInfo) {
        companion object {
            /** Keeps the issued version and its identity/PDF information inseparable. */
            internal fun inspect(file:File,locationKey:String,kind:DocumentKind,checkActive:()->Unit):Inspected {
                if(locationKey.isBlank() || kind !in setOf(DocumentKind.NOTE,DocumentKind.CANVAS))
                    throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
                val cancelled={checkActive();false}
                try {
                    val before=BundleMigration.hash(file,cancelled)
                    val info=BundleReferenceInfo.inspect(file,true,cancelled)
                    if(BundleMigration.hash(file,cancelled)!=before) throw ReferenceContractViolation(StorageMsg.REFERENCE_METADATA_INVALID)
                    if(info.format!=kind.extension) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
                    val id=info.header.identity?.documentId ?: throw ReferenceContractViolation(StorageMsg.REFERENCE_IDENTITY_MISSING)
                    checkActive()
                    return Inspected(NativeSourceVersion(locationKey,id,kind,before),info)
                } catch(error:Exception) {
                    if(error is ReferenceContractViolation || error is java.util.concurrent.CancellationException) throw error
                    if(error is IllegalArgumentException || error is IllegalStateException || error is java.util.zip.ZipException)
                        throw ReferenceContractViolation(StorageMsg.REFERENCE_METADATA_INVALID,error)
                    throw error
                }
            }
        }
    }
    companion object {
        /** Caller owns this immutable file, either a source snapshot or the retained staged candidate. */
        internal fun inspect(file:File,locationKey:String,kind:DocumentKind,checkActive:()->Unit={}):Inspected =
            Inspected.inspect(file,locationKey,kind,checkActive)
    }
}

/**
 * Owns only a raw container and its PDF leases, never decoder-created image/audio/template assets.
 * Decode inside use. Retain a separate PDF lease before leaving use when adoption occurs later.
 */
internal class NativeSourceSnapshot private constructor(
    private val snapshot:BundleSnapshot,
    private val inspected:NativeSourceVersion.Inspected,
    private val resources:PdfResources,
    initialLease:AutoCloseable,
    initialIds:Set<String>,
    private val checkActive:()->Unit,
):AutoCloseable {
    private val gate=Any()
    private var closed=false
    private val leases=mutableListOf(initialLease)
    private val ids=initialIds.toMutableSet()
    val version:NativeSourceVersion get()=inspected.version
    val file:File get()=synchronized(gate) {checkOpen();snapshot.file}
    private fun checkOpen() {check(!closed) {"Source snapshot is closed"}}

    /** Identity contract only, not a re-encoding or a bytewise comparison of model contents. */
    fun verify(document:Document) {
        synchronized(gate) {checkOpen()};checkActive()
        if(version.kind!=DocumentKind.NOTE) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        verifyNote(document,inspected.info)
        verifyContainer()
    }
    private fun verifyNote(document:Document,info:BundleReferenceInfo) {
        if(document.identity.documentId!=version.documentId) throw ReferenceContractViolation(StorageMsg.REFERENCE_IDENTITY_MISMATCH)
        val anchored=info.header.anchors!=null
        if(document.hasReferenceAnchors!=anchored) throw ReferenceContractViolation(StorageMsg.REFERENCE_METADATA_INVALID)
        val actual=document.pages.map {checkActive();it.referenceIdentity.id}
        if(anchored) {
            if(actual.size!=info.pageIds.size || actual.toSet()!=info.pageIds)
                throw ReferenceContractViolation(StorageMsg.REFERENCE_METADATA_INVALID)
        } else if(actual.any {it!=null}) throw ReferenceContractViolation(StorageMsg.REFERENCE_METADATA_INVALID)
        verifyOriginalPdf(document,info)
    }
    fun verify(document:InfiniteDocument) {
        synchronized(gate) {checkOpen()};checkActive()
        if(version.kind!=DocumentKind.CANVAS) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        if(document.identity.documentId!=version.documentId) throw ReferenceContractViolation(StorageMsg.REFERENCE_IDENTITY_MISMATCH)
        verifyContainer()
    }
    /** Data from the actual source, not a permission to bind any external model. */
    internal fun verifyWorkingContract(info:BundleReferenceInfo):com.xnotes.format.BundleIdentity {
        synchronized(gate) {checkOpen()};checkActive()
        val original=inspected.info
        if(info.format!=version.kind.extension || info.header.identity?.documentId!=version.documentId ||
            (info.header.anchors!=null)!=(original.header.anchors!=null))
            throw ReferenceContractViolation(StorageMsg.REFERENCE_METADATA_INVALID)
        if(info.originalPdfHash!=original.originalPdfHash) throw ReferenceContractViolation(StorageMsg.PDF_RESOURCE_MISMATCH)
        verifyContainer()
        return requireNotNull(original.header.identity)
    }
    internal fun verifyWorkingCopy(document:Document,info:BundleReferenceInfo) {
        verifyWorkingContract(info)
        if(version.kind!=DocumentKind.NOTE) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        verifyNote(document,info)
    }
    internal fun verifyWorkingCopy(document:InfiniteDocument,info:BundleReferenceInfo) {
        verifyWorkingContract(info)
        if(version.kind!=DocumentKind.CANVAS || document.identity!=info.header.identity)
            throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
    }
    private fun verifyOriginalPdf(document:Document,info:BundleReferenceInfo) {
        // v1 may carry an unused stale resource field; only the verified manifest/PDF contract
        // determines whether an original exists and what its bytes must be.
        val expected=info.originalPdfHash
        if(document.identity!=info.header.identity && (expected==null || document.identity.pdfResource!=expected))
            throw ReferenceContractViolation(StorageMsg.PDF_RESOURCE_MISMATCH)
        val original=document.pdfFile
        if(expected==null) {
            if(original!=null) throw ReferenceContractViolation(StorageMsg.PDF_RESOURCE_MISMATCH)
            return
        }
        if(original==null) throw ReferenceContractViolation(StorageMsg.REFERENCE_METADATA_INVALID)
        val actual=try {BundleMigration.hash(original) {checkActive();false}}
            catch(error:java.io.IOException) {throw ReferenceContractViolation(StorageMsg.REFERENCE_METADATA_INVALID,error)}
        if(actual!=expected) throw ReferenceContractViolation(StorageMsg.PDF_RESOURCE_MISMATCH)
    }
    private fun verifyContainer() {
        if(BundleMigration.hash(file) {checkActive();false}!=version.sha256)
            throw ReferenceContractViolation(StorageMsg.REFERENCE_METADATA_INVALID)
    }

    /** Codec pdfStore callback for an embedded original; owns and closes input on every path. */
    fun admitOriginalPdf(input:InputStream,name:String):File = input.use {source->synchronized(gate) {
        checkOpen();checkActive()
        val expected=inspected.info.embeddedPdfHash ?: throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        val guarded=object:FilterInputStream(source) {
            override fun read():Int {checkActive();return super.read()}
            override fun read(bytes:ByteArray,offset:Int,length:Int):Int {checkActive();return super.read(bytes,offset,length)}
            override fun close() {} // The outer use owns this stream, including rejection paths.
        }
        val held=resources.admitHeld(guarded,name,expected)
        leases.add(held.lease);ids.add(expected)
        held.file
    }}

    /** Close this handoff lease only after persistent model protection/adoption, or on cancellation. */
    fun retainPdfForAdoption():AutoCloseable=synchronized(gate) {
        checkOpen();checkActive();resources.holdResolved(ids.toSet())
    }
    override fun close() {
        val owned=synchronized(gate) {if(closed) return;closed=true;leases.toList().also {leases.clear()}}
        var failure:Throwable?=null
        for(lease in owned) try {lease.close()} catch(error:Throwable) {
            if(failure==null) failure=error else failure.addSuppressed(error)
        }
        try {snapshot.close()} catch(error:Throwable) {if(failure==null) failure=error else failure.addSuppressed(error)}
        failure?.let {throw it}
    }
    companion object {
        /** The caller owns initialLease until this returns successfully. */
        internal fun open(snapshot:BundleSnapshot,inspected:NativeSourceVersion.Inspected,resources:PdfResources,
                          initialLease:AutoCloseable,initialIds:Set<String>,checkActive:()->Unit):NativeSourceSnapshot {
            if(snapshot.sourceHash!=inspected.version.sha256) throw ReferenceContractViolation(StorageMsg.REFERENCE_METADATA_INVALID)
            val ids=inspected.info.header.identity?.takeIf {it.externalPdf}?.pdfResource?.let {setOf(it)} ?: emptySet()
            if(ids!=initialIds) throw ReferenceContractViolation(StorageMsg.REFERENCE_METADATA_INVALID)
            checkActive()
            return NativeSourceSnapshot(snapshot,inspected,resources,initialLease,ids,checkActive)
        }
    }
}
