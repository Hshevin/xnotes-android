package com.xnotes.mindmap

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.xnotes.R
import com.xnotes.canvas.CanvasState
import com.xnotes.canvas.CanvasView
import com.xnotes.canvas.InitialView
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.PdfKey
import com.xnotes.platform.AndroidSurfaceFactory
import com.xnotes.platform.awaitClosedExecutor
import com.xnotes.storage.OriginalPdfPageInfo
import com.xnotes.ui.NativeReferenceLocator
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.Palette
import com.xnotes.ui.theme.Space
import com.xnotes.ui.theme.TextSize
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlin.math.ceil
import kotlin.math.floor

/** A private disposable projection. It is never adopted by Editor or handed to any encoder/writer. */
internal class OriginalPdfReader(context:Context,private val entry:OriginalPdfWorkspace.Entry,
    private val index:Int,info:OriginalPdfPageInfo,palette:Palette,private val retained:OriginalPdfWorkspace.Entry.Reader) {
    val page=Page(info.sizePoints.x,info.sizePoints.y,pdfPage=index,pdfTurn=entry.target.originalPdf!!.viewTurns)
    val document=Document(mutableListOf(page),dpi=72)
    val state=CanvasState(document,AndroidSurfaceFactory(),palette)
    val view=CanvasView(context)
    private val active=AtomicBoolean(true)
    private val handler=Handler(Looper.getMainLooper())
    private val posts=Any()
    private val executor=Executors.newSingleThreadExecutor {r->Thread(r,"xnotes-original-reader").apply {isDaemon=true}}
    private val key=PdfKey.of(index,page.pdfTurn)
    private val locator=NativeReferenceLocator(state,view) {saveCamera()}
    val input=OriginalPdfReadOnlyInput(state,::current,{saveCamera();view.requestRender()},locator::cancelPending)
    var failed by mutableStateOf(false);private set
    private fun current()=active.get() && retained.current() && entry.pageIndex==index
    private fun saveCamera() {entry.saveCamera(retained,index,state.snapshotView())}
    init {
        state.freeMovement=true
        val saved=entry.camera?.takeIf {entry.cameraPage==index}
        saved?.let {state.pendingInitialView=InitialView.Restore(it.zoom,it.scrollX,it.scrollY)}
        state.paintPageBackground={actual,renderer,res,region->
            if(actual===page && current()) {
                val left=maxOf(0.0,region.left);val top=maxOf(0.0,region.top)
                val right=minOf(page.width,region.right);val bottom=minOf(page.height,region.bottom)
                if(right>left && bottom>top) {
                    val x=floor(left*res).toInt();val y=floor(top*res).toInt()
                    val width=ceil(right*res).toInt()-x;val height=ceil(bottom*res).toInt()-y
                    var raster:com.xnotes.platform.AndroidRasterSurface?=null
                    try {
                        raster=entry.read.engine.renderRegion(key,res,x,y,width,height)
                        if(raster!=null && current()) renderer.drawRaster(raster,Rect(x/res,y/res,width/res,height/res))
                        else reportFailure()
                    } catch(_:Exception) {reportFailure()}
                    finally {raster?.recycle()}
                }
            }
        }
        view.state=state // CanvasView's defaults are replaced with this host's drainable worker below.
        state.runAsync={work->if(current()) runCatching {executor.execute {if(current()) work()}}}
        state.postToMain={work->if(active.get()) handler.postAtTime({if(current()) work()},posts,SystemClock.uptimeMillis())}
        state.onCacheReady={if(current()) view.requestRender()}
        view.input=input::onTouch
        view.onInputLost=input::cancel
        view.onKey={it.keyCode!=KeyEvent.KEYCODE_BACK}
        view.drawOverlay={renderer,_->locator.draw(renderer)}
        var restorePending=saved!=null
        view.afterLayout={
            if(current()) {
                if(restorePending && saved!=null) {state.restoreView(saved);restorePending=false}
                locator.applyIfReady()
            }
        }
        if(saved==null) locator.show(document,page,
            entry.target.originalPdf!!.userRegion?.takeIf {index==entry.read.pageIndex}?.let(info::toDisplay))
    }
    private fun reportFailure() {
        if(active.get()) handler.postAtTime({if(active.get()) failed=true},posts,SystemClock.uptimeMillis())
    }
    /** Main-thread retirement fences every old callback before another host can be created. */
    fun retire() {
        if(!active.compareAndSet(true,false)) return
        saveCamera();input.cancel();locator.clear();handler.removeCallbacksAndMessages(posts)
        view.input={true};view.afterLayout=null;view.drawOverlay=null;view.onInputLost=null;view.state=null
        state.onCacheReady=null;state.runAsync={};state.postToMain={};state.paintPageBackground=null
        state.invalidateAllCaches();state.clearSharpViewport();executor.shutdown()
    }
    /** IO-only. The retained PDF remains alive until this host's last accepted raster job ends. */
    fun closeAndAwait() {awaitClosedExecutor(executor);retained.close()}
}

/** current != null remains exclusive even while a recreated host is acquiring a fresh parking proof. */
@Composable
internal fun OriginalPdfWorkspaceHost(workspace:OriginalPdfWorkspace,onClose:()->Unit,nativeContent:@Composable ()->Unit) {
    val entry=workspace.current
    if(entry==null) {nativeContent();return}
    BackHandler {onClose()}
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(Space.s8),horizontalArrangement=Arrangement.spacedBy(Space.s8),
                verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.title,fontSize=TextSize.body)
                    Text(stringResource(R.string.original_pdf_readonly),fontSize=TextSize.caption)
                }
                MapButton(if(entry.returnTo is OriginalPdfWorkspace.Return.Map) R.string.graph_return_map else R.string.original_pdf_back_library) {onClose()}
            }
            Row(Modifier.fillMaxWidth().padding(horizontal=Space.s8),horizontalArrangement=Arrangement.spacedBy(Space.s8),
                verticalAlignment=Alignment.CenterVertically) {
                MapButton(R.string.previous_page,enabled=workspace.visible && entry.pageIndex>0) {entry.movePage(-1)}
                Text(stringResource(R.string.page_n,entry.pageIndex+1),fontSize=TextSize.body,modifier=Modifier.weight(1f))
                MapButton(R.string.next_page,enabled=workspace.visible && entry.pageIndex+1<entry.pageCount) {entry.movePage(1)}
            }
            if(workspace.visible) key(entry,entry.pageIndex) {OriginalPdfReaderPage(entry,Modifier.weight(1f))}
            else Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {Text(stringResource(R.string.map_loading),fontSize=TextSize.body)}
        }
    }
}

@Composable
private fun OriginalPdfReaderPage(entry:OriginalPdfWorkspace.Entry,modifier:Modifier) {
    val context=LocalContext.current;val palette=LocalPalette.current;val index=entry.pageIndex
    var reader by remember {mutableStateOf<OriginalPdfReader?>(null)}
    var failed by remember {mutableStateOf(false)}
    LaunchedEffect(entry,index) {
        val retained=entry.retainReader()
        var owned:OriginalPdfReader?=null
        try {
            if(retained==null) {failed=true;return@LaunchedEffect}
            val key=PdfKey.of(index,entry.target.originalPdf!!.viewTurns)
            val info=withContext(Dispatchers.IO) {entry.read.pageInfo(key)}
            ensureActive()
            if(!retained.current() || entry.pageIndex!=index) {failed=true;return@LaunchedEffect}
            owned=OriginalPdfReader(context,entry,index,info,palette,retained);reader=owned
            awaitCancellation()
        } catch(cancelled:CancellationException) {throw cancelled}
        catch(_:Exception) {failed=true}
        finally {
            val draining=owned
            draining?.retire()
            withContext(NonCancellable+Dispatchers.IO) {if(draining!=null) draining.closeAndAwait() else retained?.close()}
        }
    }
    val shown=reader
    Box(modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
        if(shown!=null) {
            AndroidView(factory={shown.view},modifier=Modifier.fillMaxSize())
            if(shown.failed) Surface(Modifier.align(Alignment.BottomCenter).padding(Space.s8)) {
                Text(stringResource(R.string.original_pdf_unavailable),fontSize=TextSize.body,modifier=Modifier.padding(Space.s8))
            }
        } else Text(stringResource(if(failed) R.string.original_pdf_unavailable else R.string.map_loading),fontSize=TextSize.body)
    }
}
