package com.xnotes.storage

import android.content.Context
import com.xnotes.core.model.PdfKey
import com.xnotes.core.model.ReferenceAnchors
import com.xnotes.format.BundleMigration
import com.xnotes.mindmap.MapSourceTarget
import com.xnotes.mindmap.SourceScope
import java.io.File

/** Pure-original readers own independent engines and leases; no native note save authority is issued. */
internal class OriginalPdfAccess(context:Context,engineFactory:((File)->OriginalPdfEngine)?=null,
    storageOverride:NotebookStorage?=null) {
    private val storage=storageOverride ?: NotebookStorage.get(context)
    private val resources by lazy {storage.pdfResources}
    private val catalog=OriginalPdfCatalog(storage.registry)
    private val factory=engineFactory ?: {file:File->OriginalPdfSourceEngine(file)}
    internal class Candidate private constructor(
        internal val catalog:OriginalPdfCatalog,private val stamp:OriginalPdfCatalog.Stamp,
        private val file:File,private val lease:AutoCloseable,private val engine:OriginalPdfEngine,
        val title:String,val sha256:String,val pageCount:Int,private val relinkExpected:OriginalPdfCatalog.Observation?,
    ) {
        private val closeGate=Any()
        @Volatile private var retired=false
        private var released=false
        fun current()=!retired && stamp.current()
        internal fun belongs(other:OriginalPdfCatalog)=catalog.sameDatabase(other)
        internal fun authorizesRelink(expected:OriginalPdfCatalog.Observation)=relinkExpected===expected && current()
        fun pageInfo(pdfKey:Int):OriginalPdfPageInfo {check(current());return engine.pageInfo(pdfKey).also {check(current())}}
        internal fun verify(other:OriginalPdfCatalog) {
            check(current() && belongs(other))
            require(BundleMigration.hash(file) {check(current());false}==sha256 && engine.pageCount==pageCount)
            check(current())
        }
        fun closeAndAwait()=synchronized(closeGate) {
            retired=true
            if(!released) {
                // A failed native wait conservatively keeps the lease; a caller may retry the wait.
                engine.closeAndAwait();lease.close();released=true
            }
        }
        companion object {
            internal fun issue(catalog:OriginalPdfCatalog,stamp:OriginalPdfCatalog.Stamp,file:File,lease:AutoCloseable,
                engine:OriginalPdfEngine,title:String,sha256:String,pageCount:Int,relinkExpected:OriginalPdfCatalog.Observation?=null)=
                Candidate(catalog,stamp,file,lease,engine,title,sha256,pageCount,relinkExpected)
        }
    }
    internal class Read private constructor(
        val observation:OriginalPdfCatalog.Observation,val target:MapSourceTarget,val pageIndex:Int,
        val engine:OriginalPdfEngine,private val lease:AutoCloseable,private val domain:OriginalPdfCatalog.IdentityDomainsBatch,
    ) {
        private val closeGate=Any()
        @Volatile private var retired=false
        private var released=false
        fun current()=!retired && observation.current() && domain.current()
        fun pageInfo(pdfKey:Int):OriginalPdfPageInfo {check(current());return engine.pageInfo(pdfKey).also {check(current())}}
        fun closeAndAwait()=synchronized(closeGate) {
            retired=true
            if(!released) {engine.closeAndAwait();lease.close();released=true}
        }
        companion object {internal fun issue(observation:OriginalPdfCatalog.Observation,target:MapSourceTarget,
            pageIndex:Int,engine:OriginalPdfEngine,lease:AutoCloseable,domain:OriginalPdfCatalog.IdentityDomainsBatch)=Read(observation,target,pageIndex,engine,lease,domain)}
    }
    fun identityDomains(ids:Set<String>,checkActive:()->Unit={}):OriginalPdfCatalog.IdentityDomainsBatch =
        catalog.identityDomains(ids,checkActive)
    fun candidate(file:File,title:String,checkActive:()->Unit={}):Candidate {
        require(title.length<=4096)
        // Freeze the epoch before hashing/admission/engine opening, not after that slow IO.
        val start=catalog.stamp();checkActive()
        val held=resources.admitHeld(file,title) {checkActive();check(start.current())}
        return prepare(held,title,start,checkActive)
    }
    fun candidate(sha256:String,title:String,checkActive:()->Unit={}):Candidate {
        require(sha256.matches(Regex("[0-9a-f]{64}")) && title.length<=4096)
        val start=catalog.stamp();checkActive()
        val lease=resources.holdResolved(setOf(sha256)) {checkActive();check(start.current())}
        return prepare(PdfResources.HeldPdf(File(resources.directory,"$sha256.pdf"),lease),title,start,checkActive)
    }
    private fun prepare(held:PdfResources.HeldPdf,title:String,start:OriginalPdfCatalog.Stamp,checkActive:()->Unit,
        relinkExpected:OriginalPdfCatalog.Observation?=null):Candidate {
        var engine:OriginalPdfEngine?=null
        try {
            fun check() {checkActive();kotlin.check(start.current())}
            check();val sha=held.file.nameWithoutExtension
            require(BundleMigration.hash(held.file) {check();false}==sha)
            engine=factory(held.file)
            val count=engine.pageCount;require(count in 1..ReferenceAnchors.MAX_TARGETS)
            check()
            return Candidate.issue(catalog,start,held.file,held.lease,engine,title,sha,count,relinkExpected)
        } catch(error:Throwable) {
            // Keep resource protection if actual native close could not be established.
            try {engine?.closeAndAwait();held.lease.close()} catch(close:Throwable) {error.addSuppressed(close)}
            throw error
        }
    }
    /** Explicit same-original re-association may repair missing/damaged managed bytes, never change them. */
    fun relink(file:File,expected:OriginalPdfCatalog.Observation,title:String,checkActive:()->Unit={}):Candidate {
        val notification=catalog.beginNetworkMutation()
        try {
            checkActive()
            val record=expected.record
            val start=catalog.beginRelink(expected)
            fun check() {checkActive();kotlin.check(start.current())}
            require(BundleMigration.hash(file) {check();false}==record.sha256)
            file.inputStream().use {input->
                val guarded=object:java.io.FilterInputStream(input) {
                    override fun read():Int {check();return super.read()}
                    override fun read(b:ByteArray,o:Int,n:Int):Int {check();return `in`.read(b,o,n)}
                }
                resources.restore(guarded,record.sha256,title)
            }
            check()
            val lease=resources.holdResolved(setOf(record.sha256),::check)
            return prepare(PdfResources.HeldPdf(File(resources.directory,"${record.sha256}.pdf"),lease),title,start,::check,expected).also {
                if(it.pageCount!=record.pageCount) {it.closeAndAwait();error("Original page count changed")}
            }
        } finally {notification.close()}
    }

    fun open(target:MapSourceTarget,checkActive:()->Unit={}):Read {
        target.validate();require(target.scope==SourceScope.PDF_ORIGINAL)
        val domain=identityDomains(setOf(target.documentId),checkActive)
        check(domain.supported && domain.current() && target.documentId in domain.reservedOriginalIds && target.documentId !in domain.nativeIdentityIds)
        // The original observation is obtained before resource validation and slow native IO.
        val observed=requireNotNull(catalog.observe(target.documentId))
        check(!observed.record.deleted);checkActive()
        val record=observed.record;val projection=requireNotNull(target.originalPdf)
        val index=record.pages.indexOf(target.pageId)
        require(index>=0 && index==projection.pageIndex && projection.sha256==record.sha256)
        val lease=resources.holdResolved(setOf(record.sha256)) {checkActive();check(observed.current() && domain.current())}
        var engine:OriginalPdfEngine?=null
        try {
            val file=File(resources.directory,"${record.sha256}.pdf")
            require(BundleMigration.hash(file) {checkActive();check(observed.current() && domain.current());false}==record.sha256)
            engine=factory(file);require(engine.pageCount==record.pageCount)
            val info=engine.pageInfo(PdfKey.of(index,projection.viewTurns))
            projection.userRegion?.let {region->require(contains(info.displayBounds,info.toDisplay(region)))}
            checkActive();check(observed.current() && domain.current())
            // Re-read the complete actual row/mapping after IO, not merely the dirty flag or SHA.
            val final=requireNotNull(catalog.observe(record.id));check(final.record==record && observed.current() && domain.current())
            val finalDomain=identityDomains(setOf(record.id),checkActive)
            check(domain.current() && finalDomain.supported && record.id in finalDomain.reservedOriginalIds && record.id !in finalDomain.nativeIdentityIds)
            return Read.issue(observed,target,index,engine,lease,domain)
        } catch(error:Throwable) {
            try {engine?.closeAndAwait();lease.close()} catch(close:Throwable) {error.addSuppressed(close)}
            throw error
        }
    }
    companion object {
        internal fun contains(outer:com.xnotes.core.geometry.Rect,inner:com.xnotes.core.geometry.Rect):Boolean {
            val e=1e-6
            return inner.w>0 && inner.h>0 && listOf(inner.x,inner.y,inner.w,inner.h).all {it.isFinite()} &&
                inner.left>=outer.left-e && inner.top>=outer.top-e && inner.right<=outer.right+e && inner.bottom<=outer.bottom+e
        }
    }
}
