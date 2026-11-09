package com.xnotes.mindmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.provider.OpenableColumns
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.BundleHeader
import com.xnotes.format.BundleMigration
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.NativeDocumentPreview
import com.xnotes.storage.NativeSourceSnapshot
import com.xnotes.storage.NotebookStorage
import com.xnotes.storage.PdfResources
import com.xnotes.storage.PreparedNativeOpen
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock

internal data class MapSourcePreviewRead(
    val target:MapSourceTarget,val status:SourceStatus,val rawVersion:String?=null,val renderedVersion:String?=null,
    val fileName:String?=null,val lastKnownPage:Int?=null,val asset:MapPreviewAsset?=null,val failedPreview:Boolean=false,
)

/** One IO-only actual file observation for all requested cards. Never adopts, saves or migrates it. */
internal object MapSourcePreviewBackend {
    private const val SIDE=256
    suspend fun load(context:Context,targets:List<MapSourceTarget>,render:Set<MapSourceTarget>,checkActive:()->Unit={}):List<MapSourcePreviewRead> {
        val coroutine=currentCoroutineContext()
        val check={coroutine.ensureActive();checkActive()}
        require(targets.isNotEmpty() && targets.all {it.documentId==targets[0].documentId})
        targets.forEach {it.validate()};require(render.all {it in targets});check()
        try {
            val app=context.applicationContext
            com.xnotes.platform.FontCatalog.ensureInitialized(app)
            com.xnotes.platform.TemplateLibrary.ensureInitialized(app)
            check()
            val storage=NotebookStorage.get(context);val id=targets.first().documentId
            val uri=storage.registry.resolve(id) ?: return unavailable(targets,render)
            val parsed=Uri.parse(uri)
            val name=context.contentResolver.query(parsed,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)
                ?.use {if(it.moveToFirst()) it.getString(0) else null} ?: storage.registry.nameForId(id) ?: parsed.lastPathSegment.orEmpty()
            val kind=storage.registry.kindForId(id)?.let {key->DocumentKind.entries.singleOrNull {it.extension==key}} ?: DocumentKind.ofName(name)
            check()
            return when(kind) {
                DocumentKind.NOTE,DocumentKind.CANVAS->ordinarySnapshot(context,storage,uri,name,kind,targets,render,check)
                DocumentKind.MIND_MAP->map(context,storage,parsed,name,targets,render,check)
                else->unavailable(targets,render,name)
            }
        } catch(error:CancellationException) {throw error}
        catch(_:Exception) {return unavailable(targets,render)}
    }
    private fun unavailable(targets:List<MapSourceTarget>,render:Set<MapSourceTarget>,name:String?=null)=
        targets.map {MapSourcePreviewRead(it,SourceStatus.UNAVAILABLE,fileName=name,failedPreview=it in render)}

    private fun ordinarySnapshot(context:Context,storage:NotebookStorage,uri:String,name:String,kind:DocumentKind,
                                 targets:List<MapSourceTarget>,render:Set<MapSourceTarget>,checkActive:()->Unit):List<MapSourcePreviewRead> =
        storage.openSource(uri,kind,checkActive).use {source->
            val catalog=source.referenceCatalog();require(catalog.documentId==targets.first().documentId)
            // The holder already owns the lease. An explicit check must not trust only a PDF stamp.
            BundleHeader.read(source.file) {checkActive();false}.identity?.takeIf {it.externalPdf}?.pdfResource?.let {id->
                if(BundleMigration.hash(storage.pdfResources.resolveChecked(id,checkActive)) {checkActive();false}!=id) throw PdfResources.Unavailable()
            }
            val raw=source.version.sha256;val version="native-sha256:$raw"
            val pages=catalog.pages as? NativeSourceSnapshot.ReferenceCatalog.Pages.Available
            val base=targets.map {target->
                checkActive()
                val status=when {
                    target.scope==SourceScope.FILE->SourceStatus.CURRENT
                    target.scope==SourceScope.CANVAS_REGION->if(kind==DocumentKind.CANVAS) SourceStatus.CURRENT else SourceStatus.UNAVAILABLE
                    kind!=DocumentKind.NOTE || pages==null->SourceStatus.UNAVAILABLE
                    target.pageId?.let {it in pages.ids}!=true->SourceStatus.DELETED
                    else->SourceStatus.CURRENT
                }
                MapSourcePreviewRead(target,status,raw,version,name,
                    if(target.scope==SourceScope.PAGE && status==SourceStatus.CURRENT) pages!!.orderedIds.indexOf(target.pageId)+1 else null)
            }
            if(base.none {it.target in render && it.status==SourceStatus.CURRENT}) return@use base
            val text=AndroidTextMeasurer();val image=AndroidImageCodec()
            val noteCodec=DocumentCodec(image,text).apply {resolvePdf={storage.pdfResources.resolveChecked(it,checkActive)}}
            val canvasCodec=CanvasCodec(image,text)
            val assetRoot=File(context.cacheDir,"source-preview-assets").apply {check(isDirectory || mkdirs())}
            val pdfDir=File(context.cacheDir,"source-preview-pdfs").apply {check(isDirectory || mkdirs())}
            PreparedNativeOpen.read(source,storage.pdfResources,noteCodec,canvasCodec,pdfDir,assetRoot,null,
                requireCompleteCanvas=targets.any {it.scope==SourceScope.CANVAS_REGION},checkActive=checkActive).use {prepared->
                NativeDocumentPreview(context,text).use {painter->
                    val note=prepared.note
                    val byId=note?.pages?.mapIndexedNotNull {index,page->page.referenceIdentity.id?.let {it to index}}?.toMap().orEmpty()
                    fun index(read:MapSourcePreviewRead):Int?=if(read.target.scope==SourceScope.FILE) 0 else read.target.pageId?.let {byId[it]}
                    base.filter {it.target in render && it.status==SourceStatus.CURRENT}.mapNotNull(::index).maxOrNull()?.let {highest->
                        if(note!=null) painter.prepareNote(note,highest,checkActive)
                    }
                    base.map {read->
                        checkActive()
                        if(read.target !in render || read.status!=SourceStatus.CURRENT) read
                        else try {
                            if(note!=null) {
                                val at=requireNotNull(index(read));val page=note.pages[at];val region=read.target.region
                                if(region!=null && NativeDocumentPreview.intersect(NativeDocumentPreview.footprint(note,page),region)==null) read.copy(status=SourceStatus.EMPTY)
                                else read.copy(asset=png(requireNotNull(painter.note(note,at,SIDE,region,checkActive)),checkActive))
                            } else {
                                val canvas=requireNotNull(prepared.canvas)
                                if(read.target.scope==SourceScope.CANVAS_REGION) {
                                    val region=requireNotNull(read.target.region)
                                    val visible=canvas.layers.ordered(canvas.items.toList())
                                    if(visible.none {checkActive();it.paintBounds().intersects(region)}) read.copy(status=SourceStatus.EMPTY)
                                    else read.copy(asset=png(painter.canvasRegion(canvas,region,SIDE,checkActive),checkActive))
                                } else read.copy(asset=png(painter.canvas(canvas,SIDE,SIDE,checkActive),checkActive))
                            }
                        } catch(error:CancellationException) {throw error}
                        catch(_:Exception) {read.copy(failedPreview=true)}
                    }.also {checkActive()}
                }
            }
        }

    private suspend fun map(context:Context,storage:NotebookStorage,uri:Uri,name:String,targets:List<MapSourceTarget>,
                    render:Set<MapSourceTarget>,checkActive:()->Unit):List<MapSourcePreviewRead> {
        // The same canonical lock as autosave protects only the owned byte capture. A slow decode,
        // semantic digest or painter must never keep a writer waiting behind a preview.
        val bytes=MapSaves.lockFor(uri).withLock {
            checkActive();MapFiles.target(context,uri,checkActive).read().also {checkActive()}
        }
        checkActive()
        val doc=MapCodec.decode(bytes);require(doc.id==targets.first().documentId)
        val at=storage.registry.expectationAt(uri.toString())
        require(at==null || at.documentId==doc.id && !at.requiresPages);require(!storage.registry.expectationForId(doc.id).requiresPages)
        val raw=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
        val version=MapCodec.semanticPreviewVersion(doc)
        var asset:MapPreviewAsset?=null;var failed=false
        if(targets.any {it in render && it.scope==SourceScope.FILE}) try {
            val painter=MapPainter().apply {
                collapsedLabel={context.getString(com.xnotes.R.string.graph_hidden_nodes_count,it)}
                sourceLabel={context.getString(com.xnotes.R.string.graph_sources_count,it)}
            }
            val bounds=painter.drawingBounds(doc);val bitmap=Bitmap.createBitmap(SIDE,SIDE,Bitmap.Config.ARGB_8888)
            try {
                val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
                val scale=minOf(SIDE/bounds.w,SIDE/bounds.h).toFloat()
                canvas.translate(SIDE/2f-bounds.centerX.toFloat()*scale,SIDE/2f-bounds.centerY.toFloat()*scale);canvas.scale(scale,scale)
                checkActive();painter.draw(canvas,doc);checkActive()
            } catch(error:Throwable) {bitmap.recycle();throw error}
            asset=png(bitmap,checkActive)
        } catch(error:CancellationException) {throw error}
        catch(_:Exception) {failed=true}
        checkActive()
        return targets.map {target->
            if(target.scope!=SourceScope.FILE) MapSourcePreviewRead(target,SourceStatus.UNAVAILABLE,raw,version,name,failedPreview=target in render)
            else MapSourcePreviewRead(target,SourceStatus.CURRENT,raw,version,name,asset=if(target in render) asset else null,failedPreview=target in render && failed)
        }
    }
    /** Consumes this one temporary bitmap even when compression, validation or cancellation fails. */
    private fun png(bitmap:Bitmap,checkActive:()->Unit):MapPreviewAsset = try {
        checkActive();val bytes=ByteArrayOutputStream();check(bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes));checkActive()
        MapPreviewAsset.fromPng(bytes.toByteArray()).also {checkActive()}
    } finally {bitmap.recycle()}
}
