package com.xnotes.pen

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.xnotes.canvas.CanvasState
import com.xnotes.core.FakeRenderer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.AddCanvasItem
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.*
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pdf.PdfPageGeometry
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.format.CanvasCodec
import com.xnotes.mindmap.*
import com.xnotes.persistNativeForMapPresentation
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidRasterSurface
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.LegacySessionQueue
import com.xnotes.storage.*
import com.xnotes.ui.Editor
import com.xnotes.ui.NativeReferenceLocator
import com.xnotes.ui.ReferenceRegionReveal
import com.xnotes.ui.theme.Palette
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Production row tickets, controller, real input View, bundles, PNGs, parking and navigators.
 * Only the original PDF engine's parsing/pixels are injected; no PDFium/device claim is made. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceTargetEditNavigationHostTest {
    private enum class Native {NOTE,CANVAS,DUAL_NOTES}
    private class Fixture(val native:EditorPageCopyFixture,val mode:Native) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val maps=MapWorkspace(native.context,scope)
        val networks=FileNetworkWorkspace(native.context,scope)
        val binding=Any()
        val catalog=OriginalPdfCatalog(native.storage.registry)
        val renders=AtomicInteger()
        val access=OriginalPdfAccess(native.context,engineFactory={object:OriginalPdfEngine {
            override val pageCount=2
            override fun pageInfo(pdfKey:Int):OriginalPdfPageInfo {
                val turn=PdfKey.turn(pdfKey)
                return OriginalPdfPageInfo(if(turn%2==0) Pt(600.0,800.0) else Pt(800.0,600.0),
                    PdfPageGeometry.of(0.0,0.0,600.0,800.0,turn*90))
            }
            override fun renderRegion(pdfKey:Int,pxPerPt:Double,leftPx:Int,topPx:Int,width:Int,height:Int):AndroidRasterSurface {
                renders.incrementAndGet()
                return AndroidRasterSurface.create(width,height).apply {bitmap.eraseColor(if(PdfKey.index(pdfKey)==0) Color.WHITE else Color.LTGRAY)}
            }
            override fun closeAndAwait() {}
        }},storageOverride=native.storage)
        val originals=OriginalPdfWorkspace(native.context,scope,access)
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible()
        val host=FrameLayout(activity.get()).also {activity.get().setContentView(it)}
        val graphFile=File(native.root,"target-edit-navigation.xmap")
        val originalFiles=mutableListOf<Pair<File,ByteArray>>()
        lateinit var original:OriginalPdfCatalog.Record
        lateinit var otherOriginal:OriginalPdfCatalog.Record
        lateinit var source:MapSource
        lateinit var entry:MapWorkspace.Entry
        lateinit var panel:SourceListEditor
        lateinit var controller:SourceTargetEditController
        lateinit var navigator:MapSourceNavigator
        lateinit var originalNavigator:OriginalPdfNavigator
        lateinit var initialAsset:MapPreviewAsset
        val models=mutableListOf<Any>()
        val histories=mutableListOf<Any>()
        val revisions=mutableListOf<Long>()
        val nativeCameras=mutableListOf<Any>()
        lateinit var targetBytes:ByteArray
        var sourceBytes:ByteArray?=null
        var openedRegion:ReferenceRegionReveal?=null
        var time=1L
        var visual:SourceRegionVisualView?=null
        var visualHost:Any?=null
        suspend fun await(check:()->Boolean)=withTimeout(12000) {while(!withContext(Dispatchers.Main) {check()}) delay(1)}
        suspend fun awaitImage(previous:OwnedVisualFrame?=null)=withTimeout(10000) {
            while(!withContext(Dispatchers.Main) {
                // The controller's render debounce follows Android Handler time in this host.
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(10,TimeUnit.MILLISECONDS)
                assertNotEquals("Actual source rendering failed",SourceTargetEditProblem.READ,controller.state?.problem)
                controller.image!=null && controller.image!==previous
            }) delay(1)
        }
        private suspend fun awaitVisualGeometry(view:SourceRegionVisualView)=withTimeout(10000) {
            var previous:Pair<Int,Int>?=null;var stable=0
            while(!withContext(Dispatchers.Main) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(16,TimeUnit.MILLISECONDS)
                val size=view.width to view.height
                val ready=view.isAttachedToWindow && view.isLaidOut && !view.isLayoutRequested &&
                    size.first>0 && size.second>0 && view.viewport.widthPx==size.first && view.viewport.heightPx==size.second
                stable=if(ready && previous==size) stable+1 else if(ready) 1 else 0
                previous=if(ready) size else null
                stable>=3
            }) delay(1)
        }
        private fun stroke(x:Double=100.0)=Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),
            listOf(Sample(x,130.0,.5),Sample(x+90.0,190.0,.8)))
        private fun stopNativeDebounce(editor:Editor) {
            for(name in listOf("noteDebounceJob","canvasDebounceJob"))
                (Editor::class.java.getDeclaredField(name).apply {isAccessible=true}.get(editor) as? Job)?.cancel()
        }
        suspend fun start() {
            when(mode) {
                Native.NOTE->{
                    withContext(Dispatchers.IO) {
                        val saved=Document(mutableListOf(Page(500.0,500.0),Page(500.0,500.0)),dpi=72).apply {enableReferenceAnchors()}
                        saved.pages.forEach {it.items+=stroke()}
                        native.target.outputStream().use {native.codec.write(saved.fixedWriteSnapshot(),it)}
                    }
                    withContext(Dispatchers.Main) {
                        native.editor.openAsync(native.targetUri,"Actual note.xnote")
                        native.editor.view.layout(0,0,1000,800)
                        native.addStroke(300.0,recordHistory=true);stopNativeDebounce(native.editor)
                    }
                }
                Native.CANVAS->{
                    withContext(Dispatchers.IO) {
                        val saved=InfiniteDocument().apply {add(stroke())}
                        native.target.outputStream().use {CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer()).write(saved,it)}
                    }
                    withContext(Dispatchers.Main) {
                        assertTrue(native.editor.openCanvasAsync(native.targetUri,"Actual canvas.xcanvas"))
                        val canvas=native.editor.infinite;val item=stroke(350.0)
                        canvas.document.add(item);canvas.history.push(AddCanvasItem(canvas.document,item));canvas.document.dirty=true
                        stopNativeDebounce(native.editor)
                    }
                }
                Native.DUAL_NOTES->{
                    openParkingPanes(native)
                    withContext(Dispatchers.Main) {for(pane in native.editor.openPanes) {
                        parkingPen(pane,MotionEvent.ACTION_DOWN,Pt(100.0,100.0));parkingPen(pane,MotionEvent.ACTION_UP,Pt(160.0,140.0))
                        stopNativeDebounce(pane)
                    }}
                }
            }
            withContext(Dispatchers.Main) {
                native.editor.originalPdfAccess=access
                if(mode==Native.CANVAS) {
                    val canvas=native.editor.infinite
                    models+=canvas.document;histories+=canvas.history;revisions+=canvas.document.bodyRevision
                    nativeCameras+=canvas.viewport.toWaypoint()
                } else for(pane in native.editor.openPanes) {
                    models+=pane.state.document;histories+=pane.history;revisions+=pane.state.document.bodyRevision
                    nativeCameras+=pane.state.snapshotView()
                }
            }
            targetBytes=native.target.readBytes();sourceBytes=native.source.takeIf {it.exists()}?.readBytes()
            withContext(Dispatchers.IO) {
                fun retain(name:String):OriginalPdfCatalog.Record {
                    val file=File(native.root,"$name.pdf").apply {writeText("immutable original $name")}
                    originalFiles+=file to file.readBytes()
                    val candidate=access.candidate(file,name)
                    return try {(catalog.retain(candidate) as OriginalPdfCatalog.Retention.Retained).observation.record}
                    finally {candidate.closeAndAwait()}
                }
                original=retain("Original A");otherOriginal=retain("Original B")
            }
            val target=withContext(Dispatchers.Main) {if(mode==Native.NOTE) {
                val doc=native.editor.state.document
                MapSourceTarget(doc.identity.documentId,SourceScope.PAGE,doc.pages.first().referenceIdentity.id,Rect(50.0,60.0,250.0,250.0))
            } else MapSourceTarget(original.id,SourceScope.PDF_ORIGINAL,original.pages.first(),
                originalPdf=PdfOriginalProjection(original.sha256,0,userRegion=Rect(30.0,600.0,160.0,120.0)))}
            val preview=withContext(Dispatchers.IO) {if(target.scope==SourceScope.PDF_ORIGINAL)
                OriginalPdfSourcePreviewBackend.load(native.context,listOf(target),setOf(target),access=access,catalog=catalog).single()
                else MapSourcePreviewBackend.load(native.context,listOf(target),setOf(target)).single()}
            initialAsset=requireNotNull(preview.asset)
            source=MapSource(documentId=target.documentId,fileName=preview.fileName.orEmpty(),scope=target.scope,pageId=target.pageId,
                region=target.region,originalPdf=target.originalPdf,note="Keep this exact source remark",lastKnownPage=preview.lastKnownPage,
                sourceVersion=preview.rawVersion,status=SourceStatus.CURRENT,previewKey=initialAsset.key,
                observedVersion=preview.renderedVersion,acceptedPreviewVersion=preview.renderedVersion,
                lastPreview=MapLastPreview(initialAsset.key,target,requireNotNull(preview.renderedVersion),1))
            withContext(Dispatchers.IO) {graphFile.writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(
                MapNode(id="root",parent=null),MapNode(id="concept",parent="root",text="Keep independent body",sources=listOf(source),primarySource=source.id)),
                previewAssets=mapOf(initialAsset.key to initialAsset))))}
            withContext(Dispatchers.Main) {
                maps.bindHost(binding);networks.bindHost(binding);originals.bindHost(binding)
                assertTrue(maps.open(Uri.fromFile(graphFile),"Target edit",preparePresentation={
                    native.editor.awaitHostParking()?.let {proof->({proof.current()})}
                },flushNativeOnPresent=false))
            }
            await {maps.visible && !maps.opening}
            withContext(Dispatchers.Main) {
                entry=maps.current!!;entry.presentation.synchronize(entry.session);entry.session.selected="concept"
                installSourceOwner()
                originalNavigator=OriginalPdfNavigator(native.editor,maps,networks,originals,binding,scope)
                navigator=MapSourceNavigator(native.editor,maps,binding,scope,
                    guard={_,_->MapSourceGuardResult.Ready(emptySet())},originalNavigator=originalNavigator,
                    onOpened={result,_->openedRegion=result.region})
                showMap()
            }
        }
        private fun installSourceOwner() {
            panel=SourceListEditor(entry.session,entry.session.sourceEditSnapshot("concept")!!)
            controller=SourceTargetEditController(native.context,entry.session,scope,
                MapSourceTargetAccess(native.context,storageOverride=native.storage,originalsOverride=access))
            // The production session lazy slot is injected only to substitute PDFium at the engine boundary.
            MapSession::class.java.getDeclaredField("targetEditController").apply {isAccessible=true}.set(entry.session,controller)
        }
        fun actual()=entry.session.document!!.node("concept").sources.single()
        fun install(view:View) {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            host.removeAllViews();host.addView(view,FrameLayout.LayoutParams(-1,-1))
            host.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY))
            host.layout(0,0,1000,800)
        }
        fun showMap():MindMapView {
            val view=MindMapView(activity.get()).apply {document=entry.session.document!!;documentEpoch=entry.session.interactionEpoch}
            install(view)
            entry.presentation.closeSources();entry.presentation.restore(entry.session,view)
            view.restoreViewport(MindMapView.Viewport(.91f,83f,-47f),1000,800,view.resources.displayMetrics.density)
            assertTrue(entry.presentation.capture(entry.session,view));assertTrue(entry.presentation.showSources(entry.session,view,"concept"))
            return view
        }
        suspend fun begin(mode:SourceTargetEditMode=SourceTargetEditMode.RANGE) {
            withContext(Dispatchers.Main) {
                val displayed=actual().target
                val ticket=panel.captureTargetEdit(source.id,displayed,mode)!!
                assertTrue(panel.startTargetEdit(ticket))
            }
            if(mode==SourceTargetEditMode.RANGE) await {controller.state?.step==SourceTargetEditStep.VISUAL}
        }
        suspend fun regionScope() {
            withContext(Dispatchers.Main) {assertTrue(controller.chooseScope(SourceRangeScope.REGION))}
            await {controller.state?.step==SourceTargetEditStep.VISUAL && controller.state?.selectionScope==SourceRangeScope.REGION}
        }
        suspend fun selectWithView(rect:Rect) {
            withContext(Dispatchers.Main) {
                val shown=controller.state!!;val frame=shown.frame!!;val token=Any()
                detachVisual();assertTrue(controller.attachVisualHost(token,frame));visualHost=token
                val view=SourceRegionVisualView(activity.get());visual=view
                view.bind(frame,frame.bounds,frame.initialRegion,shown.region,true,shown.camera,Color.WHITE,
                    {controller.visualCurrent(token,frame)}, {controller.setRegion(frame,it)},
                    {controller.setVisualBusy(token,frame,it)}, {viewport,camera->controller.render(viewport,token,camera)})
                install(view)
                assertTrue(controller.render(view.viewport.visibleContentRect(),token,view.camera()))
            }
            awaitImage()
            val surface=withContext(Dispatchers.Main) {visual!!}
            awaitVisualGeometry(surface)
            val beforeFit=withContext(Dispatchers.Main) {
                // The real Activity traversal may replace the install helper's initial 1000x800.
                // Fit is the actual UI action; resize intentionally preserves the previous camera.
                val previous=controller.image
                surface.fit()
                if(controller.state!!.kind==SourceRangeKind.CANVAS) surface.zoomBy(.25)
                assertTrue(controller.render(surface.viewport.visibleContentRect(),visualHost!!,surface.camera()))
                previous
            }
            awaitImage(beforeFit)
            withContext(Dispatchers.Main) {
                val image=controller.image
                assertNotNull("The actual source render must succeed before input: ${controller.state?.problem}",image)
                val view=visual!!;view.image(image!!.bitmap,image.frame) // Same adopted-image update as SourceTargetVisualSurface.
                val start=view.viewport.contentToViewport(Pt(rect.x,rect.y));val end=view.viewport.contentToViewport(Pt(rect.right,rect.bottom))
                for(point in listOf(start,end)) {
                    val diagnostic="world=$rect point=$point surface=${view.width}x${view.height} viewport=${view.viewport.widthPx}x${view.viewport.heightPx} camera=${view.camera()} frame=${controller.state!!.frame!!.bounds} image=${image.frame} layoutRequested=${view.isLayoutRequested}"
                    assertTrue("Horizontal selection endpoint is outside the actual host: $diagnostic",point.x in 0.0..view.width.toDouble())
                    assertTrue("Vertical selection endpoint is outside the actual host: $diagnostic",point.y in 0.0..view.height.toDouble())
                }
                fun touch(action:Int,point:Pt) {
                    val properties=MotionEvent.PointerProperties().apply {id=7;toolType=MotionEvent.TOOL_TYPE_STYLUS}
                    val coords=MotionEvent.PointerCoords().apply {x=point.x.toFloat();y=point.y.toFloat();pressure=.6f}
                    val event=MotionEvent.obtain(1,time++,action,1,arrayOf(properties),arrayOf(coords),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
                    try {assertTrue(view.onTouchEvent(event))} finally {event.recycle()}
                }
                val old=controller.state!!.region
                touch(MotionEvent.ACTION_DOWN,start);touch(MotionEvent.ACTION_MOVE,end)
                assertEquals(old,controller.state!!.region);assertFalse(controller.canConfirm())
                touch(MotionEvent.ACTION_UP,end)
                assertRect(rect,controller.state!!.region);assertTrue(controller.canConfirm())
            }
        }
        fun detachVisual() {visual?.retire();visual=null;visualHost?.let(controller::detachVisualHost);visualHost=null}
        suspend fun confirm():MapSourceTarget {
            withContext(Dispatchers.Main) {assertTrue(controller.confirm())}
            await {controller.state?.step==SourceTargetEditStep.SAVED}
            await {source.id !in panel.busySourceIds}
            return withContext(Dispatchers.Main) {
                assertTrue(controller.state!!.ticket.accepted);assertEquals(source.id,actual().id);assertEquals(source.note,actual().note)
                val result=actual().target;detachVisual();assertTrue(controller.cancel());result
            }
        }
        suspend fun coldWithOldPng(target:MapSourceTarget) {
            withContext(Dispatchers.Main) {entry.session.cancelPendingAutosave();assertTrue(entry.session.saveAttempt().await() is MapSaveOutcome.Written)}
            val cold=withContext(Dispatchers.IO) {MapCodec.decode(graphFile.readBytes())}
            val saved=cold.node("concept").sources.single()
            assertEquals(target,saved.target);assertEquals(source.id,saved.id);assertEquals(source.lastPreview,saved.lastPreview)
            assertTrue(saved.hasHistoricalPreview);assertArrayEquals(initialAsset.pngBytes(),cold.previewAssets.getValue(initialAsset.key).pngBytes())
            assertEquals("Keep independent body",cold.node("concept").text);assertEquals(source.id,cold.node("concept").primarySource)
            val retired=withContext(Dispatchers.Main) {
                panel.close();controller.close();maps.close(entry)
            }
            retired?.join()
            val oldSession=entry.session
            withContext(Dispatchers.Main) {assertTrue(maps.open(Uri.fromFile(graphFile),"Cold target edit",preparePresentation={
                native.editor.awaitHostParking()?.let {proof->({proof.current()})}
            },flushNativeOnPresent=false))}
            await {maps.visible && !maps.opening}
            withContext(Dispatchers.Main) {
                entry=maps.current!!;assertNotSame("The cold open must use a new actual map actor",oldSession,entry.session)
                assertEquals(cold,entry.session.document);assertFalse(entry.session.canUndo)
                entry.presentation.synchronize(entry.session);entry.session.selected="concept"
                installSourceOwner();showMap()
            }
        }
        suspend fun refreshNativePreview(target:MapSourceTarget) {
            withContext(Dispatchers.Main) {assertTrue(panel.refresh(source.id,target))}
            await {source.id !in panel.busySourceIds}
            withContext(Dispatchers.Main) {
                val current=actual();assertEquals(target,current.lastPreview!!.target);assertEquals(SourceStatus.CURRENT,current.status)
                assertNotNull(entry.session.document!!.previewAssets[current.lastPreview!!.assetKey]);assertFalse(current.hasHistoricalPreview)
                entry.session.cancelPendingAutosave();assertTrue(entry.session.saveAttempt().await() is MapSaveOutcome.Written)
            }
        }
        suspend fun openAndReturn(original:Boolean) {
            lateinit var expected:MindMapView.Viewport
            val target=withContext(Dispatchers.Main) {
                val view=showMap();expected=view.viewport()
                val target=actual().target;val ticket=panel.captureNavigation(source.id,target)!!
                assertTrue(navigator.open(entry,ticket,entry.session.captureSourceReturn(ticket)!!));target
            }
            if(original) {
                await {originals.visible && !navigator.busy}
                val shown=withContext(Dispatchers.Main) {originals.current!!}
                assertEquals(target,shown.target)
                val retained=withContext(Dispatchers.Main) {shown.retainReader()!!}
                val info=withContext(Dispatchers.IO) {shown.read.pageInfo(PdfKey.of(shown.pageIndex,target.originalPdf!!.viewTurns))}
                val reader=withContext(Dispatchers.Main) {OriginalPdfReader(activity.get(),shown,shown.pageIndex,info,Palette.DEFAULT,retained)}
                try {
                    val count=renders.get()
                    withContext(Dispatchers.Main) {
                        install(reader.view);repeat(3) {reader.view.viewTreeObserver.dispatchOnPreDraw()}
                        val bitmap=Bitmap.createBitmap(1000,800,Bitmap.Config.ARGB_8888)
                        try {reader.view.draw(Canvas(bitmap))} finally {bitmap.recycle()}
                        assertNull(reader.document.path);assertTrue(reader.page.items.isEmpty());assertTrue(reader.page.markups.isEmpty())
                        assertEquals(target.originalPdf!!.pageIndex,reader.page.pdfPage);assertEquals(target.originalPdf!!.viewTurns,reader.page.pdfTurn)
                    }
                    await {renders.get()>count}
                    withContext(Dispatchers.Main) {assertTrue(navigator.returnToMap())}
                    await {maps.visible && !navigator.busy && originals.current==null}
                } finally {withContext(Dispatchers.Main) {reader.retire()};withContext(Dispatchers.IO) {reader.closeAndAwait()}}
            } else {
                await {!maps.visible && !navigator.busy}
                withContext(Dispatchers.Main) {
                    assertNull(navigator.failure);assertEquals(ReferenceRegionReveal.SHOWN,openedRegion)
                    if(mode==Native.CANVAS) {
                        val canvas=native.editor.infinite;canvas.pad.visibility=View.GONE
                        install(canvas.surfacesForAttach());repeat(4) {canvas.toolOverlay.viewTreeObserver.dispatchOnPreDraw()}
                        assertEquals(target.region!!.centerX,canvas.viewport.centerContent.x,.01)
                        assertEquals(target.region!!.centerY,canvas.viewport.centerContent.y,.01)
                    } else {
                        native.editor.pad.visibility=View.GONE;install(native.editor.surfacesForAttach())
                        repeat(4) {native.editor.view.viewTreeObserver.dispatchOnPreDraw()}
                        val locator=(Editor::class.java.getDeclaredField("referenceLocator\$delegate").apply {isAccessible=true}.get(native.editor) as Lazy<*>).value as NativeReferenceLocator
                        var count=0;locator.draw(object:Renderer by FakeRenderer() {override fun strokeRect(rect:Rect,pen:Pen) {count++}})
                        assertEquals(1,count);assertEquals(target.pageId,native.editor.state.document.pages[native.editor.state.currentPageIndex()].referenceIdentity.id)
                    }
                    assertTrue(navigator.returnToMap())
                }
                await {maps.visible && !navigator.busy}
            }
            withContext(Dispatchers.Main) {
                persistNativeForMapPresentation(native.editor,entry)
                assertSame(entry,maps.current);assertEquals("concept",entry.session.selected)
                assertEquals("concept",entry.presentation.sourcesFor);assertEquals(source.id,entry.presentation.sourceFocus!!.sourceId)
                val view=MindMapView(activity.get()).apply {document=entry.session.document!!;documentEpoch=entry.session.interactionEpoch}
                install(view);assertTrue(entry.presentation.restore(entry.session,view));assertCamera(expected,view.viewport())
                assertEquals(target,actual().target)
            }
        }
        suspend fun drainNative() {
            repeat(2) {
                parkingDispatch()
                val panes=withContext(Dispatchers.Main) {listOfNotNull(native.editor,native.editor.secondary)}
                for(pane in panes) {
                    val jobs=withContext(Dispatchers.Main) {listOf("noteWriteJob","canvasWriteJob","sessionWriteJob").mapNotNull {
                        Editor::class.java.getDeclaredField(it).apply {isAccessible=true}.get(pane) as? Job
                    }}
                    jobs.forEach {it.join()}
                    (Editor::class.java.getDeclaredField("sessionOwner").apply {isAccessible=true}.get(pane) as LegacySessionQueue.Owner).awaitAccepted()
                }
            }
        }
        fun assertNativeUnchanged(checkCamera:Boolean=true) {
            if(mode==Native.CANVAS) {
                val canvas=native.editor.infinite;assertSame(models.single(),canvas.document);assertSame(histories.single(),canvas.history)
                assertEquals(revisions.single(),canvas.document.bodyRevision);assertTrue(canvas.document.dirty);assertTrue(canvas.history.canUndo)
                if(checkCamera) assertEquals(nativeCameras.single(),canvas.viewport.toWaypoint())
            } else native.editor.openPanes.forEachIndexed {index,pane->
                assertSame(models[index],pane.state.document);assertSame(histories[index],pane.history)
                assertEquals(revisions[index],pane.state.document.bodyRevision);assertTrue(pane.state.document.dirty);assertTrue(pane.history.canUndo)
                if(checkCamera) assertNoteCamera(nativeCameras[index] as CanvasState.ViewSnapshot,pane.state.snapshotView())
            }
            sourceBytes?.let {assertArrayEquals(it,native.source.readBytes())};assertArrayEquals(targetBytes,native.target.readBytes())
            assertEquals(0,native.writes.get());assertEquals(0,native.writeOpens.get())
            originalFiles.forEach {(file,bytes)->assertArrayEquals(bytes,file.readBytes())}
            for((record,bytes) in listOf(original to originalFiles[0].second,otherOriginal to originalFiles[1].second))
                assertArrayEquals(bytes,native.storage.pdfResources.resolve(record.sha256).readBytes())
        }
        suspend fun finish()=withContext(NonCancellable) {
            withContext(Dispatchers.Main) {
                native.beforeTargetRead=null
                if(this@Fixture::controller.isInitialized) {detachVisual();controller.close()}
                if(this@Fixture::panel.isInitialized) panel.close()
                if(this@Fixture::navigator.isInitialized) navigator.dispose()
                if(this@Fixture::originalNavigator.isInitialized) originalNavigator.dispose()
                host.removeAllViews();networks.dispose();maps.dispose()
            }.join()
            withContext(Dispatchers.Main) {originals.dispose()}.join()
            if(this@Fixture::otherOriginal.isInitialized) await {
                native.storage.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM leases WHERE resource_id IN (?,?)",
                    arrayOf(original.sha256,otherOriginal.sha256)).use {it.moveToFirst();it.getInt(0)==0}
            }
            scope.cancel();withContext(Dispatchers.Main) {activity.pause().stop().destroy()}
        }
    }
    private fun scenario(mode:Native,block:suspend(Fixture)->Unit)=runParkingScenario {native->
        val f=withContext(Dispatchers.Main) {Fixture(native,mode)}
        try {f.start();block(f)} finally {f.finish()}
    }
    private companion object {
        fun assertRect(expected:Rect,actual:Rect?) {
            assertNotNull(actual);assertEquals(expected.x,actual!!.x,.001);assertEquals(expected.y,actual.y,.001)
            assertEquals(expected.w,actual.w,.001);assertEquals(expected.h,actual.h,.001)
        }
        fun assertNoteCamera(expected:CanvasState.ViewSnapshot,actual:CanvasState.ViewSnapshot) {
            assertEquals("Native zoom",expected.zoom,actual.zoom,0.0)
            assertEquals("Native horizontal camera",expected.scrollX,actual.scrollX,0.0)
            assertEquals("Native vertical camera",expected.scrollY,actual.scrollY,0.0)
            assertEquals("Native fit width",expected.fitWidth,actual.fitWidth)
            assertEquals("Native fit height",expected.fitHeight,actual.fitHeight)
        }
        fun assertCamera(expected:MindMapView.Viewport,actual:MindMapView.Viewport) {
            assertEquals(expected.zoom,actual.zoom,.000001f);assertEquals(expected.panX,actual.panX,.001f);assertEquals(expected.panY,actual.panY,.001f)
        }
    }

    @Test fun pageRangeUsesTheActualViewThenKeepsItsSourceIdOldPngAndDirtyLiveNoteThroughRefreshAndReturn()=scenario(Native.NOTE) {f->
        f.begin()
        val page=withContext(Dispatchers.Main) {f.native.editor.state.document.pages[1].referenceIdentity.id!!}
        withContext(Dispatchers.Main) {assertTrue(f.controller.choosePage(page))}
        f.await {f.controller.state?.step==SourceTargetEditStep.VISUAL && f.controller.state?.frame?.pageId==page}
        f.regionScope();f.selectWithView(Rect(80.0,100.0,220.0,160.0))
        withContext(Dispatchers.Main) {f.assertNativeUnchanged()}
        val target=f.confirm();assertEquals(page,target.pageId)
        f.coldWithOldPng(target);f.refreshNativePreview(target)
        f.openAndReturn(original=false);f.drainNative()
        withContext(Dispatchers.Main) {f.assertNativeUnchanged(checkCamera=false)}
    }
    @Test fun aFreshCrossDomainReplacementSelectsCanvasWorldRegionAndReturnsToTheSameRetainedDirtyCanvas()=scenario(Native.CANVAS) {f->
        f.begin(SourceTargetEditMode.REPLACE)
        withContext(Dispatchers.Main) {
            val slot=f.controller.capturePicker()!!;assertTrue(f.controller.receivePicker(slot,Uri.parse(f.native.targetUri)))
        }
        f.await {f.controller.state?.step==SourceTargetEditStep.VISUAL}
        withContext(Dispatchers.Main) {assertEquals(SourceRangeKind.CANVAS,f.controller.state!!.kind);assertEquals(SourceRangeScope.WHOLE_FILE,f.controller.state!!.selectionScope);assertNull(f.controller.state!!.region)}
        f.regionScope();f.selectWithView(Rect(50.0,90.0,220.0,180.0))
        val target=f.confirm();assertEquals(SourceScope.CANVAS_REGION,target.scope);assertNull(target.pageId);assertNotEquals(f.source.documentId,target.documentId)
        f.coldWithOldPng(target);assertEquals(6,org.json.JSONObject(f.graphFile.readText()).getInt("version"))
        f.refreshNativePreview(target);f.openAndReturn(original=false);f.drainNative()
        withContext(Dispatchers.Main) {f.assertNativeUnchanged(checkCamera=false)}
    }
    @Test fun originalRangeThenAnotherOriginalChooseFreshPagesAndReturnWithoutSavingEitherDirtyNote()=scenario(Native.DUAL_NOTES) {f->
        f.begin()
        withContext(Dispatchers.Main) {assertTrue(f.controller.choosePage(f.original.pages[1],viewTurns=1))}
        f.await {f.controller.state?.step==SourceTargetEditStep.VISUAL && f.controller.state?.frame?.viewTurns==1}
        f.regionScope();val display=Rect(80.0,100.0,200.0,150.0);f.selectWithView(display)
        val ranged=f.confirm()
        assertEquals(f.original.pages[1],ranged.pageId);assertEquals(1,ranged.originalPdf!!.viewTurns)
        assertRect(Rect(100.0,80.0,150.0,200.0),ranged.originalPdf!!.userRegion)
        f.coldWithOldPng(ranged);f.openAndReturn(original=true);f.drainNative()
        withContext(Dispatchers.Main) {f.assertNativeUnchanged()}
        f.begin(SourceTargetEditMode.REPLACE)
        withContext(Dispatchers.Main) {assertTrue(f.controller.loadOriginals())}
        f.await {f.controller.state?.originalPage!=null && f.controller.state?.step==SourceTargetEditStep.CHOOSE_SOURCE}
        withContext(Dispatchers.Main) {
            val entry=f.controller.state!!.originalPage!!.entries.single {it.documentId==f.otherOriginal.id}
            assertTrue(f.controller.chooseOriginal(entry))
        }
        f.await {f.controller.state?.step==SourceTargetEditStep.VISUAL}
        withContext(Dispatchers.Main) {
            assertEquals(f.otherOriginal.pages.first(),f.controller.state!!.frame!!.pageId)
            assertEquals(0,f.controller.state!!.frame!!.viewTurns);assertNull(f.controller.state!!.region)
            assertEquals(SourceRangeScope.WHOLE_PAGE,f.controller.state!!.selectionScope)
        }
        val replacement=f.confirm();assertEquals(f.otherOriginal.id,replacement.documentId);assertNull(replacement.originalPdf!!.userRegion)
        f.coldWithOldPng(replacement);assertEquals(6,org.json.JSONObject(f.graphFile.readText()).getInt("version"))
        f.openAndReturn(original=true);f.drainNative()
        withContext(Dispatchers.Main) {f.assertNativeUnchanged()}
    }
    @Test fun cancelAndAnExactNoOpCreateNoTargetHistoryWhileFinalIoVersionChangesRefuseTheChosenFrame()=scenario(Native.NOTE) {f->
        f.begin()
        withContext(Dispatchers.Main) {assertTrue(f.controller.cancel());assertFalse(f.entry.session.canUndo)}
        f.begin();withContext(Dispatchers.Main) {assertTrue(f.controller.confirm())}
        f.await {f.controller.state?.step==SourceTargetEditStep.SAVED}
        f.await {f.source.id !in f.panel.busySourceIds}
        withContext(Dispatchers.Main) {assertFalse(f.entry.session.canUndo);assertEquals(f.source.target,f.actual().target);assertTrue(f.controller.cancel())}
        f.begin()
        withContext(Dispatchers.Main) {assertTrue(f.controller.choosePage(f.native.editor.state.document.pages[1].referenceIdentity.id!!))}
        f.await {f.controller.state?.step==SourceTargetEditStep.VISUAL && f.controller.state?.selectionScope==SourceRangeScope.WHOLE_PAGE}
        f.regionScope();f.selectWithView(Rect(80.0,100.0,220.0,160.0))
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        f.native.beforeTargetRead={entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
        try {
            withContext(Dispatchers.Main) {assertTrue(f.controller.confirm())}
            assertTrue(withContext(Dispatchers.IO) {entered.await(5,TimeUnit.SECONDS)})
            withContext(Dispatchers.IO) {
                val external=f.native.target.inputStream().use {f.native.codec.read(it)}
                external.pages[1].width=700.0
                f.native.target.outputStream().use {f.native.codec.write(external.fixedWriteSnapshot(),it)}
            }
            val changed=f.native.target.readBytes();f.native.beforeTargetRead=null;release.countDown()
            f.await {f.controller.state?.step==SourceTargetEditStep.CHANGED}
            withContext(Dispatchers.Main) {
                assertEquals(f.source.target,f.actual().target);assertFalse(f.entry.session.canUndo);assertFalse(f.controller.state!!.ticket.accepted)
                assertTrue(f.maps.visible);assertSame(f.entry,f.maps.current)
                assertSame(f.models.single(),f.native.editor.state.document);assertSame(f.histories.single(),f.native.editor.history)
                assertEquals(f.revisions.single(),f.native.editor.state.document.bodyRevision);assertTrue(f.native.editor.state.document.dirty)
                assertEquals(f.source.lastPreview,f.actual().lastPreview)
            }
            assertArrayEquals(changed,f.native.target.readBytes());assertEquals(0,f.native.writes.get());assertEquals(0,f.native.writeOpens.get())
        } finally {f.native.beforeTargetRead=null;release.countDown()}
    }
}
