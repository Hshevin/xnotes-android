package com.xnotes.ui

import android.content.Context
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.PdfKey
import com.xnotes.core.model.sourceWidth
import com.xnotes.core.model.sourceHeight
import com.xnotes.mindmap.MapSource
import com.xnotes.mindmap.PdfOriginalProjection
import com.xnotes.mindmap.SourceScope
import com.xnotes.mindmap.SourceStatus
import com.xnotes.storage.OriginalPdfAccess
import com.xnotes.storage.OriginalPdfCatalog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The original branch owns only immutable PDF reads and catalog retention, never a note writer. */
internal class OriginalPdfCreationRequest private constructor(private val access:OriginalPdfAccess,
    private val catalog:OriginalPdfCatalog,private val document:Document,private val page:Page,
    private val file:File,private val title:String,private val snapshot:OriginalPdfRegionSnapshot) {
    private var retained:MapSource?=null
    private var restoreOffer:OriginalPdfCatalog.Observation?=null
    private var restoreProjection:PdfOriginalProjection?=null

    private fun choices(issue:SourceCreationIssue)=PageReferencePreparation.ModeRequired(
        setOf(SourceCreationMode.NOTE_PAGE,SourceCreationMode.PDF_ORIGINAL),issue)
    private fun mappingCurrent()=document.pdfFile?.path==file.path && page.pdfKey==snapshot.pdfKey &&
        (snapshot.region==null || document.dpi==snapshot.dpi && page.sourceWidth==snapshot.sourceWidth &&
            page.sourceHeight==snapshot.sourceHeight && page.rooms==snapshot.rooms)
    private fun ready(request:PageReferenceRequest)=request.current() && request.pane.originalPdfReferenceReady(request)

    suspend fun prepare(request:PageReferenceRequest):PageReferencePreparation {
        if(!ready(request)) return choices(SourceCreationIssue.ORIGINAL_CHANGED)
        retained?.let {source->
            val owned=java.util.concurrent.atomic.AtomicReference<OriginalPdfAccess.Read?>()
            return try {
                val coroutine=currentCoroutineContext()
                val read=withContext(Dispatchers.IO) {
                    access.open(source.target) {coroutine.ensureActive();check(request.active())}.also(owned::set)
                }
                if(!ready(request) || !read.current()) PageReferencePreparation.Changed else PageReferencePreparation.Ready(source)
            } catch(cancelled:java.util.concurrent.CancellationException) {
                currentCoroutineContext().ensureActive();PageReferencePreparation.Changed
            } catch(_:Exception) {retained=null;choices(SourceCreationIssue.ORIGINAL_UNAVAILABLE)}
            finally {withContext(NonCancellable+Dispatchers.IO) {owned.getAndSet(null)?.closeAndAwait()}}
        }
        if(!mappingCurrent()) return choices(SourceCreationIssue.ORIGINAL_CHANGED)
        if(snapshot.region!=null && snapshot.displayRegionInPoints()==null) return choices(SourceCreationIssue.ORIGINAL_REGION)
        return prepareOriginal(request,null)
    }
    suspend fun restore(request:PageReferenceRequest,offer:OriginalPdfCatalog.Observation):PageReferencePreparation {
        if(offer!==restoreOffer || !offer.current() || !ready(request) || !mappingCurrent())
            return choices(SourceCreationIssue.ORIGINAL_CHANGED)
        return prepareOriginal(request,offer)
    }
    private suspend fun prepareOriginal(request:PageReferenceRequest,restore:OriginalPdfCatalog.Observation?):PageReferencePreparation {
        val coroutine=currentCoroutineContext()
        val check={coroutine.ensureActive();check(request.active());Unit}
        val candidate=java.util.concurrent.atomic.AtomicReference<OriginalPdfAccess.Candidate?>()
        return try {
            val opened=withContext(Dispatchers.IO) {access.candidate(file,title,check).also(candidate::set)}
            val projection=withContext(Dispatchers.IO) {
                require(PdfKey.index(snapshot.pdfKey) in 0 until opened.pageCount)
                snapshot.map(opened.pageInfo(snapshot.pdfKey))?.let {
                    PdfOriginalProjection(opened.sha256,PdfKey.index(snapshot.pdfKey),PdfKey.turn(snapshot.pdfKey),it.userRegion)
                }
            } ?: return choices(SourceCreationIssue.ORIGINAL_REGION)
            projection.validate()
            if(!ready(request) || !mappingCurrent()) return choices(SourceCreationIssue.ORIGINAL_CHANGED)
            if(restore!=null && (restoreProjection!=projection || !restore.current()))
                return choices(SourceCreationIssue.ORIGINAL_CHANGED)
            // Mapping and owner checks precede the independent, explicitly requested retention.
            val result=withContext(Dispatchers.IO) {
                check()
                if(restore==null) catalog.retain(opened) else OriginalPdfCatalog.Retention.Retained(catalog.restore(restore,opened))
            }
            if(!ready(request) || !mappingCurrent()) return PageReferencePreparation.Changed
            when(result) {
                is OriginalPdfCatalog.Retention.RestoreRequired->{
                    restoreOffer=result.observation;restoreProjection=projection
                    PageReferencePreparation.RestoreRequired(result.observation)
                }
                is OriginalPdfCatalog.Retention.Retained->{
                    // This publication intentionally retires the candidate's old catalog epoch.
                    val observed=result.observation
                    if(!observed.current() || observed.record.deleted) return choices(SourceCreationIssue.ORIGINAL_CHANGED)
                    val record=observed.record;val index=projection.pageIndex
                    require(record.sha256==projection.sha256 && index in record.pages.indices)
                    val source=MapSource(id=request.sourceId,documentId=record.id,fileName=record.title,scope=SourceScope.PDF_ORIGINAL,
                        pageId=record.pages[index],lastKnownPage=index+1,status=SourceStatus.CURRENT,sourceVersion=record.sha256,
                        originalPdf=projection).also {it.validate()}
                    retained=source;restoreOffer=null;restoreProjection=null
                    PageReferencePreparation.Ready(source)
                }
            }
        } catch(cancelled:java.util.concurrent.CancellationException) {
            currentCoroutineContext().ensureActive();PageReferencePreparation.Changed
        } catch(_:Exception) {choices(SourceCreationIssue.ORIGINAL_UNAVAILABLE)}
        finally {withContext(NonCancellable+Dispatchers.IO) {candidate.getAndSet(null)?.closeAndAwait()}}
    }
    companion object {
        fun capture(context:Context,access:OriginalPdfAccess,document:Document,page:Page,region:Rect?):OriginalPdfCreationRequest? {
            val file=document.pdfFile ?: return null
            val key=page.pdfKey ?: return null
            return OriginalPdfCreationRequest(access,OriginalPdfCatalog.get(context),document,page,file,document.title.take(4096),
                OriginalPdfRegionSnapshot(key,document.dpi,page.sourceWidth,page.sourceHeight,page.rooms.toList(),region?.copy()))
        }
    }
}
